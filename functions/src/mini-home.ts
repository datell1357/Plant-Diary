import { createHash } from "node:crypto";
import type { AuthContext } from "./contracts.js";

export type MiniHomeErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition"
  | "data-loss";

export type MiniHomeErrorReason =
  | "UNAVAILABLE_ENTITY"
  | "OUTBOX_MISMATCH"
  | "PAYLOAD_MISMATCH"
  | "REVISION_CONFLICT"
  | "INVALID_REQUEST"
  | "PERMISSION_DENIED"
  | "MALFORMED_RESPONSE";

export type MiniHomeErrorDetails = Readonly<{
  field?: string;
  committedOperationId?: string;
  committedExpectedRevision?: number;
  committedRevision?: number;
  committedPayloadHash?: string;
}>;

export class MiniHomeError extends Error {
  constructor(
    readonly code: MiniHomeErrorCode,
    message: string,
    readonly reason?: MiniHomeErrorReason,
    readonly details?: MiniHomeErrorDetails,
  ) {
    super(message);
    this.name = "MiniHomeError";
  }
}

export type MiniHomePlacementCommand = Readonly<{
  placementId: string;
  plantId: string | null;
  itemId: string | null;
  normalizedX: number;
  normalizedY: number;
  zIndex: number;
}>;

export type MiniHomeLayoutCommand = Readonly<{
  ownerUid: string;
  miniHomeId: string;
  expectedRevision: number;
  idempotencyKey: string;
  requestHash: string;
  name: string;
  placements: readonly MiniHomePlacementCommand[];
}>;

export type MiniHomeSaveResult =
  | Readonly<{ kind: "applied"; revision: number }>
  | Readonly<{ kind: "duplicate"; revision: number }>
  | Readonly<{ kind: "conflict"; actualRevision: number }>;

export interface MiniHomeLayoutStore {
  save(command: MiniHomeLayoutCommand): Promise<MiniHomeSaveResult>;
}

export type MiniHomeAuthoritativePlacement = Readonly<{
  placementId: string;
  ownerUid: string;
  miniHomeId: string;
  layoutRevision: number;
  plantId: string | null;
  itemId: string | null;
  normalizedX: number;
  normalizedY: number;
  zIndex: number;
  revision: number;
  expectedRevision: number;
  idempotencyKey: string;
  updatedAtEpochMillis: number;
}>;

export type MiniHomeAuthoritativeLayout = Readonly<{
  ownerUid: string;
  generation: number;
  miniHomeId: string;
  name: string;
  placedPlantCount: number;
  revision: number;
  expectedRevision: number;
  idempotencyKey: string;
  requestHash: string;
  updatedAtEpochMillis: number;
  placements: readonly MiniHomeAuthoritativePlacement[];
}>;

export type MiniHomeAuthoritativeRead =
  | Readonly<{ kind: "missing"; ownerUid: string; generation: number; tombstoneId: string; updatedAtEpochMillis: number }>
  | Readonly<{ kind: "present"; layout: MiniHomeAuthoritativeLayout }>;

export interface MiniHomeLayoutReader {
  load(ownerUid: string): Promise<MiniHomeAuthoritativeRead>;
}

export type MiniHomeLoadResult =
  | Readonly<{ kind: "missing"; ownerUid: string; generation: number; tombstoneId: string; updatedAtEpochMillis: number }>
  | Readonly<MiniHomeAuthoritativeLayout & { kind: "present"; placementCount: number }>;

export type MiniHomeDeleteCommand = Readonly<{
  ownerUid: string;
  expectedGeneration: number;
  tombstoneId: string;
}>;

export interface MiniHomeLayoutDeleter {
  delete(command: MiniHomeDeleteCommand): Promise<Readonly<{ kind: "deleted"; generation: number; tombstoneId: string }>>;
}

