import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { FirebaseAuthError, getAuth } from "firebase-admin/auth";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import {
  ACCOUNT_DELETION_GRACE_MILLIS,
  ACCOUNT_DELETION_SCOPES,
  type AccountDeletionAuth,
  AccountDeletionCleanupError,
} from "./account-deletion-contract.js";
import { runAccountDeletionScan } from "./account-deletion-processor.js";
import {
  cancelAccountDeletion,
  requestAccountDeletion,
  retryAccountDeletion,
} from "./account-deletion-service.js";
import { FirebaseAccountDeletionCleaner } from "./firebase-account-deletion-cleaner.js";
import {
  AccountDeletionPersistenceError,
  FirestoreAccountDeletionStore,
} from "./firestore-account-deletion-store.js";

const PROJECT_ID = "demo-planterior";
const NOW_MILLIS = Date.parse("2026-08-12T00:00:00.000Z");
const DUE_MILLIS = NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS;

function auth(ownerUid: string): AccountDeletionAuth {
  return { uid: ownerUid, authTimeSeconds: NOW_MILLIS / 1_000 };
}

test("Firestore transaction persists one request for concurrent double submit", async () => {
  // Given
  const app = initializeApp({ projectId: PROJECT_ID }, "todo16-double-submit");
  const firestore = getFirestore(app);
  const store = new FirestoreAccountDeletionStore(firestore);
  const input = {
    expectedOwnerUid: "todo16-double-owner",
    confirmed: true,
    idempotencyKey: "todo16-double-submit-0001",
  };

  try {
    // When
    const [first, second] = await Promise.all([
      requestAccountDeletion(auth(input.expectedOwnerUid), input, {
        store,
        nowMillis: () => NOW_MILLIS,
        requestId: () => "todo16-request-a",
      }),
      requestAccountDeletion(auth(input.expectedOwnerUid), input, {
        store,
        nowMillis: () => NOW_MILLIS,
        requestId: () => "todo16-request-b",
      }),
    ]);

    // Then
    assert.deepEqual(second, first);
    const requests = await firestore.collection("accountDeletionRequests").get();
    assert.equal(requests.docs.filter((document) => document.id === input.expectedOwnerUid).length, 1);
    assert.equal(JSON.stringify(requests.docs[0]?.data()).includes(input.idempotencyKey), false);
  } finally {
    await firestore.doc(`accountDeletionRequests/${input.expectedOwnerUid}`).delete();
    await deleteApp(app);
  }
});

test("request and cancel receipts replay after the owner record is replaced", async () => {
  // Given
  const app = initializeApp({ projectId: PROJECT_ID }, "todo16-durable-receipts");
  const firestore = getFirestore(app);
  const store = new FirestoreAccountDeletionStore(firestore);
  const ownerUid = "todo16-receipt-owner";
  const firstInput = {
    expectedOwnerUid: ownerUid,
    confirmed: true,
    idempotencyKey: "todo16-receipt-request-0001",
  };

  try {
    const first = await requestAccountDeletion(auth(ownerUid), firstInput, {
      store,
      nowMillis: () => NOW_MILLIS,
      requestId: () => "todo16-receipt-request-a",
    });
    const cancelled = await cancelAccountDeletion(
      auth(ownerUid),
      { expectedOwnerUid: ownerUid, requestId: first.requestId },
      { store, nowMillis: () => NOW_MILLIS + 1 },
    );
    const replacement = await requestAccountDeletion(
      { uid: ownerUid, authTimeSeconds: NOW_MILLIS / 1_000 },
      { ...firstInput, idempotencyKey: "todo16-receipt-request-0002" },
      {
        store,
        nowMillis: () => NOW_MILLIS + 2,
        requestId: () => "todo16-receipt-request-b",
      },
    );

    // When
    const requestReplay = await requestAccountDeletion(auth(ownerUid), firstInput, {
      store,
      nowMillis: () => NOW_MILLIS + 3,
      requestId: () => "todo16-receipt-request-c",
    });
    const cancelReplay = await cancelAccountDeletion(
      auth(ownerUid),
      { expectedOwnerUid: ownerUid, requestId: first.requestId },
      { store, nowMillis: () => NOW_MILLIS + 4 },
    );

    // Then
    assert.deepEqual(requestReplay, first);
    assert.deepEqual(cancelReplay, cancelled);
    assert.equal((await store.load(ownerUid))?.requestId, replacement.requestId);
    const receipts = await firestore
      .collection(`accountDeletionReceipts/${ownerUid}/commands`)
      .get();
    assert.equal(receipts.size, 3);
    assert.equal(JSON.stringify(receipts.docs.map((document) => document.data())).includes(
      firstInput.idempotencyKey,
    ), false);
  } finally {
    await firestore.recursiveDelete(firestore.doc(`accountDeletionReceipts/${ownerUid}`));
    await firestore.doc(`accountDeletionRequests/${ownerUid}`).delete();
    await deleteApp(app);
  }
});

