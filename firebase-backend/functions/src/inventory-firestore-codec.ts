import type { DocumentSnapshot, QueryDocumentSnapshot } from "firebase-admin/firestore"
import { z } from "zod"
import {
  type InventoryCatalogItem,
  InventoryCatalogItemObjectSchema,
  InventoryCatalogItemSchema,
  type InventoryOwnedItem,
  InventoryOwnedItemObjectSchema,
  InventoryOwnedItemSchema,
  type InventoryOwnerId,
  type InventoryReceipt,
  InventoryReceiptSchema,
} from "./inventory-contract.js"
import type { InventoryAcquireCommand } from "./inventory-service.js"

const CatalogStorageSchema = InventoryCatalogItemObjectSchema.omit({ itemId: true })
  .extend({ publicationState: z.enum(["PUBLISHED", "DRAFT"]), available: z.boolean() })
  .strict()
const OwnedStorageSchema = InventoryOwnedItemObjectSchema.omit({ availability: true })
  .extend({ ownerUid: z.string() })
  .strict()
const StateSchema = z
  .object({
    ownerUid: z.string(),
    generation: z.number().int().min(1),
    snapshotHash: z.string().regex(/^[a-f0-9]{64}$/),
  })
  .strict()
const SupportedCatalogItemIds = new Set([
  "item-christmas-tree",
  "item-green-wall",
  "item-succulent-pot",
  "item-lamp",
  "item-cozy-rug",
  "item-mini-shelf",
  "item-vintage-lamp",
  "item-small-rug",
  "item-cushion",
  "item-flower-stand",
  "item-autumn-frame",
  "item-chair",
  "item-window-frame",
  "item-wall-art",
])

const OperationSchema = z
  .object({
    ownerUid: z.string(),
    itemId: z.string(),
    expectedCatalogRevision: z.number().int().min(1),
    requestHash: z.string().regex(/^[a-f0-9]{64}$/),
    receipt: InventoryReceiptSchema,
  })
  .strict()

export type StoredOwnedItem = z.infer<typeof OwnedStorageSchema>
export type InventoryState = z.infer<typeof StateSchema>

export class InventoryStoreError extends Error {
  override readonly name = "InventoryStoreError"

  constructor(
    readonly code: "data-loss" | "not-found" | "failed-precondition",
    message: string,
  ) {
    super(message)
  }
}

function stored<T>(schema: z.ZodType<T>, value: unknown, field: string): T {
  try {
    return schema.parse(value)
  } catch (error: unknown) {
    if (error instanceof z.ZodError)
      throw new InventoryStoreError("data-loss", `Stored ${field} is malformed`)
    throw error
  }
}

export function inventoryCatalog(
  documents: readonly QueryDocumentSnapshot[],
): readonly InventoryCatalogItem[] {
  return documents
    .map((document) => {
      const { publicationState, available, ...item } = stored(
        CatalogStorageSchema,
        document.data(),
        "catalog item",
      )
      if (publicationState !== "PUBLISHED" || !available) return null
      if (
        !SupportedCatalogItemIds.has(document.id) ||
        !validBundledAsset(document.id, item.mediaIdentity)
      ) {
        throw new InventoryStoreError("data-loss", "Catalog item media is unsupported")
      }
      return stored(InventoryCatalogItemSchema, { itemId: document.id, ...item }, "catalog item")
    })
    .filter((item): item is InventoryCatalogItem => item !== null)
    .sort((left, right) => left.itemId.localeCompare(right.itemId))
}

function validBundledAsset(itemId: string, media: InventoryCatalogItem["mediaIdentity"]): boolean {
  const suffix =
    media.mimeType === "image/png" ? "png" : media.mimeType === "image/jpeg" ? "jpg" : "webp"
  return media.path === `catalog-assets/${itemId}/${media.sha256}.${suffix}`
}

export function inventoryOwned(
  documents: readonly QueryDocumentSnapshot[],
  ownerUid: InventoryOwnerId,
): readonly InventoryOwnedItem[] {
  return documents
    .map((document) => {
      const { ownerUid: storedOwnerUid, ...owned } = stored(
        OwnedStorageSchema,
        document.data(),
        "ownership",
      )
      if (storedOwnerUid !== ownerUid)
        throw new InventoryStoreError("data-loss", "Stored ownership owner is malformed")
      return stored(
        InventoryOwnedItemSchema,
        { ...owned, itemId: document.id, availability: "UNAVAILABLE" },
        "ownership",
      )
    })
    .sort((left, right) => left.itemId.localeCompare(right.itemId))
}

export function inventoryState(
  document: DocumentSnapshot,
  ownerUid: InventoryOwnerId,
): InventoryState | null {
  if (!document.exists) return null
  const state = stored(StateSchema, document.data(), "inventory state")
  if (state.ownerUid !== ownerUid)
    throw new InventoryStoreError("data-loss", "Stored inventory state owner is malformed")
  return state
}

export function replayInventoryOperation(
  document: DocumentSnapshot,
  command: InventoryAcquireCommand,
): InventoryReceipt {
  const operation = stored(OperationSchema, document.data(), "inventory operation")
  if (
    operation.ownerUid !== command.ownerUid ||
    operation.itemId !== command.itemId ||
    operation.expectedCatalogRevision !== command.expectedCatalogRevision ||
    operation.requestHash !== command.requestHash
  ) {
    throw new InventoryStoreError(
      "failed-precondition",
      "Operation ID was already used for another acquisition",
    )
  }
  return operation.receipt
}

export function wireOwned(ownership: StoredOwnedItem): InventoryOwnedItem {
  const { ownerUid, ...owned } = ownership
  void ownerUid
  return InventoryOwnedItemSchema.parse({ ...owned, availability: "AVAILABLE" })
}
