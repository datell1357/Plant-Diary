import { createHash } from "node:crypto"
import { z } from "zod"

export const INVENTORY_CONTRACT_VERSION = 3 as const

const OpaqueIdSchema = z.string().regex(/^[A-Za-z0-9_-]{1,128}$/)
export const OwnerIdSchema = OpaqueIdSchema.brand("InventoryOwnerId")
export type InventoryOwnerId = z.infer<typeof OwnerIdSchema>

const PositiveIntegerSchema = z.number().int().min(1).max(Number.MAX_SAFE_INTEGER)
const EpochMillisSchema = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER)
const HashSchema = z.string().regex(/^[a-f0-9]{64}$/)
const ItemIdSchema = OpaqueIdSchema.brand("InventoryItemId")
const OperationIdSchema = z
  .string()
  .regex(/^[A-Za-z0-9_-]{8,128}$/)
  .brand("InventoryOperationId")

const MediaIdentitySchema = z
  .object({
    path: z.string().min(1).max(1024),
    sha256: HashSchema,
    byteSize: PositiveIntegerSchema.max(8 * 1024 * 1024),
    mimeType: z.enum(["image/png", "image/jpeg", "image/webp"]),
    width: PositiveIntegerSchema.max(32_768),
    height: PositiveIntegerSchema.max(32_768),
    mediaRevision: PositiveIntegerSchema,
  })
  .strict()
  .readonly()
export type InventoryMediaIdentity = z.infer<typeof MediaIdentitySchema>

const CategorySchema = z.enum(["BACKGROUND", "FURNITURE", "DECORATION"])
const ConditionSchema = z.literal("registered-plant")

export const InventoryCatalogItemObjectSchema = z
  .object({
    itemId: ItemIdSchema,
    name: z.string().min(1).max(100),
    description: z.string().min(1).max(500),
    category: CategorySchema,
    mediaIdentity: MediaIdentitySchema,
    acquisitionCondition: ConditionSchema.nullable(),
    revision: PositiveIntegerSchema,
    updatedAtEpochMillis: EpochMillisSchema,
  })
  .strict()
export const InventoryCatalogItemSchema = InventoryCatalogItemObjectSchema.readonly()
export type InventoryCatalogItem = z.infer<typeof InventoryCatalogItemSchema>

const OwnedCatalogSnapshotSchema = z
  .object({
    name: z.string().min(1).max(100),
    category: CategorySchema,
    mediaIdentity: MediaIdentitySchema,
    catalogRevision: PositiveIntegerSchema,
  })
  .strict()
  .readonly()
export type InventoryOwnedCatalogSnapshot = z.infer<typeof OwnedCatalogSnapshotSchema>

export const InventoryOwnedItemObjectSchema = z
  .object({
    itemId: ItemIdSchema,
    acquiredAtEpochMillis: EpochMillisSchema,
    applied: z.boolean(),
    revision: PositiveIntegerSchema,
    availability: z.enum(["AVAILABLE", "UNAVAILABLE"]),
    catalogSnapshot: OwnedCatalogSnapshotSchema.nullable(),
  })
  .strict()
export const InventoryOwnedItemSchema = InventoryOwnedItemObjectSchema.readonly()
export type InventoryOwnedItem = z.infer<typeof InventoryOwnedItemSchema>

export const InventorySnapshotSchema = z
  .object({
    contractVersion: z.literal(INVENTORY_CONTRACT_VERSION),
    ownerUid: OwnerIdSchema,
    catalog: z.array(InventoryCatalogItemSchema).max(100).readonly(),
    owned: z.array(InventoryOwnedItemSchema).max(100).readonly(),
    registeredPlantCount: z.number().int().min(0).max(200),
    loadedAtEpochMillis: EpochMillisSchema,
    partial: z.boolean(),
    inventoryGeneration: PositiveIntegerSchema,
    snapshotHash: HashSchema,
  })
  .strict()
  .readonly()
export type InventorySnapshot = z.infer<typeof InventorySnapshotSchema>

export const LoadInventoryInputSchema = z
  .object({ expectedOwnerUid: OwnerIdSchema })
  .strict()
  .readonly()
export const AcquireInventoryInputSchema = z
  .object({
    expectedOwnerUid: OwnerIdSchema,
    itemId: ItemIdSchema,
    expectedCatalogRevision: PositiveIntegerSchema,
    operationId: OperationIdSchema,
  })
  .strict()
  .readonly()
