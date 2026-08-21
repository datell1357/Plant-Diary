import { createHash } from "node:crypto";
import type { AuthContext } from "./contracts.js";

export type InventoryCategory = "BACKGROUND" | "FURNITURE" | "DECORATION";
export type InventoryAcquisitionCondition = "registered-plant";

export type InventoryMediaIdentity = Readonly<{
  path: string;
  sha256: string;
  byteSize: number;
  mimeType: "image/png" | "image/jpeg" | "image/webp";
  width: number;
  height: number;
  mediaRevision: number;
}>;

export type InventoryCatalogItem = Readonly<{
  itemId: string;
  name: string;
  description: string;
  category: InventoryCategory;
  mediaIdentity: InventoryMediaIdentity;
  acquisitionCondition: InventoryAcquisitionCondition | null;
  revision: number;
  updatedAtEpochMillis: number;
}>;

export type InventoryOwnedCatalogSnapshot = Readonly<{
  name: string;
  category: InventoryCategory;
  mediaIdentity: InventoryMediaIdentity;
  catalogRevision: number;
}>;

export type InventoryOwnedItem = Readonly<{
  itemId: string;
  acquiredAtEpochMillis: number;
  applied: boolean;
  revision: number;
  availability: "AVAILABLE" | "UNAVAILABLE";
  catalogSnapshot: InventoryOwnedCatalogSnapshot | null;
}>;

export const INVENTORY_CONTRACT_VERSION = 3 as const;

export type InventorySnapshot = Readonly<{
  contractVersion: typeof INVENTORY_CONTRACT_VERSION;
  ownerUid: string;
  catalog: readonly InventoryCatalogItem[];
  owned: readonly InventoryOwnedItem[];
  registeredPlantCount: number;
  loadedAtEpochMillis: number;
  partial: boolean;
  inventoryGeneration: number;
  snapshotHash: string;
}>;

export function inventorySnapshotHash(
  snapshot: Pick<InventorySnapshot, "ownerUid" | "catalog" | "owned" | "registeredPlantCount" | "partial">,
): string {
  const encoded = (value: string | null | undefined): string =>
    value === null || value === undefined ? "~" : Buffer.from(value, "utf8").toString("base64url");
  const catalog = [...snapshot.catalog]
    .sort((left, right) => left.itemId < right.itemId ? -1 : left.itemId > right.itemId ? 1 : 0)
    .map((item) => [
      "C", encoded(item.itemId), encoded(item.name), encoded(item.description), item.category,
      encoded(item.mediaIdentity.path), item.mediaIdentity.sha256,
      String(item.mediaIdentity.byteSize), item.mediaIdentity.mimeType,
      String(item.mediaIdentity.width), String(item.mediaIdentity.height),
      String(item.mediaIdentity.mediaRevision), item.acquisitionCondition ?? "~", String(item.revision),
      String(item.updatedAtEpochMillis),
    ].join("\t"));
  const owned = [...snapshot.owned]
    .sort((left, right) => left.itemId < right.itemId ? -1 : left.itemId > right.itemId ? 1 : 0)
    .map((item) => [
      "O", encoded(item.itemId), String(item.acquiredAtEpochMillis), item.applied ? "1" : "0",
      String(item.revision), item.availability, encoded(item.catalogSnapshot?.name),
      item.catalogSnapshot?.category ?? "~", encoded(item.catalogSnapshot?.mediaIdentity.path),
      item.catalogSnapshot?.mediaIdentity.sha256 ?? "~",
      item.catalogSnapshot === null ? "~" : String(item.catalogSnapshot.mediaIdentity.byteSize),
      item.catalogSnapshot?.mediaIdentity.mimeType ?? "~",
      item.catalogSnapshot === null ? "~" : String(item.catalogSnapshot.mediaIdentity.width),
      item.catalogSnapshot === null ? "~" : String(item.catalogSnapshot.mediaIdentity.height),
      item.catalogSnapshot === null ? "~" : String(item.catalogSnapshot.mediaIdentity.mediaRevision),
      item.catalogSnapshot === null ? "~" : String(item.catalogSnapshot.catalogRevision),
    ].join("\t"));
  const canonical = [
    "INVENTORY-SNAPSHOT-V3",
    encoded(snapshot.ownerUid),
    String(snapshot.registeredPlantCount),
    snapshot.partial ? "1" : "0",
    ...catalog,
    ...owned,
  ].join("\n");
  return createHash("sha256").update(canonical, "utf8").digest("hex");
}

export type InventoryAcquireCommand = Readonly<{
  ownerUid: string;
  itemId: string;
  expectedCatalogRevision: number;
  operationId: string;
  requestHash: string;
}>;

export type InventoryAcquireResult =
  | Readonly<{
      kind: "acquired";
      ownerUid: string;
      itemId: string;
      catalogRevision: number;
      ownershipRevision: number;
      acquiredAtEpochMillis: number;
      mediaIdentity: InventoryMediaIdentity;
    }>
  | Readonly<{
      kind: "already-owned";
      ownerUid: string;
      itemId: string;
      catalogRevision: number;
      ownershipRevision: number;
      acquiredAtEpochMillis: number;
      mediaIdentity: InventoryMediaIdentity;
    }>
  | Readonly<{
      kind: "condition-not-met";
      ownerUid: string;
      itemId: string;
      catalogRevision: number;
      condition: InventoryAcquisitionCondition;
    }>;

export interface InventoryStore {
  load(ownerUid: string): Promise<InventorySnapshot>;
  acquire(command: InventoryAcquireCommand): Promise<InventoryAcquireResult>;
}