test("retry receipt replays after a later retry replaces its owner record", async () => {
  // Given
  const app = initializeApp({ projectId: PROJECT_ID }, "todo16-durable-retry-receipt");
  const firestore = getFirestore(app);
  const store = new FirestoreAccountDeletionStore(firestore);
  const ownerUid = "todo16-retry-receipt-owner";

  try {
    await requestAccountDeletion(
      auth(ownerUid),
      {
        expectedOwnerUid: ownerUid,
        confirmed: true,
        idempotencyKey: "todo16-original-request-0001",
      },
      {
        store,
        nowMillis: () => NOW_MILLIS,
        requestId: () => "todo16-original-request",
      },
    );
    const originalClaim = (await store.claimDue({
      nowMillis: DUE_MILLIS,
      leaseExpiresAtMillis: DUE_MILLIS + 60_000,
      limit: 1,
    }))[0];
    assert.ok(originalClaim !== undefined);
    await store.finish({
      ownerUid,
      requestId: originalClaim.requestId,
      claimGeneration: originalClaim.claimGeneration,
      completedScopes: [],
      failedScopes: ACCOUNT_DELETION_SCOPES,
      nowMillis: DUE_MILLIS + 1,
    });
    const firstRetryAt = DUE_MILLIS + 2;
    const firstRetryInput = {
      expectedOwnerUid: ownerUid,
      confirmed: true,
      idempotencyKey: "todo16-retry-receipt-0001",
    };
    const firstRetry = await retryAccountDeletion(
      { uid: ownerUid, authTimeSeconds: Math.floor(firstRetryAt / 1_000) },
      firstRetryInput,
      {
        store,
        nowMillis: () => firstRetryAt,
        requestId: () => "todo16-retry-request-a",
      },
    );
    const firstRetryClaim = (await store.claimDue({
      nowMillis: firstRetry.scheduledForMillis,
      leaseExpiresAtMillis: firstRetry.scheduledForMillis + 60_000,
      limit: 1,
    }))[0];
    assert.ok(firstRetryClaim !== undefined);
    await store.finish({
      ownerUid,
      requestId: firstRetryClaim.requestId,
      claimGeneration: firstRetryClaim.claimGeneration,
      completedScopes: [],
      failedScopes: ACCOUNT_DELETION_SCOPES,
      nowMillis: firstRetry.scheduledForMillis + 1,
    });
    const replacementAt = firstRetry.scheduledForMillis + 2;
    const replacement = await retryAccountDeletion(
      { uid: ownerUid, authTimeSeconds: Math.floor(replacementAt / 1_000) },
      {
        expectedOwnerUid: ownerUid,
        confirmed: true,
        idempotencyKey: "todo16-retry-receipt-0002",
      },
      {
        store,
        nowMillis: () => replacementAt,
        requestId: () => "todo16-retry-request-b",
      },
    );

    // When
    const replay = await retryAccountDeletion(
      { uid: ownerUid, authTimeSeconds: Math.floor(replacementAt / 1_000) },
      firstRetryInput,
      {
        store,
        nowMillis: () => replacementAt + 1,
        requestId: () => "todo16-retry-request-c",
      },
    );

    // Then
    assert.deepEqual(replay, firstRetry);
    assert.equal((await store.load(ownerUid))?.requestId, replacement.requestId);
    const retryReceipts = await firestore
      .collection(`accountDeletionReceipts/${ownerUid}/commands`)
      .where("commandKind", "==", "RETRY")
      .get();
    assert.equal(retryReceipts.size, 2);
  } finally {
    await firestore.recursiveDelete(firestore.doc(`accountDeletionReceipts/${ownerUid}`));
    await firestore.doc(`accountDeletionRequests/${ownerUid}`).delete();
    await deleteApp(app);
  }
});

