import assert from "node:assert/strict";
import test from "node:test";
import {
  ACCOUNT_DELETION_GRACE_MILLIS,
  ACCOUNT_DELETION_SCOPES,
  type AccountDeletionAuth,
  type AccountDeletionCleaner,
  AccountDeletionCleanupError,
  type AccountDeletionScope,
} from "./account-deletion-contract.js";
import { runAccountDeletionScan } from "./account-deletion-processor.js";
import { requestAccountDeletion, retryAccountDeletion } from "./account-deletion-service.js";
import { MemoryAccountDeletionStore } from "./account-deletion-test-fixture.test.js";

const NOW_MILLIS = Date.parse("2026-08-12T00:00:00.000Z");
const DUE_MILLIS = NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS;
const OWNER_UID = "user-a";
const AUTH: AccountDeletionAuth = { uid: OWNER_UID, authTimeSeconds: NOW_MILLIS / 1_000 };

/** Mutable cleanup fake; calls and failures are the behavior under test. */
class RecordingCleaner implements AccountDeletionCleaner {
  readonly calls: AccountDeletionScope[] = [];

  constructor(readonly failures: Set<AccountDeletionScope> = new Set()) {}

  async clean(ownerUid: string, scope: AccountDeletionScope): Promise<void> {
    assert.equal(ownerUid, OWNER_UID);
    this.calls.push(scope);
    if (this.failures.has(scope)) {
      throw new AccountDeletionCleanupError(ownerUid, scope);
    }
  }
}

function exactSignal(): Readonly<{ wait: Promise<void>; emit: () => void }> {
  let emitSignal: (() => void) | undefined;
  const wait = new Promise<void>((resolve, reject) => {
    const safety = setTimeout(
      () => reject(new Error("Timed out waiting for exact test signal")),
      10_000,
    );
    emitSignal = () => {
      clearTimeout(safety);
      resolve();
    };
  });
  return {
    wait,
    emit: () => {
      if (emitSignal === undefined) throw new TypeError("Signal is not subscribed");
      emitSignal();
    },
  };
}

async function receivedStore(): Promise<MemoryAccountDeletionStore> {
  const store = new MemoryAccountDeletionStore();
  await requestAccountDeletion(
    AUTH,
    {
      expectedOwnerUid: OWNER_UID,
      confirmed: true,
      idempotencyKey: "delete-aaaaaaaa",
    },
    {
      store,
      nowMillis: () => NOW_MILLIS,
      requestId: () => "deletion-request-0001",
    },
  );
  return store;
}

test("scanner performs no cleanup before the exact seven-day deadline", async () => {
  // Given
  const store = await receivedStore();
  const cleaner = new RecordingCleaner();

  // When
  const result = await runAccountDeletionScan({
    store,
    cleaner,
    nowMillis: () => DUE_MILLIS - 1,
  });

  // Then
  assert.deepEqual(result, { claimed: 0, completed: 0, failed: 0, partiallyFailed: 0 });
  assert.deepEqual(cleaner.calls, []);
  assert.equal((await store.load(OWNER_UID))?.status, "RECEIVED");
});

test("processor attempts every non-auth scope before Firebase Auth last", async () => {
  // Given
  const store = await receivedStore();
  const cleaner = new RecordingCleaner();

  // When
  const result = await runAccountDeletionScan({
    store,
    cleaner,
    nowMillis: () => DUE_MILLIS,
  });

  // Then
  assert.deepEqual(cleaner.calls, [
    "PUBLIC_SHARES",
    "NOTIFICATION_ENDPOINT_OWNERS",
    "PRIVATE_MEDIA_RESERVATIONS",
    "IDENTIFICATION_ORIGINALS",
    "PLANT_PHOTOS",
    "SHARE_IMAGES",
    "OWNER_GLOBAL_SWEEP",
    "USER_DOCUMENTS",
    "AUTH_ACCOUNT",
  ]);
  assert.equal(cleaner.calls.at(-1), "AUTH_ACCOUNT");
  assert.deepEqual(result, { claimed: 1, completed: 1, failed: 0, partiallyFailed: 0 });
  assert.equal(await store.load(OWNER_UID), null);
});

test("irreversible success plus remaining failure is partial and never deletes Auth", async () => {
  // Given
  const store = await receivedStore();
  const cleaner = new RecordingCleaner(new Set(["PLANT_PHOTOS"]));

  // When
  const result = await runAccountDeletionScan({
    store,
    cleaner,
    nowMillis: () => DUE_MILLIS,
  });

  // Then
  assert.deepEqual(result, { claimed: 1, completed: 0, failed: 0, partiallyFailed: 1 });
  assert.equal(cleaner.calls.includes("AUTH_ACCOUNT"), false);
  const workflow = await store.load(OWNER_UID);
  assert.equal(workflow?.status, "PARTIALLY_FAILED");
  assert.deepEqual(workflow?.failedScopes, ["PLANT_PHOTOS", "AUTH_ACCOUNT"]);
  assert.equal(workflow?.completedAtMillis, null);
});

test("skipped dependency scopes remain reported as undeleted", async () => {
  // Given
  const store = await receivedStore();
  const firstCleaner = new RecordingCleaner(new Set(["PUBLIC_SHARES"]));

  // When
  await runAccountDeletionScan({
    store,
    cleaner: firstCleaner,
    nowMillis: () => DUE_MILLIS,
  });

  // Then
  assert.equal(firstCleaner.calls.includes("USER_DOCUMENTS"), false);
  assert.equal(firstCleaner.calls.includes("AUTH_ACCOUNT"), false);
  const record = await store.load(OWNER_UID);
  assert.equal(record?.status, "PARTIALLY_FAILED");
  assert.deepEqual(record?.failedScopes, ["PUBLIC_SHARES", "USER_DOCUMENTS", "AUTH_ACCOUNT"]);
});

