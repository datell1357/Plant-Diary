import assert from "node:assert/strict"
import test from "node:test"
import { deleteApp, initializeApp } from "firebase-admin/app"
import { getFirestore } from "firebase-admin/firestore"
import {
  FirestorePlantIdentificationStore,
  PLANT_IDENTIFICATION_LEASE_MILLIS,
} from "./firestore-plant-identification-store.js"
import {
  PLANT_IDENTIFICATION_CONTRACT_VERSION,
  type PlantIdentificationOperation,
  PlantIdentificationProxyError,
  type PlantIdentificationResponse,
  plantIdentificationRequestHash,
} from "./plant-identification-proxy.js"

const { GCLOUD_PROJECT } = process.env
const PROJECT_ID = GCLOUD_PROJECT ?? "demo-planterior-ios-deletion"
const NOW = 1_787_616_000_000
const IMAGES_BASE64 = [Buffer.from("photo").toString("base64")]

function operation(
  ownerID: string,
  requestID = "request-00000001",
  idempotencyKey = "operation-00000001",
): PlantIdentificationOperation {
  return {
    ownerID,
    requestID,
    idempotencyKey,
    requestHash: plantIdentificationRequestHash({ ownerID, requestID }, IMAGES_BASE64),
  }
}

function response(): PlantIdentificationResponse {
  return { kind: "no_candidates" }
}

function deferred(): Readonly<{ promise: Promise<void>; resolve: () => void }> {
  let resolvePromise = (): void => {
    throw new Error("Deferred promise was not initialized")
  }
  const promise = new Promise<void>((resolve) => {
    resolvePromise = () => resolve()
  })
  return { promise, resolve: () => resolvePromise() }
}

async function fixture(name: string, ownerID: string) {
  const app = initializeApp({ projectId: PROJECT_ID }, name)
  const firestore = getFirestore(app)
  await firestore.recursiveDelete(firestore.doc(`users/${ownerID}`))
  return {
    app,
    firestore,
    store: new FirestorePlantIdentificationStore(firestore, () => NOW),
  }
}

async function cleanup(
  app: ReturnType<typeof initializeApp>,
  firestore: ReturnType<typeof getFirestore>,
  ownerID: string,
): Promise<void> {
  await firestore.recursiveDelete(firestore.doc(`users/${ownerID}`))
  await deleteApp(app)
}

test("completed plant identification responses replay without retaining the images", async () => {
  const ownerID = "plant-id-replay-owner"
  const { app, firestore, store } = await fixture("plant-id-store-replay", ownerID)
  const command = operation(ownerID)
  let calls = 0

  try {
    const first = await store.runOnce(command, async () => {
      calls += 1
      return response()
    })
    const replay = await store.runOnce(command, async () => {
      calls += 1
      return { kind: "failed", reason: "provider_unavailable" }
    })
    const stored = await firestore
      .doc(`users/${ownerID}/plantIdentificationOperations/${command.idempotencyKey}`)
      .get()

    assert.deepEqual(first, response())
    assert.deepEqual(replay, first)
    assert.equal(calls, 1)
    assert.equal(stored.get("status"), "completed")
    assert.equal(stored.get("imagesBase64"), undefined)
    assert.deepEqual(Object.keys(stored.data() ?? {}).sort(), [
      "contractVersion",
      "idempotencyKey",
      "leaseExpiresAtEpochMillis",
      "leaseID",
      "ownerUid",
      "requestHash",
      "requestID",
      "response",
      "status",
    ])
  } finally {
    await cleanup(app, firestore, ownerID)
  }
})

test("same idempotency key conflicts for changed requests and valid in-flight leases", async () => {
  const ownerID = "plant-id-conflict-owner"
  const { app, firestore, store } = await fixture("plant-id-store-conflict", ownerID)
  const command = operation(ownerID)
  const reference = firestore.doc(
    `users/${ownerID}/plantIdentificationOperations/${command.idempotencyKey}`,
  )
  const processing = {
    contractVersion: PLANT_IDENTIFICATION_CONTRACT_VERSION,
    ownerUid: ownerID,
    requestID: command.requestID,
    idempotencyKey: command.idempotencyKey,
    requestHash: command.requestHash,
    status: "processing" as const,
    leaseID: "00000000-0000-4000-8000-000000000001",
    leaseExpiresAtEpochMillis: NOW + 1,
  }

  try {
    await reference.set(processing)
    await assert.rejects(
      () => store.runOnce({ ...command, requestID: "request-00000002" }, async () => response()),
      (error: unknown) =>
        error instanceof PlantIdentificationProxyError && error.code === "conflict",
    )
    await assert.rejects(
      () => store.runOnce(command, async () => response()),
      (error: unknown) =>
        error instanceof PlantIdentificationProxyError && error.code === "conflict",
    )
  } finally {
    await cleanup(app, firestore, ownerID)
  }
})

test("expired leases can be taken over but stale workers cannot finalize", async () => {
  const ownerID = "plant-id-lease-owner"
  const { app, firestore, store } = await fixture("plant-id-store-lease", ownerID)
  const command = operation(ownerID)
  const reference = firestore.doc(
    `users/${ownerID}/plantIdentificationOperations/${command.idempotencyKey}`,
  )
  const expiredLease = "00000000-0000-4000-8000-000000000002"

  try {
    await reference.set({
      contractVersion: PLANT_IDENTIFICATION_CONTRACT_VERSION,
      ownerUid: ownerID,
      requestID: command.requestID,
      idempotencyKey: command.idempotencyKey,
      requestHash: command.requestHash,
      status: "processing",
      leaseID: expiredLease,
      leaseExpiresAtEpochMillis: NOW - 1,
    })
    assert.deepEqual(await store.runOnce(command, async () => response()), response())
    const completed = await reference.get()
    assert.equal(completed.get("status"), "completed")
    assert.notEqual(completed.get("leaseID"), expiredLease)
    assert.equal(
      completed.get("leaseExpiresAtEpochMillis"),
      NOW + PLANT_IDENTIFICATION_LEASE_MILLIS,
    )

    await reference.set({
      contractVersion: PLANT_IDENTIFICATION_CONTRACT_VERSION,
      ownerUid: ownerID,
      requestID: command.requestID,
      idempotencyKey: command.idempotencyKey,
      requestHash: command.requestHash,
      status: "processing",
      leaseID: expiredLease,
      leaseExpiresAtEpochMillis: NOW - 1,
    })
    const started = deferred()
    const gate = deferred()
    const pending = store.runOnce(command, async () => {
      started.resolve()
      await gate.promise
      return response()
    })
    await started.promise
    await reference.update({ leaseID: "00000000-0000-4000-8000-000000000003" })
    gate.resolve()
    await assert.rejects(
      pending,
      (error: unknown) =>
        error instanceof PlantIdentificationProxyError && error.code === "conflict",
    )
  } finally {
    await cleanup(app, firestore, ownerID)
  }
})