test("cancel and due claim race has one transactionally linearized winner", async () => {
  // Given
  const app = initializeApp({ projectId: PROJECT_ID }, "todo16-cancel-claim-race");
  const store = new FirestoreAccountDeletionStore(getFirestore(app));
  const ownerUid = "todo16-race-owner";
  await requestAccountDeletion(auth(ownerUid), {
    expectedOwnerUid: ownerUid,
    confirmed: true,
    idempotencyKey: "todo16-race-request-0001",
  }, {
    store,
    nowMillis: () => NOW_MILLIS,
    requestId: () => "todo16-race-request",
  });

  try {
    // When
    const [cancelled, claimed] = await Promise.all([
      store.cancel({
        ownerUid,
        requestId: "todo16-race-request",
        nowMillis: DUE_MILLIS - 1,
      }),
      store.claimDue({
        nowMillis: DUE_MILLIS,
        leaseExpiresAtMillis: DUE_MILLIS + 10 * 60_000,
        limit: 50,
      }),
    ]);

    // Then
    assert.equal((cancelled === null ? 0 : 1) + claimed.length, 1);
    assert.ok(["CANCELLED", "PROCESSING"].includes((await store.load(ownerUid))?.status ?? ""));
  } finally {
    await getFirestore(app).doc(`accountDeletionRequests/${ownerUid}`).delete();
    await deleteApp(app);
  }
});

test("stored deletion records reject status-specific impossible states", async () => {
  // Given
  const app = initializeApp({ projectId: PROJECT_ID }, "todo16-status-invariants");
  const firestore = getFirestore(app);
  const store = new FirestoreAccountDeletionStore(firestore);
  const ownerUid = "todo16-malformed-owner";
  const reference = firestore.doc(`accountDeletionRequests/${ownerUid}`);
  const base = {
    schemaVersion: 1,
    ownerUid,
    requestId: "todo16-malformed-request",
    idempotencyKeyHash: "a".repeat(64),
    requestedAt: new Date(NOW_MILLIS),
    scheduledFor: new Date(DUE_MILLIS),
    nextAttemptAt: null,
    leaseExpiresAt: null,
    completedAt: null,
    completedScopes: [],
    failedScopes: [],
    claimGeneration: 0,
    updatedAt: new Date(DUE_MILLIS),
  };
  const impossible = [
    {
      status: "RECEIVED",
      nextAttemptAt: new Date(DUE_MILLIS),
      completedScopes: ["PUBLIC_SHARES"],
    },
    { status: "PROCESSING" },
    { status: "COMPLETED", completedScopes: ACCOUNT_DELETION_SCOPES },
    { status: "FAILED", completedScopes: ["PUBLIC_SHARES"], failedScopes: ["PLANT_PHOTOS"] },
    {
      status: "PARTIALLY_FAILED",
      completedScopes: ["PUBLIC_SHARES"],
      failedScopes: ["PUBLIC_SHARES"],
    },
  ];

  try {
    for (const value of impossible) {
      await reference.set({ ...base, ...value });

      // When / Then
      await assert.rejects(
        store.load(ownerUid),
        AccountDeletionPersistenceError,
      );
    }
  } finally {
    await reference.delete();
    await deleteApp(app);
  }
});

test("endpoint cleanup waits for an exact active send lease boundary", async () => {
  // Given
  const app = initializeApp(
    { projectId: PROJECT_ID, storageBucket: `${PROJECT_ID}.firebasestorage.app` },
    "todo16-active-send-lease",
  );
  const firestore = getFirestore(app);
  const ownerUid = "todo16-send-lease-owner";
  const endpoint = firestore.doc("notificationEndpointOwners/todo16-send-lease");
  await endpoint.set({
    ownerUid,
    activeSendLeases: {
      "send-lease-0001": Timestamp.fromMillis(NOW_MILLIS + 1),
    },
  });
  const cleaner = new FirebaseAccountDeletionCleaner(
    firestore,
    getStorage(app),
    getAuth(app),
    () => NOW_MILLIS,
  );

  try {
    // When / Then
    await assert.rejects(
      cleaner.clean(ownerUid, "NOTIFICATION_ENDPOINT_OWNERS"),
      AccountDeletionCleanupError,
    );
    assert.equal((await endpoint.get()).exists, true);

    await endpoint.update({
      activeSendLeases: {
        "send-lease-0001": Timestamp.fromMillis(NOW_MILLIS),
      },
    });
    await cleaner.clean(ownerUid, "NOTIFICATION_ENDPOINT_OWNERS");
    assert.equal((await endpoint.get()).exists, false);
  } finally {
    await endpoint.delete();
    await deleteApp(app);
  }
});

