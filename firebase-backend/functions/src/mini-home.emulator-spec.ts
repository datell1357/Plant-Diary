import assert from "node:assert/strict"
import test from "node:test"
import { deleteApp, initializeApp } from "firebase-admin/app"
import { getFirestore } from "firebase-admin/firestore"
import { z } from "zod"
import { FirestoreMiniHomeStore, MiniHomeStoreError } from "./firestore-mini-home-store.js"
import { OwnerUidSchema } from "./mini-home-contract.js"
import { loadMiniHome, miniHomeAuth, saveMiniHome } from "./mini-home-service.js"

const { AUTH_EMULATOR_PORT, FIRESTORE_EMULATOR_HOST, GCLOUD_PROJECT } = process.env
const PROJECT_ID = GCLOUD_PROJECT ?? "demo-planterior-ios-deletion"
const authPort = AUTH_EMULATOR_PORT ?? "9299"
const firestoreHost = FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8280"
const NOW = 1_787_616_000_000

async function fixture(name: string) {
  const app = initializeApp({ projectId: PROJECT_ID }, name)
  const firestore = getFirestore(app)
  await firestore.recursiveDelete(firestore.collection("users"))
  let tick = 0
  return {
    app,
    firestore,
    store: new FirestoreMiniHomeStore(firestore, () => NOW + tick++),
  }
}

type SaveFixture = Readonly<{
  operationId: string
  name: string
  placements?: readonly Readonly<Record<string, unknown>>[]
}>

function request(ownerUid: string, expectedRevision: number, save: SaveFixture) {
  return {
    expectedOwnerUid: ownerUid,
    expectedRevision,
    operationId: save.operationId,
    roomId: "room-main",
    name: save.name,
    placements: save.placements ?? [],
  }
}

test("Given two clients at revision one, when they race and explicitly reapply, then CAS and receipts are exact", async () => {
  // Given
  const { app, firestore, store } = await fixture("mini-home-race")
  const owner = OwnerUidSchema.parse("race-owner")
  const auth = miniHomeAuth({ uid: owner })
  try {
    const revision1 = await saveMiniHome(
      auth,
      request(owner, 0, { operationId: "race-operation-0001", name: "Revision One" }),
      store,
    )
    assert.equal(revision1.kind, "committed")
    assert.equal(revision1.snapshot.revision, 1)
    const clientA = await loadMiniHome(auth, { expectedOwnerUid: owner }, store)
    const clientB = await loadMiniHome(auth, { expectedOwnerUid: owner }, store)
    assert.equal(clientA.kind, "snapshot")
    assert.equal(clientB.kind, "snapshot")
    await assert.rejects(
      () =>
        saveMiniHome(
          auth,
          {
            ...request(owner, 1, {
              operationId: "race-operation-room-change",
              name: "Changed Room",
            }),
            roomId: "room-other",
          },
          store,
        ),
      (error: unknown) =>
        error instanceof MiniHomeStoreError && error.code === "failed-precondition",
    )

    // When
    const raced = await Promise.all([
      saveMiniHome(
        auth,
        request(owner, 1, { operationId: "race-operation-client-a", name: "Client A" }),
        store,
      ),
      saveMiniHome(
        auth,
        request(owner, 1, { operationId: "race-operation-client-b", name: "Client B" }),
        store,
      ),
    ])

    // Then
    const committed = raced.find((result) => result.kind === "committed")
    const conflict = raced.find((result) => result.kind === "conflict")
    assert.ok(committed)
    assert.ok(conflict)
    assert.equal(committed.snapshot.revision, 2)
    assert.deepEqual(conflict.snapshot, committed.snapshot)
    assert.equal((await firestore.collection(`users/${owner}/miniHomeOperations`).get()).size, 2)

    const losingName = committed.snapshot.name === "Client A" ? "Client B" : "Client A"
    const reapplied = await saveMiniHome(
      auth,
      request(owner, 2, { operationId: "race-operation-reapply", name: losingName }),
      store,
    )
    assert.equal(reapplied.kind, "committed")
    assert.equal(reapplied.snapshot.revision, 3)

    const winningOperation =
      committed.snapshot.name === "Client A" ? "race-operation-client-a" : "race-operation-client-b"
    const replay = await saveMiniHome(
      auth,
      request(owner, 1, { operationId: winningOperation, name: committed.snapshot.name }),
      store,
    )
    assert.deepEqual(replay, { kind: "duplicate", snapshot: committed.snapshot })
    await assert.rejects(
      () =>
        saveMiniHome(
          auth,
          request(owner, 1, { operationId: winningOperation, name: "Changed Replay" }),
          store,
        ),
      (error: unknown) =>
        error instanceof MiniHomeStoreError && error.code === "failed-precondition",
    )
  } finally {
    await deleteApp(app)
  }
})

