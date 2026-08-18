import assert from "node:assert/strict";
import test from "node:test";
import {
  ContractError,
  executeOwnerMutation,
  executeServerStateWrite,
  type MutationStore,
} from "./contracts.js";

class FakeStore implements MutationStore {
  readonly revisions = new Map<string, number>();
  readonly operations = new Map<string, { path: string; requestHash: string; revision: number }>();
  readonly writes = new Map<string, Readonly<Record<string, unknown>>>();

  async ownerZoneId(_ownerUid: string) { return "Asia/Seoul"; }
  async publicPlantContentExists(contentId: string) { return contentId === "species-public"; }

  async applyOwnerMutation(command: Parameters<MutationStore["applyOwnerMutation"]>[0]) {
    const operationPath = `users/${command.ownerUid}/operations/${command.idempotencyKey}`;
    const duplicate = this.operations.get(operationPath);
    if (duplicate) {
      if (duplicate.path !== command.documentPath || duplicate.requestHash !== command.requestHash) throw new ContractError("invalid-argument", "Idempotency key belongs to another request");
      return { kind: "duplicate" as const, revision: duplicate.revision };
    }
    const actual = this.revisions.get(command.documentPath) ?? 0;
    if (actual !== command.expectedRevision) return { kind: "conflict" as const, actualRevision: actual };
    const revision = actual + 1;
    this.revisions.set(command.documentPath, revision);
    this.operations.set(operationPath, { path: command.documentPath, requestHash: command.requestHash, revision });
    this.writes.set(command.documentPath, command.payload);
    return { kind: "applied" as const, revision };
  }

  async writeServerState(command: Parameters<MutationStore["writeServerState"]>[0]) {
    this.writes.set(command.documentPath, command.payload);
  }
}

const validMutation = { expectedOwnerUid: "user-a", collection: "personalPlants", documentId: "plant-a", expectedRevision: 0, idempotencyKey: "operation-0001", payload: { displayName: "몬스테라", registrationMethod: "MANUAL" } };

test("owner is derived from auth and duplicate idempotency key applies once", async () => {
  const store = new FakeStore();
  const first = await executeOwnerMutation({ uid: "user-a" }, validMutation, store);
  const duplicate = await executeOwnerMutation({ uid: "user-a" }, validMutation, store);
  assert.deepEqual(first, { kind: "applied", revision: 1 });
  assert.deepEqual(duplicate, { kind: "duplicate", revision: 1 });
  assert.equal(store.writes.size, 1);
  assert.ok(store.writes.has("users/user-a/personalPlants/plant-a"));
  await assert.rejects(
    () => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, payload: { displayName: "변경", registrationMethod: "MANUAL" } }, store),
    ContractError,
  );
});

test("auth spoof malformed operation and revision conflict are typed errors", async () => {
  const store = new FakeStore();
  await assert.rejects(() => executeOwnerMutation(null, validMutation, store), (error: unknown) => error instanceof ContractError && error.code === "unauthenticated");
  await assert.rejects(
    () => executeOwnerMutation({ uid: "user-b" }, validMutation, store),
    (error: unknown) => error instanceof ContractError && error.code === "permission-denied",
  );
  await assert.rejects(() => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, userId: "user-b" }, store), (error: unknown) => error instanceof ContractError && error.code === "invalid-argument");
  await assert.rejects(() => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, idempotencyKey: "../bad" }, store), ContractError);
  await executeOwnerMutation({ uid: "user-a" }, validMutation, store);
  const conflict = await executeOwnerMutation({ uid: "user-a" }, { ...validMutation, idempotencyKey: "operation-0002", expectedRevision: 0 }, store);
  assert.deepEqual(conflict, { kind: "conflict", actualRevision: 1 });
  await assert.rejects(
    () => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, documentId: "plant-b" }, store),
    (error: unknown) => error instanceof ContractError && error.code === "invalid-argument",
  );
});

