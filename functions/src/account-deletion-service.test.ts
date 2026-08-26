import assert from "node:assert/strict";
import test from "node:test";
import {
  ACCOUNT_DELETION_GRACE_MILLIS,
  AccountDeletionError,
  type AccountDeletionAuth,
} from "./account-deletion-contract.js";
import {
  cancelAccountDeletion,
  getAccountDeletionStatus,
  previewAccountDeletion,
  requestAccountDeletion,
  retryAccountDeletion,
} from "./account-deletion-service.js";
import { MemoryAccountDeletionStore } from "./account-deletion-test-fixture.test.js";
import type { ServerAnalyticsOperation } from "./server-analytics.js";

const NOW_MILLIS = Date.parse("2026-08-12T00:00:00.000Z");
const OWNER_UID = "user-a";
const AUTH: AccountDeletionAuth = {
  uid: OWNER_UID,
  authTimeSeconds: NOW_MILLIS / 1_000,
};
const CONFIRMED_REQUEST = {
  expectedOwnerUid: OWNER_UID,
  confirmed: true,
  idempotencyKey: "delete-aaaaaaaa",
};

function dependencies(store: MemoryAccountDeletionStore, nowMillis = NOW_MILLIS) {
  return {
    store,
    nowMillis: () => nowMillis,
    requestId: () => "deletion-request-0001",
  };
}

test("request creates one seven-day request when the same command is submitted twice", async () => {
  // Given
  const store = new MemoryAccountDeletionStore();
  const service = dependencies(store);

  // When
  const first = await requestAccountDeletion(AUTH, CONFIRMED_REQUEST, service);
  const duplicate = await requestAccountDeletion(AUTH, CONFIRMED_REQUEST, service);

  // Then
  assert.deepEqual(duplicate, first);
  assert.equal(store.size, 1);
  assert.equal(first.status, "RECEIVED");
  assert.equal(first.scheduledForMillis, NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS);
  const stored = await store.load(OWNER_UID);
  assert.equal(stored?.idempotencyKeyHash.length, 64);
  assert.equal(JSON.stringify(stored).includes(CONFIRMED_REQUEST.idempotencyKey), false);
});

test("request analytics follows the committed transaction and replay uses the persisted request identity", async () => {
  const store = new MemoryAccountDeletionStore();
  store.analyticsConsentGranted = true;
  const steps: string[] = [];
  const persistedRequest = store.request.bind(store);
  store.request = async (command) => {
    const result = await persistedRequest(command);
    steps.push("persisted");
    return result;
  };
  const events: unknown[] = [];
  const service = {
    ...dependencies(store),
    analytics: async (event: ServerAnalyticsOperation) => {
      steps.push("analytics");
      events.push(event);
      return { kind: "transient" as const };
    },
    analyticsDeletion: {
      async revokeForAccountDeletion() {
        steps.push("revoke");
        return { purgedEventCount: 0 };
      },
    },
  };

  const first = await requestAccountDeletion(AUTH, CONFIRMED_REQUEST, service);
  const replay = await requestAccountDeletion(AUTH, CONFIRMED_REQUEST, {
    ...service,
    requestId: () => "unused-replay-request-id",
  });

  assert.deepEqual(replay, first);
  assert.deepEqual(steps, ["persisted", "analytics", "revoke", "persisted", "revoke"]);
  assert.deepEqual(events, [
    {
      ownerUid: OWNER_UID,
      eventName: "ACCOUNT_DELETION_REQUESTED",
      operationIdentifier: "deletion-request-0001",
    },
  ]);
  assert.equal((await store.load(OWNER_UID))?.analyticsRequestOutcome, "TRANSIENT");

  const failedStore = new MemoryAccountDeletionStore();
  failedStore.request = async () => {
    throw new Error("request transaction failed");
  };
  let failedEvents = 0;
  await assert.rejects(
    () => requestAccountDeletion(AUTH, CONFIRMED_REQUEST, {
      ...dependencies(failedStore),
      analytics: async (_event: ServerAnalyticsOperation) => {
        failedEvents += 1;
        return { kind: "recorded", eventId: "event", replayed: false };
      },
    }),
    /request transaction failed/,
  );
  assert.equal(failedEvents, 0);
});

