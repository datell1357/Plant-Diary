import assert from "node:assert/strict"
import test from "node:test"
import { deleteApp, initializeApp } from "firebase-admin/app"
import { getFirestore } from "firebase-admin/firestore"
import { FirestoreInventoryStore } from "./firestore-inventory-store.js"
import { inventorySnapshotHash, OwnerIdSchema } from "./inventory-contract.js"
import { acquireInventoryItem, inventoryAuth, loadInventory } from "./inventory-service.js"

const { GCLOUD_PROJECT } = process.env
const PROJECT_ID = GCLOUD_PROJECT ?? "demo-planterior-ios-deletion"
const NOW = 1_787_616_000_000
const digest = "a".repeat(64)

function catalog(
  itemId: string,
  changes: Readonly<Record<string, unknown>> = {},
): Readonly<Record<string, unknown>> {
  return {
    name: `Item ${itemId}`,
    description: "Server catalog item",
    category: "DECORATION",
    mediaIdentity: {
      path: `catalog-assets/${itemId}/${digest}.png`,
      sha256: digest,
      byteSize: 128,
      mimeType: "image/png",
      width: 64,
      height: 64,
      mediaRevision: 1,
    },
    acquisitionCondition: null,
    revision: 3,
    updatedAtEpochMillis: NOW,
    publicationState: "PUBLISHED",
    available: true,
    ...changes,
  }
}

async function fixture(name: string): Promise<
  Readonly<{
    app: ReturnType<typeof initializeApp>
    store: FirestoreInventoryStore
    firestore: FirebaseFirestore.Firestore
  }>
> {
  const app = initializeApp({ projectId: PROJECT_ID }, name)
  const firestore = getFirestore(app)
  await Promise.all([
    firestore.recursiveDelete(firestore.collection("inventoryCatalog")),
    firestore.recursiveDelete(firestore.collection("users")),
  ])
  return { app, firestore, store: new FirestoreInventoryStore(firestore, () => NOW) }
}

function command(
  owner: string,
  itemId: string,
  operationId: string,
): Readonly<Record<string, unknown>> {
  return { expectedOwnerUid: owner, itemId, expectedCatalogRevision: 3, operationId }
}

test("Given a public catalog and empty owner, when loading inventory, then it returns the sorted v3 hash", async () => {
  // Given
  const { app, firestore, store } = await fixture("inventory-load")
  const owner = OwnerIdSchema.parse("inventory-load-owner")
  await Promise.all([
    firestore.doc("inventoryCatalog/item-vintage-lamp").set(catalog("item-vintage-lamp")),
    firestore.doc("inventoryCatalog/item-lamp").set(catalog("item-lamp")),
    firestore
      .doc("inventoryCatalog/draft-item")
      .set(catalog("draft-item", { publicationState: "DRAFT" })),
  ])
  try {
    // When
    const loaded = await loadInventory(
      inventoryAuth({ uid: owner }),
      { expectedOwnerUid: owner },
      store,
    )

    // Then
    assert.equal(loaded.contractVersion, 3)
    assert.deepEqual(
      loaded.catalog.map((item) => item.itemId),
      ["item-lamp", "item-vintage-lamp"],
    )
    assert.equal(loaded.inventoryGeneration, 1)
    assert.equal(loaded.snapshotHash, inventorySnapshotHash(loaded))

    const stateBefore = await firestore.doc(`users/${owner}/inventoryState/current`).get()
    const loadedAgain = await loadInventory(
      inventoryAuth({ uid: owner }),
      { expectedOwnerUid: owner },
      store,
    )
    const stateAfter = await firestore.doc(`users/${owner}/inventoryState/current`).get()
    assert.equal(loadedAgain.inventoryGeneration, loaded.inventoryGeneration)
    assert.equal(loadedAgain.snapshotHash, loaded.snapshotHash)
    assert.ok(stateBefore.updateTime !== undefined)
    assert.ok(stateAfter.updateTime !== undefined)
    assert.ok(stateBefore.updateTime.isEqual(stateAfter.updateTime))
  } finally {
    await deleteApp(app)
  }
})

test("Given an available catalog item, when acquired and replayed, then ownership is atomic and receipt keys stay exact", async () => {
  // Given
  const { app, firestore, store } = await fixture("inventory-acquire")
  const owner = OwnerIdSchema.parse("inventory-acquire-owner")
  await firestore.doc("inventoryCatalog/item-lamp").set(catalog("item-lamp"))
  const request = command(owner, "item-lamp", "acquire-operation-0001")
  try {
    // When
    const first = await acquireInventoryItem(inventoryAuth({ uid: owner }), request, store)
    const replay = await acquireInventoryItem(inventoryAuth({ uid: owner }), request, store)

    // Then
    assert.equal(first.kind, "acquired")
    assert.deepEqual(replay, first)
    assert.deepEqual(Object.keys(first).sort(), [
      "acquiredAtEpochMillis",
      "catalogRevision",
      "itemId",
      "kind",
      "mediaIdentity",
      "ownerUid",
      "ownershipRevision",
    ])
    assert.equal((await firestore.collection(`users/${owner}/ownedItems`).get()).size, 1)
    assert.equal((await firestore.collection(`users/${owner}/inventoryOperations`).get()).size, 1)
    assert.equal(
      (await firestore.doc(`users/${owner}/inventoryState/current`).get()).get("generation"),
      1,
    )
  } finally {
    await deleteApp(app)
  }
})

