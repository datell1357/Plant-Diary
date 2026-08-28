import { createHash } from "node:crypto"
import { z } from "zod"
import {
  AcquireInventoryInputSchema,
  InventoryError,
  type InventoryErrorCode,
  type InventoryOwnerId,
  type InventoryReceipt,
  type InventorySnapshot,
  LoadInventoryInputSchema,
  OwnerIdSchema,
} from "./inventory-contract.js"

export type InventoryAuth = Readonly<{ uid: InventoryOwnerId }>

export type InventoryAcquireCommand = Readonly<{
  ownerUid: InventoryOwnerId
  itemId: InventoryReceipt["itemId"]
  expectedCatalogRevision: number
  operationId: string
  requestHash: string
}>

export interface InventoryStore {
  load(ownerUid: InventoryOwnerId): Promise<InventorySnapshot>
  acquire(command: InventoryAcquireCommand): Promise<InventoryReceipt>
}

function parseBoundary<T>(schema: z.ZodType<T>, input: unknown): T {
  try {
    return schema.parse(input)
  } catch (error: unknown) {
    if (error instanceof z.ZodError)
      throw new InventoryError("invalid-argument", "Payload does not match inventory v3")
    throw error
  }
}

function requireOwner(
  auth: InventoryAuth | null,
  expectedOwnerUid: InventoryOwnerId,
): InventoryOwnerId {
  if (auth === null) throw new InventoryError("unauthenticated", "Sign-in is required")
  if (auth.uid !== expectedOwnerUid)
    throw new InventoryError("permission-denied", "Owner does not match authentication")
  return auth.uid
}

export function inventoryAuth(auth: Readonly<{ uid: string }> | undefined): InventoryAuth | null {
  if (auth === undefined) return null
  try {
    return { uid: OwnerIdSchema.parse(auth.uid) }
  } catch (error: unknown) {
    if (error instanceof z.ZodError) return null
    throw error
  }
}

export async function loadInventory(
  auth: InventoryAuth | null,
  input: unknown,
  store: InventoryStore,
): Promise<InventorySnapshot> {
  if (auth === null) throw new InventoryError("unauthenticated", "Sign-in is required")
  const request = parseBoundary(LoadInventoryInputSchema, input)
  return store.load(requireOwner(auth, request.expectedOwnerUid))
}

export async function acquireInventoryItem(
  auth: InventoryAuth | null,
  input: unknown,
  store: InventoryStore,
): Promise<InventoryReceipt> {
  if (auth === null) throw new InventoryError("unauthenticated", "Sign-in is required")
  const request = parseBoundary(AcquireInventoryInputSchema, input)
  const ownerUid = requireOwner(auth, request.expectedOwnerUid)
  return store.acquire({
    ownerUid,
    itemId: request.itemId,
    expectedCatalogRevision: request.expectedCatalogRevision,
    operationId: request.operationId,
    requestHash: createHash("sha256")
      .update(
        JSON.stringify({
          expectedCatalogRevision: request.expectedCatalogRevision,
          itemId: request.itemId,
          ownerUid,
        }),
        "utf8",
      )
      .digest("hex"),
  })
}

export function inventoryHttpsCode(error: InventoryError): InventoryErrorCode {
  return error.code
}
