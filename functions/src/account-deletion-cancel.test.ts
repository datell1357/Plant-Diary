import assert from "node:assert/strict";
import test from "node:test";
import {
  ACCOUNT_DELETION_GRACE_MILLIS,
  AccountDeletionError,
  type AccountDeletionAuth,
} from "./account-deletion-contract.js";
import {
  cancelAccountDeletion,
  requestAccountDeletion,
} from "./account-deletion-service.js";
import { MemoryAccountDeletionStore } from "./account-deletion-test-fixture.test.js";

const NOW_MILLIS = Date.parse("2026-08-26T00:00:00.000Z");
const OWNER_UID = "account-deletion-cancel-owner";
const AUTH: AccountDeletionAuth = {
  uid: OWNER_UID,
  authTimeSeconds: NOW_MILLIS / 1_000,
};

test("account deletion cannot be cancelled while processing remains mutation locked", async () => {
  // Given
  const store = new MemoryAccountDeletionStore();
  const requested = await requestAccountDeletion(AUTH, {
    expectedOwnerUid: OWNER_UID,
    confirmed: true,
    idempotencyKey: "account-deletion-cancel-operation",
  }, {
    store,
    nowMillis: () => NOW_MILLIS,
    requestId: () => "account-deletion-cancel-request",
  });
  const claimed = await store.claimDue({
    nowMillis: NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS,
    leaseExpiresAtMillis: NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS + 60_000,
    limit: 1,
  });
  assert.equal(claimed.length, 1);

  // When
  const action = cancelAccountDeletion(
    AUTH,
    { expectedOwnerUid: OWNER_UID, requestId: requested.requestId },
    { store, nowMillis: () => NOW_MILLIS + ACCOUNT_DELETION_GRACE_MILLIS + 1 },
  );

  // Then
  await assert.rejects(
    action,
    (error: unknown) =>
      error instanceof AccountDeletionError && error.code === "failed-precondition",
  );
  assert.equal((await store.load(OWNER_UID))?.status, "PROCESSING");
});
