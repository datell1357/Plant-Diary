import assert from "node:assert/strict";
import test from "node:test";
import {
  InventoryError,
  inventorySnapshotHash,
  executeAcquireInventoryItem,
  executeLoadInventory,
  type InventoryAcquireCommand,
  type InventoryAcquireResult,
  type InventorySnapshot,
  type InventoryStore,
} from "./inventory.js";

const mediaIdentity = (itemId: string, digest: string) => ({
  path: `catalog-assets/${itemId}/${digest}.webp`,
  sha256: digest,
  byteSize: 3,
  mimeType: "image/webp" as const,
  width: 96,
  height: 64,
  mediaRevision: 1,
});

class FakeInventoryStore implements InventoryStore {
  snapshot: InventorySnapshot = {
    contractVersion: 3,
    ownerUid: "user-a",
    catalog: [
      {
        itemId: "free-background",
        name: "햇살 벽지",
        description: "방을 환하게 꾸며요.",
        category: "BACKGROUND",
        mediaIdentity: mediaIdentity("free-background", "a".repeat(64)),
        acquisitionCondition: null,
        revision: 3,
        updatedAtEpochMillis: 1,
      },
      {
        itemId: "plant-lamp",
        name: "새싹 조명",
        description: "첫 식물과 함께 켜는 조명이에요.",
        category: "DECORATION",
        mediaIdentity: mediaIdentity("plant-lamp", "b".repeat(64)),
        acquisitionCondition: "registered-plant",
        revision: 4,
        updatedAtEpochMillis: 2,
      },
    ],
    owned: [],
    registeredPlantCount: 0,
    loadedAtEpochMillis: 3,
    partial: false,
    inventoryGeneration: 1,
    snapshotHash: "0".repeat(64),
  };
  commands: InventoryAcquireCommand[] = [];
  constructor() {
    this.snapshot = { ...this.snapshot, snapshotHash: inventorySnapshotHash(this.snapshot) };
  }

  result: InventoryAcquireResult = {
    kind: "acquired",
    ownerUid: "user-a",
    itemId: "free-background",
    catalogRevision: 3,
    ownershipRevision: 1,
    acquiredAtEpochMillis: 10,
    mediaIdentity: mediaIdentity("free-background", "a".repeat(64)),
  };

  async load(ownerUid: string): Promise<InventorySnapshot> {
    return { ...this.snapshot, ownerUid };
  }

  async acquire(command: InventoryAcquireCommand): Promise<InventoryAcquireResult> {
    this.commands.push(command);
    return { ...this.result, ownerUid: command.ownerUid, itemId: command.itemId };
  }
}

test("snapshot hash matches the cross-runtime canonical vector", () => {
  assert.equal(
    inventorySnapshotHash({
      ownerUid: "account-a",
      catalog: [],
      owned: [],
      registeredPlantCount: 0,
      partial: false,
    }),
    "e833d614de9ea3d590941a196d4704e46c9eab207622311d6a3a1fd1215e0759",
  );
});

const request = {
  expectedOwnerUid: "user-a",
  itemId: "free-background",
  expectedCatalogRevision: 3,
  operationId: "inventory-operation-0001",
};

test("inventory load derives owner and exposes only typed free or action conditions", async () => {
  const store = new FakeInventoryStore();
  const result = await executeLoadInventory({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store);
  assert.equal(result.ownerUid, "user-a");
  assert.deepEqual(Object.keys(result).sort(), [
    "catalog",
    "contractVersion",
    "inventoryGeneration",
    "loadedAtEpochMillis",
    "owned",
    "ownerUid",
    "partial",
    "registeredPlantCount",
    "snapshotHash",
  ]);
  assert.equal(result.contractVersion, 3);
  assert.deepEqual(result.catalog.map((item) => item.acquisitionCondition), [null, "registered-plant"]);
  assert.equal(result.registeredPlantCount, 0);
});

test("inventory load rejects an unsupported response contract version", async () => {
  const store = new FakeInventoryStore();
  store.snapshot = { ...store.snapshot, contractVersion: 2 as 3 };
  await assert.rejects(
    () => executeLoadInventory({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store),
    (error: unknown) =>
      error instanceof InventoryError &&
      error.reason === "MALFORMED_RESPONSE" &&
      error.details?.field === "contractVersion",
  );
});

test("inventory boundaries reject spoofing malformed identifiers revisions and extra fields before store I O", async () => {
  const invalid: unknown[] = [
    null,
    { ...request, extra: true },
    { ...request, expectedOwnerUid: "user-b" },
    { ...request, itemId: "bad/id" },
    { ...request, expectedCatalogRevision: -1 },
    { ...request, expectedCatalogRevision: Number.MAX_SAFE_INTEGER },
    { ...request, operationId: "short" },
  ];
  for (const input of invalid) {
    const store = new FakeInventoryStore();
    await assert.rejects(
      () => executeAcquireInventoryItem({ uid: "user-a" }, input, store),
      InventoryError,
    );
    assert.equal(store.commands.length, 0);
  }
  await assert.rejects(
    () => executeAcquireInventoryItem(null, request, new FakeInventoryStore()),
    (error: unknown) => error instanceof InventoryError && error.code === "unauthenticated",
  );
});

test("acquisition hashes the exact owner item revision and operation command", async () => {
  const store = new FakeInventoryStore();
  assert.deepEqual(await executeAcquireInventoryItem({ uid: "user-a" }, request, store), store.result);
  assert.equal(store.commands.length, 1);
  assert.deepEqual(store.commands[0], {
    ownerUid: "user-a",
    itemId: "free-background",
    expectedCatalogRevision: 3,
    operationId: "inventory-operation-0001",
    requestHash: "7f25dad8b6dd05f69e06766370e255f1c36bb2b4b23830da37231fd41afaa904",
  });
});

test("typed acquisition outcomes preserve exact condition and ownership receipts", async () => {
  const store = new FakeInventoryStore();
  const outcomes: InventoryAcquireResult[] = [
    {
      kind: "condition-not-met",
      ownerUid: "user-a",
      itemId: "plant-lamp",
      catalogRevision: 4,
      condition: "registered-plant",
    },
    {
      kind: "already-owned",
      ownerUid: "user-a",
      itemId: "free-background",
      catalogRevision: 3,
      ownershipRevision: 1,
      acquiredAtEpochMillis: 10,
      mediaIdentity: mediaIdentity("free-background", "a".repeat(64)),
    },
    store.result,
  ];
  for (const outcome of outcomes) {
    store.result = outcome;
    const itemRequest = {
      ...request,
      itemId: outcome.itemId,
      expectedCatalogRevision: outcome.catalogRevision,
    };
    assert.deepEqual(await executeAcquireInventoryItem({ uid: "user-a" }, itemRequest, store), outcome);
  }
});
