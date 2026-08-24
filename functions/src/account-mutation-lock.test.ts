import assert from "node:assert/strict";
import test from "node:test";
import {
  AccountMutationLockedError,
  FirestoreAccountMutationLock,
  withAccountMutationLock,
  type AccountMutationLock,
} from "./account-mutation-lock.js";

class FixedMutationLock implements AccountMutationLock {
  constructor(private readonly processing: boolean) {}

  async isProcessing(ownerUid: string): Promise<boolean> {
    assert.equal(ownerUid, "user-a");
    return this.processing;
  }
}

test("shared mutation boundary prevents the callable body while deletion is processing", async () => {
  // Given
  let calls = 0;
  const handler = withAccountMutationLock(new FixedMutationLock(true), async () => {
    calls += 1;
    return "mutated";
  });

  // When
  const action = handler({ auth: { uid: "user-a" } });

  // Then
  await assert.rejects(action, AccountMutationLockedError);
  assert.equal(calls, 0);
});

test("Firestore lock remains permanent for terminal progress-bearing states", async () => {
  for (const [record, expected] of [
    [{ status: "RECEIVED", completedScopes: [] }, false],
    [{ status: "FAILED", completedScopes: [] }, false],
    [{ status: "PROCESSING", completedScopes: [] }, true],
    [{ status: "PARTIALLY_FAILED", completedScopes: ["PUBLIC_SHARES"] }, true],
    [{ status: "COMPLETED", completedScopes: ["PUBLIC_SHARES"] }, true],
    [{ status: "RECEIVED", completedScopes: ["PUBLIC_SHARES"] }, true],
  ] as const) {
    const firestore = {
      doc: (path: string) => ({
        get: async () => ({
          exists: true,
          get: (field: "status" | "completedScopes") => record[field],
        }),
        path,
      }),
    };
    const lock = new FirestoreAccountMutationLock(firestore as never);

    assert.equal(await lock.isProcessing("user-a"), expected, record.status);
  }
});

test("shared mutation boundary permits the callable body outside processing", async () => {
  // Given
  let calls = 0;
  const handler = withAccountMutationLock(new FixedMutationLock(false), async () => {
    calls += 1;
    return "mutated";
  });

  // When
  const result = await handler({ auth: { uid: "user-a" } });

  // Then
  assert.equal(result, "mutated");
  assert.equal(calls, 1);
});