test("Given account-owned entities, when owners save placements, then foreign and unavailable targets fail closed", async () => {
  // Given
  const { app, firestore, store } = await fixture("mini-home-ownership")
  const ownerA = OwnerUidSchema.parse("placement-owner-a")
  const ownerB = OwnerUidSchema.parse("placement-owner-b")
  await Promise.all([
    firestore.doc(`users/${ownerA}/ownedItems/item-lamp`).set({
      ownerUid: ownerA,
      itemId: "item-lamp",
      acquiredAtEpochMillis: NOW,
      applied: false,
      revision: 1,
      catalogSnapshot: null,
    }),
    firestore.doc(`users/${ownerA}/personalPlants/plant-a`).set({ ownerUid: ownerA }),
    firestore.doc(`users/${ownerA}/personalPlants/foreign-plant`).set({ ownerUid: ownerB }),
  ])
  const itemPlacement = {
    placementId: "placement-item",
    itemId: "item-lamp",
    normalizedX: 0.25,
    normalizedY: 0.25,
    zIndex: 0,
  }
  const plantPlacement = {
    placementId: "placement-plant",
    plantId: "plant-a",
    normalizedX: 0.75,
    normalizedY: 0.75,
    zIndex: 1,
  }
  try {
    // When
    const savedA = await saveMiniHome(
      miniHomeAuth({ uid: ownerA }),
      request(ownerA, 0, {
        operationId: "owner-a-operation-0001",
        name: "Owner A",
        placements: [itemPlacement, plantPlacement],
      }),
      store,
    )
    const savedB = await saveMiniHome(
      miniHomeAuth({ uid: ownerB }),
      request(ownerB, 0, { operationId: "owner-b-operation-0001", name: "Owner B" }),
      store,
    )

    // Then
    assert.equal(savedA.kind, "committed")
    assert.equal(savedB.kind, "committed")
    assert.notDeepEqual(savedA.snapshot.ownerUid, savedB.snapshot.ownerUid)
    await assert.rejects(() =>
      saveMiniHome(
        miniHomeAuth({ uid: ownerA }),
        request(ownerA, 1, {
          operationId: "owner-a-unowned-item",
          name: "Unowned",
          placements: [{ ...itemPlacement, itemId: "item-unowned" }],
        }),
        store,
      ),
    )
    await assert.rejects(() =>
      saveMiniHome(
        miniHomeAuth({ uid: ownerA }),
        request(ownerA, 1, {
          operationId: "owner-a-foreign-plant",
          name: "Foreign",
          placements: [{ ...plantPlacement, plantId: "foreign-plant" }],
        }),
        store,
      ),
    )
    const loadedA = await loadMiniHome(
      miniHomeAuth({ uid: ownerA }),
      { expectedOwnerUid: ownerA },
      store,
    )
    assert.equal(loadedA.kind, "snapshot")
    assert.deepEqual(
      loadedA.kind === "snapshot" ? loadedA.snapshotHash : null,
      savedA.snapshot.snapshotHash,
    )
  } finally {
    await deleteApp(app)
  }
})

test("Given a stored snapshot with a mismatched hash, when loaded, then the store reports data loss", async () => {
  // Given
  const { app, firestore, store } = await fixture("mini-home-hash-mismatch")
  const owner = OwnerUidSchema.parse("hash-owner")
  await firestore.doc(`users/${owner}/miniHomes/current`).set({
    schemaVersion: 1,
    ownerUid: owner,
    roomId: "room-main",
    name: "Hash Room",
    placements: [],
    revision: 1,
    updatedAtEpochMillis: NOW,
    snapshotHash: "a".repeat(64),
  })
  try {
    // When / Then
    await assert.rejects(
      () => loadMiniHome(miniHomeAuth({ uid: owner }), { expectedOwnerUid: owner }, store),
      (error: unknown) => error instanceof MiniHomeStoreError && error.code === "data-loss",
    )
  } finally {
    await deleteApp(app)
  }
})

test("Given deny-all rules, when an authenticated client reads canonical MiniHome, then Firestore rejects", async () => {
  // Given
  const authResponse = await fetch(
    `http://127.0.0.1:${authPort}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-key`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        email: "mini-home-rules@example.test",
        password: "deterministic-password",
        returnSecureToken: true,
      }),
    },
  )
  const authPayload = z
    .object({ idToken: z.string().min(1) })
    .passthrough()
    .parse(await authResponse.json())

  // When
  const response = await fetch(
    `http://${firestoreHost}/v1/projects/${PROJECT_ID}/databases/(default)/documents/users/rules-owner/miniHomes/current`,
    { headers: { authorization: `Bearer ${authPayload.idToken}` } },
  )

  // Then
  assert.equal(response.status, 403)
})
