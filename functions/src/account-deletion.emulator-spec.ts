import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { FirebaseAuthError, getAuth } from "firebase-admin/auth";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { FirestoreAnalyticsStore } from "./firestore-analytics-store.js";
import {
  createFirestoreServerAnalyticsRecorder,
  createSafeServerAnalyticsRecorder,
} from "./server-analytics.js";
import {
  ACCOUNT_DELETION_GRACE_MILLIS,
  ACCOUNT_DELETION_SCOPES,
  ACCOUNT_DELETION_TERMINAL_RETENTION_MILLIS,
  type AccountDeletionAuth,
  AccountDeletionCleanupError,
  AccountDeletionError,
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
  cleanupExpiredAccountDeletionTombstones,
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

test("accepted RECEIVED freezes request analytics before deletion-owned revoke", async () => {
  const app = initializeApp({ projectId: PROJECT_ID }, "todo17-deletion-analytics-ordering");
  const firestore = getFirestore(app);
  const ownerUid = "todo17-analytics-order-owner";
  const analyticsStore = new FirestoreAnalyticsStore(
    firestore,
    () => Timestamp.fromMillis(NOW_MILLIS),
  );
  const store = new FirestoreAccountDeletionStore(firestore);
  try {
    await firestore.doc(`users/${ownerUid}`).set({ ownerUid });
    await analyticsStore.setConsent({
      ownerUid,
      granted: true,
      commandGeneration: 1,
      operationId: "deletion-analytics-grant-0001",
    });

    const response = await requestAccountDeletion(
      auth(ownerUid),
      {
        expectedOwnerUid: ownerUid,
        confirmed: true,
        idempotencyKey: "deletion-analytics-request-0001",
      },
      {
        store,
        nowMillis: () => NOW_MILLIS,
        requestId: () => "deletion-analytics-request-id",
        analytics: createFirestoreServerAnalyticsRecorder(firestore),
        analyticsDeletion: analyticsStore,
      },
    );

    assert.equal(response.status, "RECEIVED");
    assert.equal(
      (await firestore.collection(`users/${ownerUid}/analyticsEvents`).get()).size,
      0,
    );
    const stored = await store.load(ownerUid);
    assert.equal(stored?.analyticsResultEligible, true);
    assert.equal(stored?.analyticsRequestOutcome, "LOCKED");
  } finally {
    await firestore.recursiveDelete(firestore.doc(`users/${ownerUid}`));
    await firestore.recursiveDelete(firestore.doc(`accountDeletionReceipts/${ownerUid}`));
    await firestore.doc(`accountDeletionRequests/${ownerUid}`).delete();
    await deleteApp(app);
  }
});

test("RECEIVED lock wins over a transient raw write and still revokes", async () => {
  const app = initializeApp({ projectId: PROJECT_ID }, "todo17-deletion-analytics-transient");
  const firestore = getFirestore(app);
  const ownerUid = "todo17-analytics-transient-owner";
  const analyticsStore = new FirestoreAnalyticsStore(
    firestore,
    () => Timestamp.fromMillis(NOW_MILLIS),
    { afterEventConsentRead: async () => { throw new Error("injected raw write failure"); } },
  );
  const store = new FirestoreAccountDeletionStore(firestore);
  try {
    await firestore.doc(`users/${ownerUid}`).set({ ownerUid });
    await analyticsStore.setConsent({
      ownerUid,
      granted: true,
      commandGeneration: 1,
      operationId: "deletion-transient-grant-0001",
    });
    const analytics = createSafeServerAnalyticsRecorder({
      getConsent: (owner) => analyticsStore.getConsent(owner),
      record: (command) => analyticsStore.recordServerEvent(command),
    });

    const response = await requestAccountDeletion(
      auth(ownerUid),
      {
        expectedOwnerUid: ownerUid,
        confirmed: true,
        idempotencyKey: "deletion-transient-request-0001",
      },
      {
        store,
        nowMillis: () => NOW_MILLIS,
        requestId: () => "deletion-transient-request-id",
        analytics,
        analyticsDeletion: analyticsStore,
      },
    );

    assert.equal(response.status, "RECEIVED");
    assert.equal((await store.load(ownerUid))?.analyticsRequestOutcome, "LOCKED");
    assert.equal((await analyticsStore.getConsent(ownerUid)).granted, false);
    assert.equal(
      (await firestore.collection(`users/${ownerUid}/analyticsEvents`).get()).size,
      0,
    );
  } finally {
    await firestore.recursiveDelete(firestore.doc(`users/${ownerUid}`));
    await firestore.recursiveDelete(firestore.doc(`accountDeletionReceipts/${ownerUid}`));
    await firestore.doc(`accountDeletionRequests/${ownerUid}`).delete();
    await deleteApp(app);
  }
});

test("finish transaction increments ownerless daily results exactly once per transition", async () => {
  const app = initializeApp({ projectId: PROJECT_ID }, "todo17-deletion-ownerless-results");
  const firestore = getFirestore(app);
  const ownerUid = "todo17-result-owner";
  const store = new FirestoreAccountDeletionStore(firestore);
  const analyticsStore = new FirestoreAnalyticsStore(
    firestore,
    () => Timestamp.fromMillis(NOW_MILLIS),
  );
  try {
    await firestore.doc(`users/${ownerUid}`).set({ ownerUid });
    await analyticsStore.setConsent({
      ownerUid,
      granted: true,
      commandGeneration: 1,
      operationId: "deletion-result-grant-0001",
    });
    await requestAccountDeletion(
      auth(ownerUid),
      {
        expectedOwnerUid: ownerUid,
        confirmed: true,
        idempotencyKey: "deletion-result-request-0001",
      },
      {
        store,
        nowMillis: () => NOW_MILLIS,
        requestId: () => "deletion-result-request-id",
        analytics: createFirestoreServerAnalyticsRecorder(firestore),
        analyticsDeletion: analyticsStore,
      },
    );
    const firstClaim = (await store.claimDue({
      nowMillis: DUE_MILLIS,
      leaseExpiresAtMillis: DUE_MILLIS + 60_000,
      limit: 1,
    }))[0]!;
    const firstFailure = {
      ownerUid,
      requestId: firstClaim.requestId,
      claimGeneration: firstClaim.claimGeneration,
      completedScopes: [] as const,
      failedScopes: ACCOUNT_DELETION_SCOPES,
      nowMillis: DUE_MILLIS + 1,
    };
    const duplicate = await Promise.allSettled([
      store.finish(firstFailure),
      store.finish(firstFailure),
    ]);
    assert.equal(duplicate.filter((result) => result.status === "fulfilled").length, 1);

    await store.retry({
      ownerUid,
      requestId: "unused-partial-result-retry",
      idempotencyKeyHash: "c".repeat(64),
      nowMillis: DUE_MILLIS + 2,
      scheduledForMillis: DUE_MILLIS + 2,
    });
    const secondClaim = (await store.claimDue({
      nowMillis: DUE_MILLIS + 2,
      leaseExpiresAtMillis: DUE_MILLIS + 60_002,
      limit: 1,
    }))[0]!;
    await store.finish({
      ownerUid,
      requestId: secondClaim.requestId,
      claimGeneration: secondClaim.claimGeneration,
      completedScopes: ["PUBLIC_SHARES"],
      failedScopes: ACCOUNT_DELETION_SCOPES.filter((scope) => scope !== "PUBLIC_SHARES"),
      nowMillis: DUE_MILLIS + 3,
    });
    await store.retry({
      ownerUid,
      requestId: "unused-completed-result-retry",
      idempotencyKeyHash: "d".repeat(64),
      nowMillis: DUE_MILLIS + 4,
      scheduledForMillis: DUE_MILLIS + 4,
    });
    const completionClaim = (await store.claimDue({
      nowMillis: DUE_MILLIS + 4,
      leaseExpiresAtMillis: DUE_MILLIS + 60_004,
      limit: 1,
    }))[0]!;
    await store.finish({
      ownerUid,
      requestId: completionClaim.requestId,
      claimGeneration: completionClaim.claimGeneration,
      completedScopes: ACCOUNT_DELETION_SCOPES,
      failedScopes: [],
      nowMillis: DUE_MILLIS + 5,
    });

    const consentOffOwner = "todo17-result-consent-off";
    await firestore.doc(`users/${consentOffOwner}`).set({ ownerUid: consentOffOwner });
    await requestAccountDeletion(
      auth(consentOffOwner),
      {
        expectedOwnerUid: consentOffOwner,
        confirmed: true,
        idempotencyKey: "off-off-off-off",
      },
      {
        store,
        nowMillis: () => NOW_MILLIS,
        requestId: () => "deletion-result-consent-off-id",
        analyticsDeletion: analyticsStore,
      },
    );
    const consentOffClaim = (await store.claimDue({
      nowMillis: DUE_MILLIS,
      leaseExpiresAtMillis: DUE_MILLIS + 60_000,
      limit: 1,
    }))[0]!;
    await store.finish({
      ownerUid: consentOffOwner,
      requestId: consentOffClaim.requestId,
      claimGeneration: consentOffClaim.claimGeneration,
      completedScopes: [],
      failedScopes: ACCOUNT_DELETION_SCOPES,
      nowMillis: DUE_MILLIS + 6,
    });

    const aggregates = await firestore.collection("analyticsDailyAggregates").get();
    assert.equal(aggregates.size, 1);
    const aggregate = aggregates.docs[0]!;
    assert.deepEqual(Object.keys(aggregate.data()).sort(), [
      "counts",
      "date",
      "expiresAt",
      "schemaVersion",
      "updatedAt",
    ]);
    assert.deepEqual(aggregate.get("counts"), {
      ACCOUNT_DELETION_COMPLETED: 1,
      ACCOUNT_DELETION_FAILED: 2,
    });
    assert.equal(aggregate.get("date"), "2026-08-19");
    assert.equal(
      aggregate.get("expiresAt").toDate().toISOString(),
      "2026-09-23T00:00:00.000Z",
    );
    const serialized = JSON.stringify(aggregate.data());
    for (const forbidden of [ownerUid, firstClaim.requestId, "PUBLIC_SHARES", "c".repeat(64)]) {
      assert.equal(serialized.includes(forbidden), false);
    }
    assert.equal(await store.load(ownerUid), null);
  } finally {
    await firestore.recursiveDelete(firestore.doc(`users/${ownerUid}`));
    await firestore.recursiveDelete(firestore.doc("users/todo17-result-consent-off"));
    await firestore.recursiveDelete(firestore.doc(`accountDeletionReceipts/${ownerUid}`));
    await firestore.recursiveDelete(
      firestore.doc("accountDeletionReceipts/todo17-result-consent-off"),
    );
    await firestore.doc(`accountDeletionRequests/${ownerUid}`).delete();
    await firestore.doc("accountDeletionRequests/todo17-result-consent-off").delete();
    await firestore.recursiveDelete(firestore.collection("analyticsDailyAggregates"));
    await deleteApp(app);
  }
});

test("cancel minimizes global records and fences replay with bounded owner tombstones", async () => {
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
    const cancelReplay = await cancelAccountDeletion(
      auth(ownerUid),
      { expectedOwnerUid: ownerUid, requestId: first.requestId },
      { store, nowMillis: () => NOW_MILLIS + 4 },
    );

    // Then
    await assert.rejects(
      () => requestAccountDeletion(auth(ownerUid), firstInput, {
        store,
        nowMillis: () => NOW_MILLIS + 3,
        requestId: () => "todo16-receipt-request-c",
      }),
      (error: unknown) =>
        error instanceof AccountDeletionError && error.code === "failed-precondition",
    );
    assert.equal(cancelReplay.status, cancelled.status);
    assert.equal(cancelReplay.requestId, cancelled.requestId);
    assert.equal((await store.load(ownerUid))?.requestId, replacement.requestId);
    const receipts = await firestore
      .collection(`accountDeletionReceipts/${ownerUid}/commands`)
      .get();
    assert.equal(receipts.size, 1);
    const tombstones = await firestore
      .collection(`users/${ownerUid}/accountDeletionTombstones`)
      .get();
    assert.equal(tombstones.size, 2);
    for (const tombstone of tombstones.docs) {
      assert.deepEqual(Object.keys(tombstone.data()).sort(), [
        "commandKeyHash",
        "commandKind",
        "expiresAt",
        "schemaVersion",
        "terminalAt",
        "terminalStatus",
      ]);
      assert.equal(tombstone.get("terminalStatus"), "CANCELLED");
      assert.equal(
        tombstone.get("expiresAt").toMillis() - tombstone.get("terminalAt").toMillis(),
        ACCOUNT_DELETION_TERMINAL_RETENTION_MILLIS,
      );
    }
    assert.deepEqual(
      await cleanupExpiredAccountDeletionTombstones(
        firestore,
        Timestamp.fromMillis(NOW_MILLIS + ACCOUNT_DELETION_TERMINAL_RETENTION_MILLIS),
      ),
      { scanned: 0, deleted: 0, failures: [] },
    );
    assert.deepEqual(
      await cleanupExpiredAccountDeletionTombstones(
        firestore,
        Timestamp.fromMillis(NOW_MILLIS + 1 + ACCOUNT_DELETION_TERMINAL_RETENTION_MILLIS),
      ),
      { scanned: 2, deleted: 2, failures: [] },
    );
  } finally {
    await firestore.recursiveDelete(firestore.doc(`accountDeletionReceipts/${ownerUid}`));
    await firestore.recursiveDelete(firestore.doc(`users/${ownerUid}`));
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
    const stored = await store.load(ownerUid);
    assert.equal(cancelled === null ? stored?.status : stored, cancelled === null ? "PROCESSING" : null);
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
    analyticsResultEligible: false,
    analyticsRequestOutcome: "CONSENT_OFF",
    analyticsRecordedResultKeys: [],
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
  const claim = firestore.doc("notificationDeliveryClaims/todo16-send-lease");
  await endpoint.set({
    ownerUid,
    activeSendLeases: {
      "send-lease-0001": Timestamp.fromMillis(NOW_MILLIS + 1),
    },
  });
  await claim.set({
    ownerUid,
    state: "AUTHORIZED_PRE_SEND",
    expiresAt: Timestamp.fromMillis(NOW_MILLIS + 1),
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
    assert.equal((await claim.get()).exists, true);

    await endpoint.update({
      activeSendLeases: {
        "send-lease-0001": Timestamp.fromMillis(NOW_MILLIS),
      },
    });
    await claim.update({ expiresAt: Timestamp.fromMillis(NOW_MILLIS) });
    await cleaner.clean(ownerUid, "NOTIFICATION_ENDPOINT_OWNERS");
    assert.equal((await endpoint.get()).exists, false);
    assert.equal((await claim.get()).exists, false);
  } finally {
    await endpoint.delete();
    await claim.delete();
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
  const ownerReservationId = "todo17_owner_media_0001";
  const foreignReservationId = "todo17_foreign_media_0001";
  await Promise.all([
    firebaseAuth.createUser({ uid: ownerUid }),
    firebaseAuth.createUser({ uid: foreignUid }),
    firestore.doc(`users/${ownerUid}`).set({ ownerUid }),
    firestore.doc(`users/${ownerUid}/personalPlants/plant-a`).set({ ownerUid }),
    firestore.doc(`users/${ownerUid}/shareLinks/share-a`).set({ ownerUid, tokenHash: ownerHash }),
    firestore.doc(`publicShares/${ownerHash}`).set({ tokenHash: ownerHash }),
    firestore.doc(`notificationEndpointOwners/owner-endpoint`).set({ ownerUid }),
    firestore.doc("notificationDeliveryClaims/owner-failed-claim").set({
      ownerUid,
      state: "FAILED",
      terminalAt: Timestamp.fromMillis(NOW_MILLIS),
      expiresAt: Timestamp.fromMillis(NOW_MILLIS + 35 * 24 * 60 * 60 * 1_000),
    }),
    firestore.doc("notificationDeliveryDiagnostics/owner-diagnostic").set({
      ownerUid,
      endpointResults: [{ endpointIds: ["owner-endpoint"], tokenHash: ownerHash }],
    }),
    firestore.doc(`users/${foreignUid}`).set({ ownerUid: foreignUid }),
    firestore.doc(`users/${foreignUid}/shareLinks/share-b`).set({ ownerUid: foreignUid, tokenHash: foreignHash }),
    firestore.doc(`publicShares/${foreignHash}`).set({ tokenHash: foreignHash }),
    firestore.doc(`notificationEndpointOwners/foreign-endpoint`).set({ ownerUid: foreignUid }),
    firestore.doc("notificationDeliveryClaims/foreign-failed-claim").set({
      ownerUid: foreignUid,
      state: "FAILED",
      terminalAt: Timestamp.fromMillis(NOW_MILLIS),
      expiresAt: Timestamp.fromMillis(NOW_MILLIS + 35 * 24 * 60 * 60 * 1_000),
    }),
    firestore.doc("notificationDeliveryDiagnostics/foreign-diagnostic").set({
      ownerUid: foreignUid,
    }),
    storage.bucket().file(`private-media-v2/${ownerReservationId}`).save("owner-private", {
      metadata: {
        contentType: "image/jpeg",
        metadata: { ownerUid, reservationId: ownerReservationId },
      },
    }),
    storage.bucket().file(`private-media-v2/${foreignReservationId}`).save("foreign-private", {
      metadata: {
        contentType: "image/jpeg",
        metadata: { ownerUid: foreignUid, reservationId: foreignReservationId },
      },
    }),
    ...["identification-originals", "plant-photos", "share-images"].flatMap((prefix) => [
      storage.bucket().file(`${prefix}/${ownerUid}/fixture.bin`).save("owner"),
      storage.bucket().file(`${prefix}/${foreignUid}/fixture.bin`).save("foreign"),
    ]),
  ]);
  const [ownerObjectMetadata] = await storage.bucket()
    .file(`private-media-v2/${ownerReservationId}`).getMetadata();
  const [foreignObjectMetadata] = await storage.bucket()
    .file(`private-media-v2/${foreignReservationId}`).getMetadata();
  const reservation = (reservationId: string, reservationOwner: string, generation: string) => ({
    schemaVersion: 1,
    reservationId,
    ownerUid: reservationOwner,
    mediaKind: "PLANT_PHOTO",
    contentType: "image/jpeg",
    byteSize: reservationOwner === ownerUid ? 13 : 15,
    objectPath: `private-media-v2/${reservationId}`,
    identificationRequestId: null,
    idempotencyKeyHash: reservationOwner === ownerUid ? ownerHash : foreignHash,
    requestHash: reservationOwner === ownerUid ? foreignHash : ownerHash,
    state: "COMMITTED",
    objectGeneration: generation,
    sealedGeneration: null,
    createdAt: Timestamp.fromMillis(NOW_MILLIS),
    expiresAt: Timestamp.fromMillis(NOW_MILLIS + 10 * 60_000),
    committedAt: Timestamp.fromMillis(NOW_MILLIS + 1),
    sealedAt: null,
  });
  await Promise.all([
    firestore.doc(`privateMediaReservations/${ownerReservationId}`).set(
      reservation(ownerReservationId, ownerUid, String(ownerObjectMetadata.generation)),
    ),
    firestore.doc(`privateMediaReservations/${foreignReservationId}`).set(
      reservation(foreignReservationId, foreignUid, String(foreignObjectMetadata.generation)),
    ),
    firestore.doc("privateMediaReservationReceipts/owner-receipt").set({
      ownerUid,
      reservationId: ownerReservationId,
    }),
    firestore.doc("privateMediaReservationReceipts/foreign-receipt").set({
      ownerUid: foreignUid,
      reservationId: foreignReservationId,
    }),
  ]);
  const store = new FirestoreAccountDeletionStore(firestore);
  const analyticsStore = new FirestoreAnalyticsStore(
    firestore,
    () => Timestamp.fromMillis(NOW_MILLIS),
  );
  await analyticsStore.setConsent({
    ownerUid,
    granted: true,
    commandGeneration: 1,
    operationId: "todo17-cleanup-analytics-grant-0001",
  });
  await requestAccountDeletion(auth(ownerUid), {
    expectedOwnerUid: ownerUid,
    confirmed: true,
    idempotencyKey: "todo16-cleanup-request-0001",
  }, {
    store,
    nowMillis: () => NOW_MILLIS,
    requestId: () => "todo16-cleanup-request",
    analytics: createFirestoreServerAnalyticsRecorder(firestore),
    analyticsDeletion: analyticsStore,
  });
  await Promise.all([
    firestore.doc(`users/${ownerUid}/analyticsEvents/stranded-event-a`).set({ ownerUid }),
    firestore.doc(`users/${ownerUid}/analyticsConsentOperations/stranded-operation-a`).set({
      ownerUid,
    }),
  ]);

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
    for (const collection of [
      "notificationEndpointOwners",
      "notificationDeliveryClaims",
      "notificationDeliveryDiagnostics",
      "privateMediaReservations",
      "privateMediaReservationReceipts",
      "accountDeletionRequests",
    ]) {
      assert.equal(
        (await firestore.collection(collection).where("ownerUid", "==", ownerUid).get()).size,
        0,
        collection,
      );
    }
    assert.equal((await firestore.doc(`users/${foreignUid}`).get()).exists, true);
    assert.equal((await firestore.doc(`publicShares/${foreignHash}`).get()).exists, true);
    assert.equal((await firestore.doc("notificationEndpointOwners/foreign-endpoint").get()).exists, true);
    assert.equal((await firestore.doc("notificationDeliveryClaims/foreign-failed-claim").get()).exists, true);
    assert.equal((await firestore.doc("notificationDeliveryDiagnostics/foreign-diagnostic").get()).exists, true);
    assert.equal((await firestore.doc(`privateMediaReservations/${foreignReservationId}`).get()).exists, true);
    assert.equal((await firestore.doc("privateMediaReservationReceipts/foreign-receipt").get()).exists, true);
    const [ownerSealMetadata] = await storage.bucket()
      .file(`private-media-v2/${ownerReservationId}`).getMetadata();
    assert.equal(Number(ownerSealMetadata.size), 0);
    assert.equal(ownerSealMetadata.contentType, "application/x.planterior-private-media-seal");
    assert.deepEqual(ownerSealMetadata.metadata, { privateMediaSeal: "true" });
    assert.equal(
      Number((await storage.bucket().file(`private-media-v2/${foreignReservationId}`).getMetadata())[0].size),
      15,
    );
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
    assert.equal(await store.load(ownerUid), null);
    assert.equal(
      (await firestore.collection(`accountDeletionReceipts/${ownerUid}/commands`).get()).size,
      0,
    );
    const aggregate = await firestore.doc("analyticsDailyAggregates/2026-08-19").get();
    assert.deepEqual(aggregate.get("counts"), { ACCOUNT_DELETION_COMPLETED: 1 });
    assert.equal(JSON.stringify(aggregate.data()).includes(ownerUid), false);
    assert.equal((await firestore.doc(`users/${ownerUid}`).get()).exists, false);
    await new FirebaseAccountDeletionCleaner(firestore, storage, firebaseAuth).clean(
      ownerUid,
      "AUTH_ACCOUNT",
    );
  } finally {
    await firestore.recursiveDelete(firestore.collection("analyticsDailyAggregates"));
    await deleteApp(app);
  }
});