const opaqueId = /^[A-Za-z0-9_-]{1,128}$/;
const operationId = /^[A-Za-z0-9_-]{8,128}$/;
const columns = 5;
const rows = 4;
const maximumPlacements = columns * rows;
const maximumNameCodePoints = 100;

/** Unicode White_Space property pinned explicitly for Android/TypeScript parity. */
export const miniHomeUnicodeWhiteSpaceCodePoints: ReadonlySet<number> = new Set([
  0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0, 0x1680,
  0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008,
  0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
]);

/** UAX #9 directional formatting controls forbidden inside display names. */
export const miniHomeBidiControlCodePoints: ReadonlySet<number> = new Set([
  0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
  0x2066, 0x2067, 0x2068, 0x2069,
]);

export function recoverLegacyMiniHomeName(value: unknown): string | null {
  if (typeof value !== "string" || value.length === 0) return null;
  const canonicalName = value.normalize("NFC");
  const characters = [...canonicalName];
  const codePoints = characters.map((character) => character.codePointAt(0) as number);
  const hasUnpairedSurrogate = characters.some((character) =>
    character.length === 1 && character.charCodeAt(0) >= 0xD800 && character.charCodeAt(0) <= 0xDFFF
  );
  const hasControl = codePoints.some((codePoint) =>
    (codePoint >= 0x0000 && codePoint <= 0x001F) ||
    (codePoint >= 0x007F && codePoint <= 0x009F)
  );
  if (
    hasUnpairedSurrogate ||
    codePoints.length < 1 ||
    codePoints.length > maximumNameCodePoints ||
    miniHomeUnicodeWhiteSpaceCodePoints.has(codePoints[0] as number) ||
    miniHomeUnicodeWhiteSpaceCodePoints.has(codePoints[codePoints.length - 1] as number) ||
    hasControl ||
    codePoints.some((codePoint) => miniHomeBidiControlCodePoints.has(codePoint))
  ) {
    return null;
  }
  return canonicalName;
}

function invalid(message: string, field: string): never {
  throw new MiniHomeError("invalid-argument", message, "INVALID_REQUEST", { field });
}

function denied(message: string, field: string): never {
  throw new MiniHomeError("permission-denied", message, "PERMISSION_DENIED", { field });
}

function record(value: unknown, label: string): Readonly<Record<string, unknown>> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    invalid(`${label} must be an object`, label);
  }
  return Object.fromEntries(Object.entries(value as Record<string, unknown>));
}

function exactFields(
  value: Readonly<Record<string, unknown>>,
  fields: readonly string[],
  field: string,
): void {
  const actual = Object.keys(value).sort();
  const expected = [...fields].sort();
  if (actual.length !== expected.length || actual.some((item, index) => item !== expected[index])) {
    invalid("Fields do not match the mini-home contract", field);
  }
}

function requiredString(value: Readonly<Record<string, unknown>>, field: string): string {
  const candidate = value[field];
  if (typeof candidate !== "string" || candidate.length === 0) {
    invalid(`${field} must be a non-empty string`, field);
  }
  return candidate;
}

function optionalOpaque(value: Readonly<Record<string, unknown>>, field: string): string | null {
  const candidate = value[field];
  if (candidate === null) return null;
  if (typeof candidate !== "string" || !opaqueId.test(candidate)) {
    invalid(`${field} must be null or path-safe`, field);
  }
  return candidate;
}

function finiteNumber(value: Readonly<Record<string, unknown>>, field: string): number {
  const candidate = value[field];
  if (typeof candidate !== "number" || !Number.isFinite(candidate)) {
    invalid(`${field} must be finite`, field);
  }
  return candidate;
}

function integer(value: Readonly<Record<string, unknown>>, field: string): number {
  const candidate = finiteNumber(value, field);
  if (!Number.isSafeInteger(candidate)) {
    invalid(`${field} must be an integer`, field);
  }
  return candidate;
}