test("request replay retries the exact analytics revoke after response loss", async () => {
  const store = new MemoryAccountDeletionStore();
  const operations: string[] = [];
  let loseFirstResponse = true;
  const service = {
    ...dependencies(store),
    analyticsDeletion: {
      async revokeForAccountDeletion(ownerUid: string, operationId: string) {
        assert.equal(ownerUid, OWNER_UID);
        operations.push(operationId);
        if (loseFirstResponse) {
          loseFirstResponse = false;
          throw new Error("injected revoke response loss");
        }
        return { purgedEventCount: 0 };
      },
    },
  };

  await assert.rejects(
    requestAccountDeletion(AUTH, CONFIRMED_REQUEST, service),
    /injected revoke response loss/,
  );
  const replay = await requestAccountDeletion(AUTH, CONFIRMED_REQUEST, {
    ...service,
    requestId: () => "unused-replay-request-id",
  });

  assert.equal(replay.requestId, "deletion-request-0001");
  assert.equal(store.size, 1);
  assert.equal(operations.length, 2);
  assert.equal(operations[0], operations[1]);
});

test("request denies a foreign expected owner", async () => {
  // Given
  const store = new MemoryAccountDeletionStore();

  // When
  const action = requestAccountDeletion(
    AUTH,
    { ...CONFIRMED_REQUEST, expectedOwnerUid: "user-b" },
    dependencies(store),
  );

  // Then
  await assert.rejects(
    action,
    (error: unknown) =>
      error instanceof AccountDeletionError && error.code === "permission-denied",
  );
  assert.equal(store.size, 0);
});

test("request requires explicit confirmation", async () => {
  // Given
  const store = new MemoryAccountDeletionStore();

  // When
  const action = requestAccountDeletion(
    AUTH,
    { ...CONFIRMED_REQUEST, confirmed: false },
    dependencies(store),
  );

  // Then
  await assert.rejects(
    action,
    (error: unknown) =>
      error instanceof AccountDeletionError && error.code === "failed-precondition",
  );
  assert.equal(store.size, 0);
});

test("request requires authentication no older than five minutes", async () => {
  // Given
  const store = new MemoryAccountDeletionStore();
  const staleAuth: AccountDeletionAuth = {
    uid: OWNER_UID,
    authTimeSeconds: NOW_MILLIS / 1_000 - 301,
  };

  // When
  const action = requestAccountDeletion(staleAuth, CONFIRMED_REQUEST, dependencies(store));

  // Then
  await assert.rejects(
    action,
    (error: unknown) =>
      error instanceof AccountDeletionError && error.code === "failed-precondition",
  );
  assert.equal(store.size, 0);
});

test("cancel requires the expected request and stale replay cannot cancel its replacement", async () => {
  // Given
  const store = new MemoryAccountDeletionStore();
  const requested = await requestAccountDeletion(AUTH, CONFIRMED_REQUEST, dependencies(store));
  const cancelAt = NOW_MILLIS + 60_000;
  const cancelInput = {
    expectedOwnerUid: OWNER_UID,
    requestId: requested.requestId,
  };
  const cancelled = await cancelAccountDeletion(
    AUTH,
    cancelInput,
    { store, nowMillis: () => cancelAt },
  );
  const rerequested = await requestAccountDeletion(
    { uid: OWNER_UID, authTimeSeconds: (cancelAt + 1_000) / 1_000 },
    { ...CONFIRMED_REQUEST, idempotencyKey: "delete-bbbbbbbb" },
    {
      store,
      nowMillis: () => cancelAt + 1_000,
      requestId: () => "deletion-request-0002",
    },
  );

  // When
  const replayedCancel = await cancelAccountDeletion(
    AUTH,
    cancelInput,
    { store, nowMillis: () => cancelAt + 2_000 },
  );

  // Then
  assert.deepEqual(replayedCancel, cancelled);
  assert.equal((await store.load(OWNER_UID))?.requestId, rerequested.requestId);
  assert.equal((await store.load(OWNER_UID))?.status, "RECEIVED");
});

