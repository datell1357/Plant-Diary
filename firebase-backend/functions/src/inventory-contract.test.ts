import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import { fileURLToPath } from "node:url"
import { z } from "zod"
import * as functions from "./index.js"

test("Given the shared v3 fixture, when the tracked backend hashes it, then it matches iOS", async () => {
  const fixturePath = fileURLToPath(
    new URL("../../../docs/ios/inventory-contract-v3.fixture.json", import.meta.url),
  )
  const fixture: unknown = JSON.parse(await readFile(fixturePath, "utf8"))

  const parsed = z
    .object({
      contractVersion: z.literal(3),
      callables: z.tuple([z.literal("loadInventory"), z.literal("acquireInventoryItem")]),
      snapshot: functions.InventorySnapshotSchema,
      receipt: functions.InventoryReceiptSchema,
      alreadyOwnedReceipt: functions.InventoryReceiptSchema,
    })
    .strict()
    .parse(fixture)

  assert.equal(typeof functions.loadInventory, "function")
  assert.equal(typeof functions.acquireInventoryItem, "function")
  assert.equal(
    functions.inventorySnapshotHash(parsed.snapshot),
    "60f3b1e9bcf9631105f78c1468ac1e97d88bfaa27213d02cf692ba84058b5bd4",
  )
  assert.deepEqual(Object.keys(parsed.receipt).sort(), [
    "acquiredAtEpochMillis",
    "catalogRevision",
    "itemId",
    "kind",
    "mediaIdentity",
    "ownerUid",
    "ownershipRevision",
  ])
  assert.equal(parsed.receipt.kind, "acquired")
  assert.equal(parsed.alreadyOwnedReceipt.kind, "already-owned")

  const catalogItem = parsed.snapshot.catalog.at(0)
  const ownedItem = parsed.snapshot.owned.at(0)
  assert.ok(catalogItem)
  assert.ok(ownedItem)
  const baselineHash = functions.inventorySnapshotHash(parsed.snapshot)
  const changedSnapshots = [
    functions.InventorySnapshotSchema.parse({
      ...parsed.snapshot,
      catalog: [{ ...catalogItem, itemId: "item-vintage-lamp" }],
      owned: [{ ...ownedItem, itemId: "item-vintage-lamp" }],
    }),
    functions.InventorySnapshotSchema.parse({
      ...parsed.snapshot,
      catalog: [{ ...catalogItem, revision: 4 }],
    }),
    functions.InventorySnapshotSchema.parse({
      ...parsed.snapshot,
      owned: [{ ...ownedItem, revision: 6 }],
    }),
    functions.InventorySnapshotSchema.parse({
      ...parsed.snapshot,
      owned: [{ ...ownedItem, acquiredAtEpochMillis: 1_787_616_000_001 }],
    }),
    functions.InventorySnapshotSchema.parse({
      ...parsed.snapshot,
      catalog: [
        {
          ...catalogItem,
          mediaIdentity: { ...catalogItem.mediaIdentity, sha256: "b".repeat(64) },
        },
      ],
    }),
  ]
  for (const changedSnapshot of changedSnapshots) {
    assert.notEqual(functions.inventorySnapshotHash(changedSnapshot), baselineHash)
  }

  const placementOnly = functions.InventorySnapshotSchema.parse({
    ...parsed.snapshot,
    owned: [{ ...ownedItem, applied: true }],
  })
  assert.equal(functions.inventorySnapshotHash(placementOnly), baselineHash)
})