test("emulator cleanup removes the exact owner scope and retains every foreign resource", async () => {
  // Given
  const app = initializeApp(
    { projectId: PROJECT_ID, storageBucket: `${PROJECT_ID}.firebasestorage.app` },
    "todo16-cleanup-scope",
  );
  const firestore = getFirestore(app);
  const firebaseAuth = getAuth(app);
  const storage = getStorage(app);
  const ownerUid = "todo16-cleanup-owner";
  const foreignUid = "todo16-cleanup-foreign";
  const ownerHash = "a".repeat(64);
  const foreignHash = "b".repeat(64);
  await Promise.all([
    firebaseAuth.createUser({ uid: ownerUid }),
    firebaseAuth.createUser({ uid: foreignUid }),
    firestore.doc(`users/${ownerUid}`).set({ ownerUid }),
    firestore.doc(`users/${ownerUid}/personalPlants/plant-a`).set({ ownerUid }),
    firestore.doc(`users/${ownerUid}/shareLinks/share-a`).set({ ownerUid, tokenHash: ownerHash }),
    firestore.doc(`publicShares/${ownerHash}`).set({ tokenHash: ownerHash }),
    firestore.doc(`notificationEndpointOwners/owner-endpoint`).set({ ownerUid }),
    firestore.doc(`users/${foreignUid}`).set({ ownerUid: foreignUid }),
    firestore.doc(`users/${foreignUid}/shareLinks/share-b`).set({ ownerUid: foreignUid, tokenHash: foreignHash }),
    firestore.doc(`publicShares/${foreignHash}`).set({ tokenHash: foreignHash }),
    firestore.doc(`notificationEndpointOwners/foreign-endpoint`).set({ ownerUid: foreignUid }),
    ...["identification-originals", "plant-photos", "share-images"].flatMap((prefix) => [
      storage.bucket().file(`${prefix}/${ownerUid}/fixture.bin`).save("owner"),
      storage.bucket().file(`${prefix}/${foreignUid}/fixture.bin`).save("foreign"),
    ]),
  ]);
  const store = new FirestoreAccountDeletionStore(firestore);
  await requestAccountDeletion(auth(ownerUid), {
    expectedOwnerUid: ownerUid,
    confirmed: true,
    idempotencyKey: "todo16-cleanup-request-0001",
  }, {
    store,
    nowMillis: () => NOW_MILLIS,
    requestId: () => "todo16-cleanup-request",
  });

  try {
    // When
    const result = await runAccountDeletionScan({
      store,
      cleaner: new FirebaseAccountDeletionCleaner(firestore, storage, firebaseAuth),
      nowMillis: () => DUE_MILLIS,
    });

    // Then
    assert.deepEqual(result, { claimed: 1, completed: 1, failed: 0, partiallyFailed: 0 });
    assert.equal((await firestore.doc(`users/${ownerUid}`).get()).exists, false);
    assert.equal((await firestore.doc(`publicShares/${ownerHash}`).get()).exists, false);
    assert.equal((await firestore.doc("notificationEndpointOwners/owner-endpoint").get()).exists, false);
    assert.equal((await firestore.doc(`users/${foreignUid}`).get()).exists, true);
    assert.equal((await firestore.doc(`publicShares/${foreignHash}`).get()).exists, true);
    assert.equal((await firestore.doc("notificationEndpointOwners/foreign-endpoint").get()).exists, true);
    for (const prefix of ["identification-originals", "plant-photos", "share-images"]) {
      assert.equal((await storage.bucket().getFiles({ prefix: `${prefix}/${ownerUid}/` }))[0].length, 0);
      assert.equal((await storage.bucket().getFiles({ prefix: `${prefix}/${foreignUid}/` }))[0].length, 1);
    }
    await assert.rejects(
      firebaseAuth.getUser(ownerUid),
      (error: unknown) =>
        error instanceof FirebaseAuthError && error.code === "auth/user-not-found",
    );
    assert.equal((await firebaseAuth.getUser(foreignUid)).uid, foreignUid);
    assert.equal((await store.load(ownerUid))?.status, "COMPLETED");
    assert.deepEqual((await store.load(ownerUid))?.completedScopes, ACCOUNT_DELETION_SCOPES);
    await new FirebaseAccountDeletionCleaner(firestore, storage, firebaseAuth).clean(
      ownerUid,
      "AUTH_ACCOUNT",
    );
  } finally {
    await deleteApp(app);
  }
});