test("an in-flight notification send defers user documents and Auth", async () => {
  // Given
  const store = await receivedStore();
  const cleaner = new RecordingCleaner(new Set(["NOTIFICATION_ENDPOINT_OWNERS"]));

  // When
  await runAccountDeletionScan({
    store,
    cleaner,
    nowMillis: () => DUE_MILLIS,
  });

  // Then
  assert.equal(cleaner.calls.includes("USER_DOCUMENTS"), false);
  assert.equal(cleaner.calls.includes("AUTH_ACCOUNT"), false);
  assert.deepEqual((await store.load(OWNER_UID))?.failedScopes, [
    "NOTIFICATION_ENDPOINT_OWNERS",
    "USER_DOCUMENTS",
    "AUTH_ACCOUNT",
  ]);
});

test("failed retry creates a fresh request with a fresh seven-day grace", async () => {
  // Given
  const store = await receivedStore();
  const failedScopes = new Set(ACCOUNT_DELETION_SCOPES.filter((scope) => scope !== "AUTH_ACCOUNT"));
  await runAccountDeletionScan({
    store,
    cleaner: new RecordingCleaner(failedScopes),
    nowMillis: () => DUE_MILLIS,
  });
  const retryAt = DUE_MILLIS + 1_000;
  const retryDependencies = {
    store,
    nowMillis: () => retryAt,
    requestId: () => "deletion-request-0002",
  };

  // When
  const retried = await retryAccountDeletion(
    { uid: OWNER_UID, authTimeSeconds: Math.floor(retryAt / 1_000) },
    {
      expectedOwnerUid: OWNER_UID,
      confirmed: true,
      idempotencyKey: "delete-bbbbbbbb",
    },
    retryDependencies,
  );

  // Then
  assert.equal(retried.status, "RECEIVED");
  assert.equal(retried.requestId, "deletion-request-0002");
  assert.equal(retried.scheduledForMillis, retryAt + ACCOUNT_DELETION_GRACE_MILLIS);
});

test("request replay after failed returns the original accepted request", async () => {
  // Given
  const store = await receivedStore();
  const original = await store.load(OWNER_UID);
  assert.ok(original !== null);
  const failedScopes = new Set(ACCOUNT_DELETION_SCOPES.filter((scope) => scope !== "AUTH_ACCOUNT"));
  await runAccountDeletionScan({
    store,
    cleaner: new RecordingCleaner(failedScopes),
    nowMillis: () => DUE_MILLIS,
  });

  // When
  const replay = await requestAccountDeletion(
    { uid: OWNER_UID, authTimeSeconds: Math.floor((DUE_MILLIS + 1) / 1_000) },
    {
      expectedOwnerUid: OWNER_UID,
      confirmed: true,
      idempotencyKey: "delete-aaaaaaaa",
    },
    {
      store,
      nowMillis: () => DUE_MILLIS + 1,
      requestId: () => "deletion-request-0002",
    },
  );

  // Then
  assert.equal(replay.requestId, original.requestId);
  assert.equal(replay.status, "RECEIVED");
});

test("expired worker cannot finish after a reclaimed worker deletes Auth", async () => {
  // Given
  const store = await receivedStore();
  const firstCleanupStarted = exactSignal();
  const releaseFirstCleanup = exactSignal();
  const firstCleaner = new RecordingCleaner();
  const pausedCleaner: AccountDeletionCleaner = {
    clean: async (ownerUid, scope) => {
      if (firstCleaner.calls.length === 0) {
        firstCleanupStarted.emit();
        await releaseFirstCleanup.wait;
      }
      await firstCleaner.clean(ownerUid, scope);
    },
  };
  const firstRun = runAccountDeletionScan({
    store,
    cleaner: pausedCleaner,
    nowMillis: () => DUE_MILLIS,
  });
  await firstCleanupStarted.wait;
  const secondCleaner = new RecordingCleaner();

  // When
  const secondResult = await runAccountDeletionScan({
    store,
    cleaner: secondCleaner,
    nowMillis: () => DUE_MILLIS + 10 * 60 * 1_000 + 1,
  });
  releaseFirstCleanup.emit();

  // Then
  assert.equal(secondCleaner.calls.at(-1), "AUTH_ACCOUNT");
  assert.deepEqual(secondResult, { claimed: 1, completed: 1, failed: 0, partiallyFailed: 0 });
  await assert.rejects(firstRun);
  assert.equal(await store.load(OWNER_UID), null);
});

test("fresh reauthenticated partial retry runs only remaining scopes and converges immediately", async () => {
  // Given
  const store = await receivedStore();
  await runAccountDeletionScan({
    store,
    cleaner: new RecordingCleaner(new Set(["PLANT_PHOTOS"])),
    nowMillis: () => DUE_MILLIS,
  });
  const retryAt = DUE_MILLIS + 1;
  const cleaner = new RecordingCleaner();
  await retryAccountDeletion(
    { uid: OWNER_UID, authTimeSeconds: Math.floor(retryAt / 1_000) },
    {
      expectedOwnerUid: OWNER_UID,
      confirmed: true,
      idempotencyKey: "delete-cccccccc",
    },
    {
      store,
      nowMillis: () => retryAt,
      requestId: () => "unused-partial-retry-request-0001",
    },
  );

  // When
  const result = await runAccountDeletionScan({
    store,
    cleaner,
    nowMillis: () => retryAt,
  });

  // Then
  assert.deepEqual(cleaner.calls, ["PLANT_PHOTOS", "AUTH_ACCOUNT"]);
  assert.deepEqual(result, { claimed: 1, completed: 1, failed: 0, partiallyFailed: 0 });
  assert.equal(await store.load(OWNER_UID), null);
});
