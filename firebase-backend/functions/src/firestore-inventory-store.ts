import type { Firestore } from "firebase-admin/firestore"
import {
  catalogSnapshot,
  type InventoryCatalogItem,
  type InventoryOwnedItem,
  InventoryOwnedItemSchema,
  type InventoryOwnerId,
  type InventoryReceipt,
  type InventorySnapshot,
  inventorySnapshotHash,
} from "./inventory-contract.js"
import {
  type InventoryState,
  InventoryStoreError,
  inventoryCatalog,
  inventoryOwned,
  inventoryState,
  replayInventoryOperation,
  type StoredOwnedItem,
  wireOwned,
} from "./inventory-firestore-codec.js"
import type { InventoryAcquireCommand, InventoryStore } from "./inventory-service.js"

export { InventoryStoreError } from "./inventory-firestore-codec.js"

export class FirestoreInventoryStore implements InventoryStore {
  constructor(
    private readonly firestore: Firestore,
    private readonly nowEpochMillis: () => number = Date.now,
  ) {}

  async load(ownerUid: InventoryOwnerId): Promise<InventorySnapshot> {
    return this.firestore.runTransaction(
      async (transaction) => {
        const [catalogDocuments, ownedDocuments, stateDocument, plants] = await Promise.all([
          transaction.get(this.firestore.collection("inventoryCatalog")),
          transaction.get(this.firestore.collection(`users/${ownerUid}/ownedItems`)),
          transaction.get(this.firestore.doc(`users/${ownerUid}/inventoryState/current`)),
          transaction.get(this.firestore.collection(`users/${ownerUid}/personalPlants`)),
        ])
        const snapshot = this.snapshot(
          ownerUid,
          inventoryCatalog(catalogDocuments.docs),
          inventoryOwned(ownedDocuments.docs, ownerUid),
          plants.size,
          inventoryState(stateDocument, ownerUid),
        )
        this.writeState(transaction, ownerUid, snapshot)
        return snapshot
      },
      { maxAttempts: 5 },
    )
  }

  async acquire(command: InventoryAcquireCommand): Promise<InventoryReceipt> {
    return this.firestore.runTransaction(
      async (transaction) => {
        const root = `users/${command.ownerUid}`
        const operationDocument = await transaction.get(
          this.firestore.doc(`${root}/inventoryOperations/${command.operationId}`),
        )
        if (operationDocument.exists) return replayInventoryOperation(operationDocument, command)

        const [catalogDocuments, ownedDocuments, stateDocument, plants] = await Promise.all([
          transaction.get(this.firestore.collection("inventoryCatalog")),
          transaction.get(this.firestore.collection(`${root}/ownedItems`)),
          transaction.get(this.firestore.doc(`${root}/inventoryState/current`)),
          transaction.get(this.firestore.collection(`${root}/personalPlants`)),
        ])
        const catalog = inventoryCatalog(catalogDocuments.docs)
        const selected = catalog.find((item) => item.itemId === command.itemId)
        if (selected === undefined)
          throw new InventoryStoreError("not-found", "Catalog item is unavailable")
        if (selected.revision !== command.expectedCatalogRevision) {
          throw new InventoryStoreError("failed-precondition", "Catalog item changed")
        }
        const owned = inventoryOwned(ownedDocuments.docs, command.ownerUid)
        const receipt = this.receipt(
          command,
          selected,
          owned.find((item) => item.itemId === command.itemId),
          plants.size,
        )
        const acquired = receipt.kind === "acquired" ? this.newOwned(receipt, selected) : null
        const snapshot = this.snapshot(
          command.ownerUid,
          catalog,
          acquired === null ? owned : [...owned, wireOwned(acquired)],
          plants.size,
          inventoryState(stateDocument, command.ownerUid),
        )
        if (acquired !== null) {
          transaction.create(this.firestore.doc(`${root}/ownedItems/${command.itemId}`), acquired)
        }
        this.writeState(transaction, command.ownerUid, snapshot)
        transaction.create(
          this.firestore.doc(`${root}/inventoryOperations/${command.operationId}`),
          {
            ownerUid: command.ownerUid,
            itemId: command.itemId,
            expectedCatalogRevision: command.expectedCatalogRevision,
            requestHash: command.requestHash,
            receipt,
          },
        )
        return receipt
      },
      { maxAttempts: 5 },
    )
  }

