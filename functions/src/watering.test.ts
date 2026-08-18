import assert from "node:assert/strict";
import test from "node:test";
import type { MutationResult } from "./contracts.js";
import {
  WateringError,
  executeWateringCompletion,
  resolveAccountLocalDate,
  type WateringCompletionCommand,
  type WateringCompletionStore,
} from "./watering.js";

class FakeStore implements WateringCompletionStore {
  readonly commands: WateringCompletionCommand[] = [];
  result: MutationResult = { kind: "applied", revision: 5 };

  async completeWatering(command: WateringCompletionCommand, _now: Date) {
    this.commands.push(command);
    return this.result;
  }
}

const valid = {
  expectedOwnerUid: "user-a",
  collection: "wateringCompletions",
  documentId: "plant-a",
  mutationType: "UPDATE",
  expectedRevision: 4,
  idempotencyKey: "watering-operation-stable",
  payload: { wateredDate: "2026-08-11" },
};

test("watering completion derives owner and retains one stable idempotency command", async () => {
  const store = new FakeStore();

  assert.deepEqual(
    await executeWateringCompletion({ uid: "user-a" }, valid, store, new Date("2026-08-11T00:00:00Z")),
    { kind: "applied", revision: 5 },
  );
  store.result = { kind: "duplicate", revision: 5 };
  assert.deepEqual(
    await executeWateringCompletion({ uid: "user-a" }, valid, store, new Date("2026-08-11T00:00:00Z")),
    { kind: "duplicate", revision: 5 },
  );

  assert.equal(store.commands.length, 2);
  assert.equal(new Set(store.commands.map((command) => command.idempotencyKey)).size, 1);
  assert.equal(store.commands[0]?.ownerUid, "user-a");
});

test("watering completion rejects spoofing malformed fields and future dates", async () => {
  const store = new FakeStore();

  await assert.rejects(
    () => executeWateringCompletion(null, valid, store, new Date()),
    (error: unknown) => error instanceof WateringError && error.code === "unauthenticated",
  );
  await assert.rejects(
    () => executeWateringCompletion({ uid: "user-b" }, valid, store, new Date()),
    (error: unknown) => error instanceof WateringError && error.code === "permission-denied",
  );
  await assert.rejects(
    () => executeWateringCompletion({ uid: "user-a" }, { ...valid, extra: "secret" }, store, new Date()),
    (error: unknown) => error instanceof WateringError && error.code === "invalid-argument",
  );
  await assert.rejects(
    () => executeWateringCompletion(
      { uid: "user-a" },
      { ...valid, payload: { wateredDate: "2026-02-31" } },
      store,
      new Date(),
    ),
    WateringError,
  );
  assert.equal(store.commands.length, 0);
});

test("account local date defaults across timezone boundaries without a device timezone", () => {
  const instant = new Date("2026-08-10T15:30:00Z");

  assert.equal(resolveAccountLocalDate(undefined, "Asia/Seoul", instant), "2026-08-11");
  assert.equal(resolveAccountLocalDate(undefined, "America/Los_Angeles", instant), "2026-08-10");
  assert.throws(
    () => resolveAccountLocalDate("2026-08-12", "Asia/Seoul", instant),
    (error: unknown) => error instanceof WateringError && error.code === "invalid-argument",
  );
});