export type AcquireInventoryInput = z.infer<typeof AcquireInventoryInputSchema>

const ReceiptBaseSchema = z.object({
  ownerUid: OwnerIdSchema,
  itemId: ItemIdSchema,
  catalogRevision: PositiveIntegerSchema,
})
export const InventoryReceiptSchema = z.discriminatedUnion("kind", [
  ReceiptBaseSchema.extend({
    kind: z.literal("acquired"),
    ownershipRevision: PositiveIntegerSchema,
    acquiredAtEpochMillis: EpochMillisSchema,
    mediaIdentity: MediaIdentitySchema,
  }).strict(),
  ReceiptBaseSchema.extend({
    kind: z.literal("already-owned"),
    ownershipRevision: PositiveIntegerSchema,
    acquiredAtEpochMillis: EpochMillisSchema,
    mediaIdentity: MediaIdentitySchema,
  }).strict(),
  ReceiptBaseSchema.extend({
    kind: z.literal("condition-not-met"),
    condition: ConditionSchema,
  }).strict(),
])
export type InventoryReceipt = z.infer<typeof InventoryReceiptSchema>

export type InventoryErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "not-found"
  | "failed-precondition"
  | "data-loss"

export class InventoryError extends Error {
  override readonly name = "InventoryError"

  constructor(
    readonly code: InventoryErrorCode,
    message: string,
  ) {
    super(message)
  }
}

export function inventorySnapshotHash(
  snapshot: Pick<
    InventorySnapshot,
    "ownerUid" | "catalog" | "owned" | "registeredPlantCount" | "partial"
  >,
): string {
  const encoded = (value: string | null): string =>
    value === null ? "~" : Buffer.from(value, "utf8").toString("base64url")
  const catalog = [...snapshot.catalog]
    .sort((left, right) => left.itemId.localeCompare(right.itemId))
    .map((item) =>
      [
        "C",
        encoded(item.itemId),
        encoded(item.name),
        encoded(item.description),
        item.category,
        encoded(item.mediaIdentity.path),
        item.mediaIdentity.sha256,
        String(item.mediaIdentity.byteSize),
        item.mediaIdentity.mimeType,
        String(item.mediaIdentity.width),
        String(item.mediaIdentity.height),
        String(item.mediaIdentity.mediaRevision),
        item.acquisitionCondition ?? "~",
        String(item.revision),
        String(item.updatedAtEpochMillis),
      ].join("\t"),
    )
  const owned = [...snapshot.owned]
    .sort((left, right) => left.itemId.localeCompare(right.itemId))
    .map((item) =>
      [
        "O",
        encoded(item.itemId),
        String(item.acquiredAtEpochMillis),
        String(item.revision),
        item.availability,
        encoded(item.catalogSnapshot?.name ?? null),
        item.catalogSnapshot?.category ?? "~",
        encoded(item.catalogSnapshot?.mediaIdentity.path ?? null),
        item.catalogSnapshot?.mediaIdentity.sha256 ?? "~",
        item.catalogSnapshot === null ? "~" : String(item.catalogSnapshot.mediaIdentity.byteSize),
        item.catalogSnapshot?.mediaIdentity.mimeType ?? "~",
        item.catalogSnapshot === null ? "~" : String(item.catalogSnapshot.mediaIdentity.width),
        item.catalogSnapshot === null ? "~" : String(item.catalogSnapshot.mediaIdentity.height),
        item.catalogSnapshot === null
          ? "~"
          : String(item.catalogSnapshot.mediaIdentity.mediaRevision),
        item.catalogSnapshot === null ? "~" : String(item.catalogSnapshot.catalogRevision),
      ].join("\t"),
    )
  return createHash("sha256")
    .update(
      [
        "INVENTORY-SNAPSHOT-V3",
        encoded(snapshot.ownerUid),
        String(snapshot.registeredPlantCount),
        snapshot.partial ? "1" : "0",
        ...catalog,
        ...owned,
      ].join("\n"),
      "utf8",
    )
    .digest("hex")
}

export function catalogSnapshot(item: InventoryCatalogItem): InventoryOwnedCatalogSnapshot {
  return {
    name: item.name,
    category: item.category,
    mediaIdentity: item.mediaIdentity,
    catalogRevision: item.revision,
  }
}