export type InventoryErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "not-found"
  | "failed-precondition"
  | "data-loss";

export type InventoryErrorReason =
  | "INVALID_REQUEST"
  | "PERMISSION_DENIED"
  | "ITEM_UNAVAILABLE"
  | "CATALOG_CHANGED"
  | "IDEMPOTENCY_MISMATCH"
  | "MALFORMED_RESPONSE";

export class InventoryError extends Error {
  constructor(
    readonly code: InventoryErrorCode,
    message: string,
    readonly reason: InventoryErrorReason,
    readonly details?: Readonly<Record<string, string | number>>,
  ) {
    super(message);
    this.name = "InventoryError";
  }
}

const opaqueId = /^[A-Za-z0-9_-]{1,128}$/;
const operationId = /^[A-Za-z0-9_-]{8,128}$/;

function invalid(message: string, field: string): never {
  throw new InventoryError("invalid-argument", message, "INVALID_REQUEST", { field });
}

function record(value: unknown, field: string): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    invalid(`${field} must be an object`, field);
  }
  return Object.fromEntries(Object.entries(value as Readonly<Record<string, unknown>>));
}

function exactFields(value: Readonly<Record<string, unknown>>, expected: readonly string[], field: string): void {
  const actual = Object.keys(value).sort();
  const canonical = [...expected].sort();
  if (actual.length !== canonical.length || actual.some((name, index) => name !== canonical[index])) {
    invalid("Fields do not match the inventory contract", field);
  }
}

function requiredString(value: Readonly<Record<string, unknown>>, field: string): string {
  const candidate = value[field];
  if (typeof candidate !== "string" || candidate.length === 0) invalid(`${field} must be a non-empty string`, field);
  return candidate;
}

function safeRevision(value: Readonly<Record<string, unknown>>, field: string): number {
  const candidate = value[field];
  if (typeof candidate !== "number" || !Number.isSafeInteger(candidate) || candidate < 1 || candidate >= Number.MAX_SAFE_INTEGER) {
    invalid(`${field} must be a positive safe revision`, field);
  }
  return candidate;
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "string" || typeof value === "number") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const entries = Object.entries(value as Readonly<Record<string, unknown>>).sort(
    ([left], [right]) => left < right ? -1 : left > right ? 1 : 0,
  );
  return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`).join(",")}}`;
}

function authorize(auth: AuthContext | null, expectedOwnerUid: string): string {
  if (auth === null || !opaqueId.test(auth.uid)) {
    throw new InventoryError("unauthenticated", "Authentication is required", "PERMISSION_DENIED", { field: "auth" });
  }
  if (!opaqueId.test(expectedOwnerUid)) invalid("expectedOwnerUid must be path-safe", "expectedOwnerUid");
  if (expectedOwnerUid !== auth.uid) {
    throw new InventoryError("permission-denied", "Owner mismatch", "PERMISSION_DENIED", { field: "expectedOwnerUid" });
  }
  return auth.uid;
}

export async function executeLoadInventory(
  auth: AuthContext | null,
  input: unknown,
  store: InventoryStore,
): Promise<InventorySnapshot> {
  const request = record(input, "request");
  exactFields(request, ["expectedOwnerUid"], "request");
  const ownerUid = authorize(auth, requiredString(request, "expectedOwnerUid"));
  const snapshot = await store.load(ownerUid);
  if (snapshot.contractVersion !== INVENTORY_CONTRACT_VERSION) {
    throw new InventoryError("data-loss", "Stored inventory contract version is malformed", "MALFORMED_RESPONSE", { field: "contractVersion" });
  }
  if (snapshot.ownerUid !== ownerUid) {
    throw new InventoryError("data-loss", "Stored inventory owner is malformed", "MALFORMED_RESPONSE", { field: "ownerUid" });
  }
  if (!Number.isSafeInteger(snapshot.inventoryGeneration) || snapshot.inventoryGeneration < 1) {
    throw new InventoryError("data-loss", "Stored inventory generation is malformed", "MALFORMED_RESPONSE", { field: "inventoryGeneration" });
  }
  if (snapshot.snapshotHash !== inventorySnapshotHash(snapshot)) {
    throw new InventoryError("data-loss", "Stored inventory snapshot hash is malformed", "MALFORMED_RESPONSE", { field: "snapshotHash" });
  }
  return snapshot;
}

export async function executeAcquireInventoryItem(
  auth: AuthContext | null,
  input: unknown,
  store: InventoryStore,
): Promise<InventoryAcquireResult> {
  const request = record(input, "request");
  exactFields(
    request,
    ["expectedOwnerUid", "itemId", "expectedCatalogRevision", "operationId"],
    "request",
  );
  const ownerUid = authorize(auth, requiredString(request, "expectedOwnerUid"));
  const itemId = requiredString(request, "itemId");
  if (!opaqueId.test(itemId)) invalid("itemId must be path-safe", "itemId");
  const expectedCatalogRevision = safeRevision(request, "expectedCatalogRevision");
  const operation = requiredString(request, "operationId");
  if (!operationId.test(operation)) invalid("operationId must be path-safe", "operationId");
  const requestHash = createHash("sha256")
    .update(canonicalJson({ ownerUid, itemId, expectedCatalogRevision }), "utf8")
    .digest("hex");
  const result = await store.acquire({
    ownerUid,
    itemId,
    expectedCatalogRevision,
    operationId: operation,
    requestHash,
  });
  if (result.ownerUid !== ownerUid || result.itemId !== itemId) {
    throw new InventoryError("data-loss", "Acquisition receipt owner or item is malformed", "MALFORMED_RESPONSE");
  }
  return result;
}
