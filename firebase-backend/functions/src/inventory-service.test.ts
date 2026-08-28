import assert from "node:assert/strict"
import test from "node:test"
import {
  InventoryCatalogItemSchema,
  type InventoryReceipt,
  type InventorySnapshot,
  InventorySnapshotSchema,
  inventorySnapshotHash,
  OwnerIdSchema,
} from "./inventory-contract.js"
import {
  acquireInventoryItem,
  type InventoryAcquireCommand,
  type InventoryStore,
  inventoryAuth,
  loadInventory,
} from "./inventory-service.js"

const owner = OwnerIdSchema.parse("inventory-owner")
const media = {
  path: "items/item-lamp.png",
  sha256: "a".repeat(64),
  byteSize: 128,
  mimeType: "image/png" as const,
  width: 64,
  height: 64,
  mediaRevision: 2,
}

function snapshot(): InventorySnapshot {
  const source = {
    contractVersion: 3 as const,
    ownerUid: owner,
    catalog: [
      InventoryCatalogItemSchema.parse({
        itemId: "item-lamp",
        name: "Lamp",
        description: "Server item",
        category: "DECORATION",
        mediaIdentity: media,
        acquisitionCondition: null,
        revision: 3,
        updatedAtEpochMillis: 1,
      }),
    ],
    owned: [],
    registeredPlantCount: 0,
    loadedAtEpochMillis: 1,
    partial: false,
    inventoryGeneration: 1,
  }
  return InventorySnapshotSchema.parse({ ...source, snapshotHash: inventorySnapshotHash(source) })
}

class InventoryStoreFake implements InventoryStore {
  readonly commands: InventoryAcquireCommand[] = []

  async load(ownerUid: typeof owner): Promise<InventorySnapshot> {
    return { ...snapshot(), ownerUid }
  }

  async acquire(command: InventoryAcquireCommand): Promise<InventoryReceipt> {
    this.commands.push(command)
    return {
      kind: "acquired",
      ownerUid: command.ownerUid,
      itemId: command.itemId,
      catalogRevision: command.expectedCatalogRevision,
      ownershipRevision: 1,
      acquiredAtEpochMillis: 1,
      mediaIdentity: media,
    }
  }
}

test("Given valid authentication, when loading v3 inventory, then the store receives only auth uid", async () => {
  // Given
  const store = new InventoryStoreFake()

  // When
  const result = await loadInventory(
    inventoryAuth({ uid: owner }),
    { expectedOwnerUid: owner },
    store,
  )

  // Then
  assert.equal(result.ownerUid, owner)
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
  ])
})

test("Given invalid or foreign caller data, when acquiring, then it rejects before persistence", async () => {
  // Given
  const invalidInputs: readonly unknown[] = [
    {
      expectedOwnerUid: "other-owner",
      itemId: owner,
      expectedCatalogRevision: 3,
      operationId: "operation-0001",
    },
    {
      expectedOwnerUid: owner,
      itemId: "bad/item",
      expectedCatalogRevision: 3,
      operationId: "operation-0001",
    },
    {
      expectedOwnerUid: owner,
      itemId: owner,
      expectedCatalogRevision: 0,
      operationId: "operation-0001",
    },
    {
      expectedOwnerUid: owner,
      itemId: owner,
      expectedCatalogRevision: 3,
      operationId: "short",
      extra: true,
    },
  ]

  // When
  for (const input of invalidInputs) {
    const store = new InventoryStoreFake()
    await assert.rejects(() => acquireInventoryItem(inventoryAuth({ uid: owner }), input, store))
    assert.equal(store.commands.length, 0)
  }

  // Then
  await assert.rejects(() =>
    loadInventory(null, { expectedOwnerUid: owner }, new InventoryStoreFake()),
  )
})

test("Given an acquisition command, when it is authorized, then its idempotency hash is canonical", async () => {
  // Given
  const store = new InventoryStoreFake()

  // When
  const receipt = await acquireInventoryItem(
    inventoryAuth({ uid: owner }),
    {
      expectedOwnerUid: owner,
      itemId: owner,
      expectedCatalogRevision: 3,
      operationId: "operation-0001",
    },
    store,
  )

  // Then
  assert.equal(receipt.kind, "acquired")
  assert.deepEqual(store.commands, [
    {
      ownerUid: owner,
      itemId: owner,
      expectedCatalogRevision: 3,
      operationId: "operation-0001",
      requestHash: "4a213d3074f32d1180f53fa971f25455b1f8219f3af8a2a03b34a5cf3ef1f511",
    },
  ])
})