  private snapshot(
    ownerUid: InventoryOwnerId,
    catalog: readonly InventoryCatalogItem[],
    owned: readonly InventoryOwnedItem[],
    registeredPlantCount: number,
    state: InventoryState | null,
  ): InventorySnapshot {
    const catalogIds = new Set(catalog.map((item) => item.itemId))
    const normalizedOwned = owned.map((item) =>
      InventoryOwnedItemSchema.parse({
        ...item,
        availability: catalogIds.has(item.itemId) ? "AVAILABLE" : "UNAVAILABLE",
      }),
    )
    const partial = normalizedOwned.some((item) => item.availability === "UNAVAILABLE")
    const snapshotHash = inventorySnapshotHash({
      ownerUid,
      catalog,
      owned: normalizedOwned,
      registeredPlantCount,
      partial,
    })
    const inventoryGeneration =
      state?.snapshotHash === snapshotHash ? state.generation : (state?.generation ?? 0) + 1
    return {
      contractVersion: 3,
      ownerUid,
      catalog,
      owned: normalizedOwned,
      registeredPlantCount,
      loadedAtEpochMillis: this.nowEpochMillis(),
      partial,
      inventoryGeneration,
      snapshotHash,
    }
  }

  private receipt(
    command: InventoryAcquireCommand,
    catalog: InventoryCatalogItem,
    owned: InventoryOwnedItem | undefined,
    registeredPlantCount: number,
  ): InventoryReceipt {
    if (owned !== undefined) {
      return {
        kind: "already-owned",
        ownerUid: command.ownerUid,
        itemId: catalog.itemId,
        catalogRevision: catalog.revision,
        ownershipRevision: owned.revision,
        acquiredAtEpochMillis: owned.acquiredAtEpochMillis,
        mediaIdentity: owned.catalogSnapshot?.mediaIdentity ?? catalog.mediaIdentity,
      }
    }
    if (catalog.acquisitionCondition === "registered-plant" && registeredPlantCount === 0) {
      return {
        kind: "condition-not-met",
        ownerUid: command.ownerUid,
        itemId: catalog.itemId,
        catalogRevision: catalog.revision,
        condition: "registered-plant",
      }
    }
    return {
      kind: "acquired",
      ownerUid: command.ownerUid,
      itemId: catalog.itemId,
      catalogRevision: catalog.revision,
      ownershipRevision: 1,
      acquiredAtEpochMillis: this.nowEpochMillis(),
      mediaIdentity: catalog.mediaIdentity,
    }
  }

  private newOwned(
    receipt: Extract<InventoryReceipt, { readonly kind: "acquired" }>,
    item: InventoryCatalogItem,
  ): StoredOwnedItem {
    return {
      ownerUid: receipt.ownerUid,
      itemId: receipt.itemId,
      acquiredAtEpochMillis: receipt.acquiredAtEpochMillis,
      applied: false,
      revision: receipt.ownershipRevision,
      catalogSnapshot: catalogSnapshot(item),
    }
  }

  private writeState(
    transaction: FirebaseFirestore.Transaction,
    ownerUid: InventoryOwnerId,
    snapshot: InventorySnapshot,
  ): void {
    transaction.set(this.firestore.doc(`users/${ownerUid}/inventoryState/current`), {
      ownerUid,
      generation: snapshot.inventoryGeneration,
      snapshotHash: snapshot.snapshotHash,
    })
  }
}