function snappedCell(x: number, y: number): string {
  if (x < 0 || x > 1 || y < 0 || y > 1) {
    invalid("Placement is outside the room", "coordinates");
  }
  const column = Math.round(x * columns - 0.5);
  const row = Math.round(y * rows - 0.5);
  if (column < 0 || column >= columns || row < 0 || row >= rows) {
    invalid("Placement does not map to the room grid", "coordinates");
  }
  const expectedX = (column + 0.5) / columns;
  const expectedY = (row + 0.5) / rows;
  if (Math.abs(expectedX - x) >= 1e-9 || Math.abs(expectedY - y) >= 1e-9) {
    invalid("Placement coordinates must be snapped to the room grid", "coordinates");
  }
  return `${column}:${row}`;
}

function placement(value: unknown): MiniHomePlacementCommand {
  const source = record(value, "placement");
  exactFields(source, ["placementId", "plantId", "itemId", "normalizedX", "normalizedY", "zIndex"], "placement");
  const placementId = requiredString(source, "placementId");
  if (!opaqueId.test(placementId)) invalid("placementId must be path-safe", "placementId");
  const plantId = optionalOpaque(source, "plantId");
  const itemId = optionalOpaque(source, "itemId");
  if ((plantId === null) === (itemId === null)) {
    invalid("A placement must reference exactly one entity", "target");
  }
  return {
    placementId,
    plantId,
    itemId,
    normalizedX: finiteNumber(source, "normalizedX"),
    normalizedY: finiteNumber(source, "normalizedY"),
    zIndex: integer(source, "zIndex"),
  };
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "string" || typeof value === "number") {
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const entries = Object.entries(value as Readonly<Record<string, unknown>>).sort(([left], [right]) => left.localeCompare(right));
  return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`).join(",")}}`;
}

export async function executeLoadMiniHomeLayout(
  auth: AuthContext | null,
  input: unknown,
  reader: MiniHomeLayoutReader,
): Promise<MiniHomeLoadResult> {
  if (auth === null) {
    throw new MiniHomeError("unauthenticated", "Authentication is required", "PERMISSION_DENIED", { field: "auth" });
  }
  const request = record(input, "request");
  exactFields(request, ["expectedOwnerUid"], "request");
  const expectedOwnerUid = requiredString(request, "expectedOwnerUid");
  if (!opaqueId.test(expectedOwnerUid)) invalid("expectedOwnerUid must be path-safe", "expectedOwnerUid");
  if (expectedOwnerUid !== auth.uid) denied("Owner mismatch", "expectedOwnerUid");
  const authoritative = await reader.load(auth.uid);
  if (authoritative.kind === "missing") return authoritative;
  const layout = authoritative.layout;
  if (layout.ownerUid !== auth.uid) {
    throw new MiniHomeError("data-loss", "Stored mini-home owner is malformed", "MALFORMED_RESPONSE", { field: "ownerUid" });
  }
  return { kind: "present", ...layout, placementCount: layout.placements.length };
}

export async function executeDeleteMiniHomeLayout(
  auth: AuthContext | null,
  input: unknown,
  deleter: MiniHomeLayoutDeleter,
): Promise<Readonly<{ kind: "deleted"; generation: number; tombstoneId: string }>> {
  if (auth === null) {
    throw new MiniHomeError("unauthenticated", "Authentication is required", "PERMISSION_DENIED", { field: "auth" });
  }
  const request = record(input, "request");
  exactFields(request, ["expectedOwnerUid", "expectedGeneration", "tombstoneId"], "request");
  const expectedOwnerUid = requiredString(request, "expectedOwnerUid");
  if (!opaqueId.test(expectedOwnerUid)) invalid("expectedOwnerUid must be path-safe", "expectedOwnerUid");
  if (expectedOwnerUid !== auth.uid) denied("Owner mismatch", "expectedOwnerUid");
  const expectedGeneration = integer(request, "expectedGeneration");
  if (expectedGeneration < 0 || expectedGeneration >= Number.MAX_SAFE_INTEGER) {
    invalid("expectedGeneration is outside the supported range", "expectedGeneration");
  }
  const tombstoneId = requiredString(request, "tombstoneId");
  if (!operationId.test(tombstoneId)) invalid("tombstoneId must be path-safe", "tombstoneId");
  return deleter.delete({ ownerUid: auth.uid, expectedGeneration, tombstoneId });
}