test("owner callable rejects impossible dates enums and unknown sensitive fields", async () => {
  const store = new FakeStore();
  const schedule = {
    expectedOwnerUid: "user-a",
    collection: "wateringSchedules",
    documentId: "schedule-a",
    expectedRevision: 0,
    idempotencyKey: "operation-1001",
    payload: { plantId: "plant-a", dueDate: "2026-02-31", reminderTime: "09:00", zoneId: "Asia/Seoul", enabled: true },
  };
  await assert.rejects(() => executeOwnerMutation({ uid: "user-a" }, schedule, store), ContractError);
  await assert.rejects(
    () => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, payload: { ...validMutation.payload, status: "SENT" } }, store),
    ContractError,
  );
  await assert.rejects(
    () => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, payload: { ...validMutation.payload, registrationMethod: "FORGED" } }, store),
    ContractError,
  );
});

test("personal plant registration rejects unnormalized names and foreign representative paths", async () => {
  const store = new FakeStore();
  await assert.rejects(
    () => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, payload: { displayName: " 몬스테라 ", registrationMethod: "MANUAL" } }, store),
    ContractError,
  );
  await assert.rejects(
    () => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, payload: { displayName: "몬스테라", registrationMethod: "MANUAL", representativePhotoPath: "plant-photos/user-b/plant-a/representative.webp" } }, store),
    ContractError,
  );
  await assert.rejects(
    () => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, payload: { displayName: "🌱".repeat(101), registrationMethod: "MANUAL" } }, store),
    ContractError,
  );
  await assert.rejects(
    () => executeOwnerMutation({ uid: "user-a" }, { ...validMutation, payload: { displayName: "몬스테라", registrationMethod: "MANUAL", lastWateredDate: "2999-01-01" } }, store),
    ContractError,
  );
});

test("personal plant content links must resolve to public content", async () => {
  const store = new FakeStore();
  await executeOwnerMutation(
    { uid: "user-a" },
    { ...validMutation, payload: { ...validMutation.payload, contentId: "species-public" } },
    store,
  );
  await assert.rejects(
    () => executeOwnerMutation(
      { uid: "user-a" },
      { ...validMutation, documentId: "plant-private", idempotencyKey: "operation-private", payload: { ...validMutation.payload, contentId: "species-private" } },
      store,
    ),
    ContractError,
  );
});

test("server-only delivery weather deletion and item writes validate state", async () => {
  const store = new FakeStore();
  const commands = [
    { collection: "notificationDeliveries", documentId: "delivery-a", payload: { status: "SENT", scheduledFor: "2026-08-12T00:00:00Z", deliveredAt: "2026-08-12T00:01:00Z", deduplicationKey: "delivery-0001" } },
    { collection: "weatherRisks", documentId: "risk-a", payload: { plantId: "plant-a", snapshotId: "snapshot-a", type: "DRY", detectedAt: "2026-08-12T00:00:00Z", active: true } },
    { collection: "deletionRequests", documentId: "deletion-a", payload: { status: "COMPLETED", requestedAt: "2026-08-01T00:00:00Z", scheduledFor: "2026-08-08T00:00:00Z", completedAt: "2026-08-08T00:01:00Z" } },
    { collection: "ownedItems", documentId: "owned-a", payload: { itemId: "item-a", acquiredAt: "2026-08-12T00:00:00Z", applied: false } },
    { collection: "weatherSnapshots", documentId: "snapshot-a", payload: { regionCode: "11B10101", temperatureCelsius: 27, humidityPercent: 55, precipitationMillimeters: 0, observedAt: "2026-08-12T00:00:00Z", expiresAt: "2026-08-12T01:00:00Z" } },
    { collection: "shareLinks", documentId: "share-a", payload: { miniHomeId: "home-a", sourceRevision: 2, snapshotPath: "share-images/user-a/share-a/share.png", createdAt: "2026-08-12T00:00:00Z", expiresAt: "2026-09-12T00:00:00Z", revokedAt: null } },
  ] as const;
  for (const command of commands) await executeServerStateWrite({ trusted: true }, "user-a", command, store);
  assert.equal(store.writes.size, 6);
  await assert.rejects(() => executeServerStateWrite({ trusted: false }, "user-a", commands[0], store), (error: unknown) => error instanceof ContractError && error.code === "permission-denied");
  await assert.rejects(() => executeServerStateWrite({ trusted: true }, "user-a", { ...commands[1], payload: { ...commands[1].payload, type: "FORGED" } }, store), ContractError);
  await assert.rejects(
    () => executeServerStateWrite({ trusted: true }, "user-a", { ...commands[5], payload: { ...commands[5].payload, expiresAt: "2026-08-01T00:00:00Z" } }, store),
    ContractError,
  );
});
