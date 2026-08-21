import assert from "node:assert/strict"
import test from "node:test"
import { deleteApp, initializeApp } from "firebase-admin/app"
import { FirebaseAuthError, getAuth } from "firebase-admin/auth"
import { getFirestore } from "firebase-admin/firestore"
import { getStorage } from "firebase-admin/storage"
import {
  type AuthContext,
  canonicalDeletionScope,
  GRACE_SECONDS,
  OwnerIdSchema,
} from "./deletion-contract.js"
import { runDueAccountDeletions } from "./deletion-execution.js"
import { requestAccountDeletion } from "./deletion-service.js"
import { FirebaseAccountCleaner } from "./firebase-account-cleaner.js"
import { FirestoreDeletionStore } from "./firestore-deletion-store.js"
import { FixedClock, SequenceRequestIds } from "./test-support.js"

const PROJECT_ID = "demo-planterior-ios-deletion"
const BUCKET_NAME = `${PROJECT_ID}.firebasestorage.app`
const NOW = 1_800_000_000

function auth(ownerID: ReturnType<typeof OwnerIdSchema.parse>, at = NOW): AuthContext {
  return { uid: ownerID, authTimeSeconds: at }
}

test("Firestore store persists one request for concurrent duplicate submissions", async () => {
  // Given
  const app = initializeApp({ projectId: PROJECT_ID }, "deletion-store-idempotency")
  const firestore = getFirestore(app)
  const store = new FirestoreDeletionStore(firestore)
  const ownerID = OwnerIdSchema.parse("emulator-idempotent-owner")
  const scope = canonicalDeletionScope(ownerID)

  try {
    // When
    const [first, second] = await Promise.all([
      requestAccountDeletion(
        auth(ownerID),
        { ownerID, scopeHash: scope.scopeHash },
        {
          store,
          clock: new FixedClock(NOW),
          requestIds: new SequenceRequestIds(["concurrent-request-0001"]),
        },
      ),
      requestAccountDeletion(
        auth(ownerID),
        { ownerID, scopeHash: scope.scopeHash },
        {
          store,
          clock: new FixedClock(NOW),
          requestIds: new SequenceRequestIds(["concurrent-request-0002"]),
        },
      ),
    ])

    // Then
    assert.deepEqual(second.workflow, first.workflow)
    assert.equal(first.workflow.scheduledAt, NOW + GRACE_SECONDS)
    assert.equal(
      (
        await firestore
          .collection("accountDeletionRequests")
          .where("workflow.ownerID", "==", ownerID)
          .get()
      ).size,
      1,
    )
  } finally {
    await deleteApp(app)
  }
})

test("scheduled execution removes only account-owned Firestore Storage and Auth resources", async () => {
  // Given
  const app = initializeApp(
    { projectId: PROJECT_ID, storageBucket: BUCKET_NAME },
    "deletion-cleanup-contract",
  )
  const firestore = getFirestore(app)
  const firebaseAuth = getAuth(app)
  const storage = getStorage(app)
  const store = new FirestoreDeletionStore(firestore)
  const ownerID = OwnerIdSchema.parse("emulator-cleanup-owner")
  const foreignID = OwnerIdSchema.parse("emulator-foreign-owner")
  const scope = canonicalDeletionScope(ownerID)
  await Promise.all([
    firebaseAuth.createUser({ uid: ownerID }),
    firestore.doc(`users/${ownerID}`).set({ ownerID }),
    firestore.doc(`users/${ownerID}/personalPlants/plant-a`).set({ ownerID }),
    firestore.doc(`users/${foreignID}`).set({ ownerID: foreignID }),
    firestore.doc("notificationEndpointOwners/owner-endpoint").set({ ownerUid: ownerID }),
    firestore.doc("notificationEndpointOwners/foreign-endpoint").set({ ownerUid: foreignID }),
    firestore.doc("publicShares/owner-share").set({ ownerID }),
    firestore.doc("publicShares/foreign-share").set({ ownerID: foreignID }),
    storage.bucket().file(`identification-originals/${ownerID}/source.jpg`).save("owner"),
    storage.bucket().file(`plant-photos/${ownerID}/plant.jpg`).save("owner"),
    storage.bucket().file(`share-images/${ownerID}/room.png`).save("owner"),
    storage.bucket().file(`plant-photos/${foreignID}/plant.jpg`).save("foreign"),
  ])
  await requestAccountDeletion(
    auth(ownerID, NOW - GRACE_SECONDS),
    { ownerID, scopeHash: scope.scopeHash },
    {
      store,
      clock: new FixedClock(NOW - GRACE_SECONDS),
      requestIds: new SequenceRequestIds(["cleanup-request-0001"]),
    },
  )

  try {
    // When
    const result = await runDueAccountDeletions({
      store,
      cleaner: new FirebaseAccountCleaner(firestore, firebaseAuth, storage),
      clock: new FixedClock(NOW),
    })

    // Then
    assert.deepEqual(result, { claimed: 1, completed: 1, pendingRetry: 0 })
    assert.equal((await firestore.doc(`users/${ownerID}`).get()).exists, false)
    assert.equal((await firestore.doc(`users/${foreignID}`).get()).exists, true)
    assert.equal(
      (await firestore.doc("notificationEndpointOwners/owner-endpoint").get()).exists,
      false,
    )
    assert.equal(
      (await firestore.doc("notificationEndpointOwners/foreign-endpoint").get()).exists,
      true,
    )
    assert.equal((await firestore.doc("publicShares/owner-share").get()).exists, false)
    assert.equal((await firestore.doc("publicShares/foreign-share").get()).exists, true)
    const [ownerFiles] = await storage.bucket().getFiles({ prefix: `plant-photos/${ownerID}/` })
    const [foreignFiles] = await storage.bucket().getFiles({ prefix: `plant-photos/${foreignID}/` })
    assert.equal(ownerFiles.length, 0)
    assert.equal(foreignFiles.length, 1)
    await assert.rejects(
      firebaseAuth.getUser(ownerID),
      (error: unknown) =>
        error instanceof FirebaseAuthError && error.code === "auth/user-not-found",
    )
    assert.equal((await store.load(ownerID))?.status, "COMPLETED")
  } finally {
    await deleteApp(app)
  }
})