export async function executeSaveMiniHomeLayout(
  auth: AuthContext | null,
  input: unknown,
  store: MiniHomeLayoutStore,
): Promise<MiniHomeSaveResult> {
  if (auth === null) {
    throw new MiniHomeError("unauthenticated", "Authentication is required", "PERMISSION_DENIED", { field: "auth" });
  }
  const request = record(input, "request");
  exactFields(
    request,
    ["expectedOwnerUid", "miniHomeId", "expectedRevision", "idempotencyKey", "name", "placements"],
    "request",
  );
  const expectedOwnerUid = requiredString(request, "expectedOwnerUid");
  if (!opaqueId.test(expectedOwnerUid)) {
    invalid("expectedOwnerUid must be path-safe", "expectedOwnerUid");
  }
  if (expectedOwnerUid !== auth.uid) denied("Owner mismatch", "expectedOwnerUid");
  const miniHomeId = requiredString(request, "miniHomeId");
  if (!opaqueId.test(miniHomeId)) invalid("miniHomeId must be path-safe", "miniHomeId");
  const idempotencyKey = requiredString(request, "idempotencyKey");
  if (!operationId.test(idempotencyKey)) invalid("idempotencyKey must be path-safe", "idempotencyKey");
  const expectedRevision = integer(request, "expectedRevision");
  if (expectedRevision < 0 || expectedRevision >= Number.MAX_SAFE_INTEGER) {
    invalid("expectedRevision is outside the supported range", "expectedRevision");
  }
  const name = requiredString(request, "name");
  if (recoverLegacyMiniHomeName(name) !== name) {
    invalid("name must be NFC, safe, and contain 1 to 100 Unicode code points", "name");
  }
  if (!Array.isArray(request.placements) || request.placements.length > maximumPlacements) {
    invalid("placements must be a bounded array", "placements");
  }
  const placements = request.placements.map(placement);
  const placementIds = new Set<string>();
  const targetIds = new Set<string>();
  const cells = new Set<string>();
  placements.forEach((item, index) => {
    if (item.zIndex !== index) invalid("zIndex must be contiguous and depth ordered", "zIndex");
    const target = item.plantId === null ? `item:${item.itemId}` : `plant:${item.plantId}`;
    const cell = snappedCell(item.normalizedX, item.normalizedY);
    if (placementIds.has(item.placementId) || targetIds.has(target) || cells.has(cell)) {
      invalid("Placements must have unique identities, entities, and cells", "placements");
    }
    placementIds.add(item.placementId);
    targetIds.add(target);
    cells.add(cell);
  });
  const depthOrder = [...placements].sort((left, right) => {
    const leftDepth = left.normalizedX + left.normalizedY;
    const rightDepth = right.normalizedX + right.normalizedY;
    if (leftDepth !== rightDepth) return leftDepth - rightDepth;
    const leftHorizontal = left.normalizedX - left.normalizedY;
    const rightHorizontal = right.normalizedX - right.normalizedY;
    if (leftHorizontal !== rightHorizontal) return leftHorizontal - rightHorizontal;
    return left.placementId < right.placementId ? -1 : left.placementId > right.placementId ? 1 : 0;
  });
  if (depthOrder.some((item, index) => item.placementId !== placements[index]?.placementId)) {
    invalid("zIndex does not match room depth", "placements");
  }
  const hashInput = { miniHomeId, expectedRevision, name, placements };
  return store.save({
    ownerUid: auth.uid,
    miniHomeId,
    expectedRevision,
    idempotencyKey,
    requestHash: createHash("sha256").update(canonicalJson(hashInput), "utf8").digest("hex"),
    name,
    placements,
  });
}