test("request replay after cancellation is fenced by the minimized terminal tombstone", async () => {
  // Given
  const store = new MemoryAccountDeletionStore();
  const first = await requestAccountDeletion(AUTH, CONFIRMED_REQUEST, dependencies(store));
  await cancelAccountDeletion(
    AUTH,
    { expectedOwnerUid: OWNER_UID, requestId: first.requestId },
    { store, nowMillis: () => NOW_MILLIS + 1 },
  );

  // When
  const replay = requestAccountDeletion(AUTH, CONFIRMED_REQUEST, {
    store,
    nowMillis: () => NOW_MILLIS + 2,
    requestId: () => "deletion-request-0002",
  });

  // Then
  await assert.rejects(
    replay,
    (error: unknown) =>
      error instanceof AccountDeletionError && error.code === "failed-precondition",
  );
  assert.equal(await store.load(OWNER_UID), null);
});

test("partial retry replay after processing returns its original accepted response", async () => {
  // Given
  const store = new MemoryAccountDeletionStore();
  await requestAccountDeletion(AUTH, CONFIRMED_REQUEST, dependencies(store));
  const claimed = await store.claimDue({
    nowMillis: NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS,
    leaseExpiresAtMillis: NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS + 60_000,
    limit: 1,
  });
  const claim = claimed[0];
  assert.ok(claim !== undefined);
  await store.finish({
    ownerUid: OWNER_UID,
    requestId: claim.requestId,
    claimGeneration: claim.claimGeneration,
    completedScopes: ["PUBLIC_SHARES"],
    failedScopes: ["PLANT_PHOTOS"],
    nowMillis: NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS + 1,
  });
  const retryInput = {
    expectedOwnerUid: OWNER_UID,
    confirmed: true,
    idempotencyKey: "retry-aaaaaaaa",
  };
  const retryAuth = {
    uid: OWNER_UID,
    authTimeSeconds: Math.floor(
      (NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS + 2) / 1_000,
    ),
  };
  const accepted = await retryAccountDeletion(retryAuth, retryInput, {
    store,
    nowMillis: () => NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS + 2,
    requestId: () => "unused-partial-retry-request-0001",
  });
  await store.claimDue({
    nowMillis: NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS + 2,
    leaseExpiresAtMillis: NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS + 120_000,
    limit: 1,
  });

  // When
  const replay = await retryAccountDeletion(retryAuth, retryInput, {
    store,
    nowMillis: () => NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS + 3,
    requestId: () => "unused-partial-retry-request-0002",
  });

  // Then
  assert.deepEqual(replay, accepted);
});

test("preview and status expose only the authenticated owner's authoritative request", async () => {
  // Given
  const store = new MemoryAccountDeletionStore();
  await requestAccountDeletion(AUTH, CONFIRMED_REQUEST, dependencies(store));

  // When
  const preview = await previewAccountDeletion(
    AUTH,
    { expectedOwnerUid: OWNER_UID },
    store,
  );
  const status = await getAccountDeletionStatus(
    AUTH,
    { expectedOwnerUid: OWNER_UID },
    store,
  );

  // Then
  assert.deepEqual(status, preview.request);
  assert.equal(preview.scope.gracePeriodMillis, ACCOUNT_DELETION_GRACE_MILLIS);
  assert.ok(status !== null);
  assert.equal("idempotencyKeyHash" in status, false);
});