test("Given existing ownership, when acquired under a new operation ID, then it returns the exact already-owned receipt", async () => {
  // Given
  const { app, firestore, store } = await fixture("inventory-duplicate")
  const owner = OwnerIdSchema.parse("inventory-duplicate-owner")
  await firestore.doc("inventoryCatalog/item-lamp").set(catalog("item-lamp"))
  await acquireInventoryItem(
    inventoryAuth({ uid: owner }),
    command(owner, "item-lamp", "first-operation-0001"),
    store,
  )
  try {
    // When
    const duplicate = await acquireInventoryItem(
      inventoryAuth({ uid: owner }),
      command(owner, "item-lamp", "second-operation-0001"),
      store,
    )

    // Then
    assert.equal(duplicate.kind, "already-owned")
    assert.deepEqual(Object.keys(duplicate).sort(), [
      "acquiredAtEpochMillis",
      "catalogRevision",
      "itemId",
      "kind",
      "mediaIdentity",
      "ownerUid",
      "ownershipRevision",
    ])
    assert.equal((await firestore.collection(`users/${owner}/ownedItems`).get()).size, 1)
  } finally {
    await deleteApp(app)
  }
})

test("Given one operation ID, when its command changes, then it rejects without another ownership write", async () => {
  // Given
  const { app, firestore, store } = await fixture("inventory-mismatch")
  const owner = OwnerIdSchema.parse("inventory-mismatch-owner")
  await Promise.all([
    firestore.doc("inventoryCatalog/item-lamp").set(catalog("item-lamp")),
    firestore.doc("inventoryCatalog/item-vintage-lamp").set(catalog("item-vintage-lamp")),
  ])
  await acquireInventoryItem(
    inventoryAuth({ uid: owner }),
    command(owner, "item-lamp", "mismatch-operation-0001"),
    store,
  )
  try {
    // When
    await assert.rejects(() =>
      acquireInventoryItem(
        inventoryAuth({ uid: owner }),
        command(owner, "item-vintage-lamp", "mismatch-operation-0001"),
        store,
      ),
    )

    // Then
    assert.equal((await firestore.collection(`users/${owner}/ownedItems`).get()).size, 1)
  } finally {
    await deleteApp(app)
  }
})

test("Given unavailable catalog or unmet condition, when acquiring, then it creates no ownership", async () => {
  // Given
  const { app, firestore, store } = await fixture("inventory-condition")
  const owner = OwnerIdSchema.parse("inventory-condition-owner")
  await Promise.all([
    firestore
      .doc("inventoryCatalog/draft-item")
      .set(catalog("draft-item", { publicationState: "DRAFT" })),
    firestore
      .doc("inventoryCatalog/item-christmas-tree")
      .set(catalog("item-christmas-tree", { acquisitionCondition: "registered-plant" })),
  ])
  try {
    // When
    await assert.rejects(() =>
      acquireInventoryItem(
        inventoryAuth({ uid: owner }),
        command(owner, "draft-item", "draft-operation-0001"),
        store,
      ),
    )
    const condition = await acquireInventoryItem(
      inventoryAuth({ uid: owner }),
      command(owner, "item-christmas-tree", "plant-operation-0001"),
      store,
    )

    // Then
    assert.deepEqual(condition, {
      kind: "condition-not-met",
      ownerUid: owner,
      itemId: "item-christmas-tree",
      catalogRevision: 3,
      condition: "registered-plant",
    })
    assert.equal((await firestore.collection(`users/${owner}/ownedItems`).get()).size, 0)
  } finally {
    await deleteApp(app)
  }
})

test("Given concurrent isolated owners, when acquiring the same item, then each gets only its own ownership", async () => {
  // Given
  const { app, firestore, store } = await fixture("inventory-isolation")
  const ownerA = OwnerIdSchema.parse("inventory-owner-a")
  const ownerB = OwnerIdSchema.parse("inventory-owner-b")
  await firestore.doc("inventoryCatalog/item-lamp").set(catalog("item-lamp"))
  try {
    // When
    const receipts = await Promise.all([
      acquireInventoryItem(
        inventoryAuth({ uid: ownerA }),
        command(ownerA, "item-lamp", "owner-a-operation-0001"),
        store,
      ),
      acquireInventoryItem(
        inventoryAuth({ uid: ownerB }),
        command(ownerB, "item-lamp", "owner-b-operation-0001"),
        store,
      ),
    ])

    // Then
    assert.deepEqual(receipts.map((receipt) => receipt.ownerUid).sort(), [ownerA, ownerB])
    assert.equal((await firestore.collection(`users/${ownerA}/ownedItems`).get()).size, 1)
    assert.equal((await firestore.collection(`users/${ownerB}/ownedItems`).get()).size, 1)
  } finally {
    await deleteApp(app)
  }
})
