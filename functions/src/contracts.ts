import { createHash } from "node:crypto";

export type AuthContext = Readonly<{ uid: string }>;
export type ServerContext = Readonly<{ trusted: boolean }>;

export type OwnerCollection =
  | "personalPlants"
  | "miniHomes"
  | "placements"
  | "consents"
  | "identificationRequests";

export type ServerCollection =
  | "notificationDeliveries"
  | "weatherSnapshots"
  | "weatherRisks"
  | "deletionRequests"
  | "ownedItems"
  | "shareLinks";

export type OwnerMutationType = "CREATE" | "UPDATE";

export type OwnerMutationCommand = Readonly<{
  ownerUid: string;
  collection: OwnerCollection;
  documentId: string;
  documentPath: string;
  mutationType: OwnerMutationType;
  expectedRevision: number;
  idempotencyKey: string;
  requestHash: string;
  payload: Readonly<Record<string, unknown>>;
}>;

export type ServerStateCommand = Readonly<{
  ownerUid: string;
  collection: ServerCollection;
  documentId: string;
  documentPath: string;
  payload: Readonly<Record<string, unknown>>;
}>;

export type MutationResult =
  | Readonly<{ kind: "applied"; revision: number }>
  | Readonly<{ kind: "duplicate"; revision: number }>
  | Readonly<{ kind: "conflict"; actualRevision: number }>;

export interface MutationStore {
  applyOwnerMutation(command: OwnerMutationCommand): Promise<MutationResult>;
  writeServerState(command: ServerStateCommand): Promise<void>;
  ownerZoneId(ownerUid: string): Promise<string>;
  publicPlantContentExists(contentId: string): Promise<boolean>;
}

export type ContractErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "resource-exhausted";

export class ContractError extends Error {
  constructor(readonly code: ContractErrorCode, message: string) {
    super(message);
    this.name = "ContractError";
  }
}

const opaqueId = /^[A-Za-z0-9_-]{1,128}$/;
const operationId = /^[A-Za-z0-9_-]{8,128}$/;

function graphemeCount(value: string): number {
  return [...new Intl.Segmenter("und", { granularity: "grapheme" }).segment(value)].length;
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number" && Number.isFinite(value)) return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (typeof value === "object") {
    const entries = Object.entries(value as Readonly<Record<string, unknown>>).sort(
      ([left], [right]) => (left < right ? -1 : left > right ? 1 : 0),
    );
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`).join(",")}}`;
  }
  throw new ContractError("invalid-argument", "Payload contains an unsupported value");
}

function mutationHash(value: Readonly<Record<string, unknown>>): string {
  return createHash("sha256").update(canonicalJson(value), "utf8").digest("hex");
}

function record(value: unknown, label: string): Readonly<Record<string, unknown>> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ContractError("invalid-argument", `${label} must be an object`);
  }
  return Object.fromEntries(Object.entries(value));
}

function exactFields(
  value: Readonly<Record<string, unknown>>,
  required: readonly string[],
  optional: readonly string[] = [],
): void {
  const allowed = new Set([...required, ...optional]);
  if (!required.every((field) => field in value) || !Object.keys(value).every((field) => allowed.has(field))) {
    throw new ContractError("invalid-argument", "Payload fields do not match the contract");
  }
}

function stringField(value: Readonly<Record<string, unknown>>, field: string): string {
  const candidate = value[field];
  if (typeof candidate !== "string" || candidate.length === 0) {
    throw new ContractError("invalid-argument", `${field} must be a non-empty string`);
  }
  return candidate;
}

function nullableStringField(value: Readonly<Record<string, unknown>>, field: string): string | null {
  const candidate = value[field];
  if (candidate === null || candidate === undefined) return null;
  if (typeof candidate !== "string") throw new ContractError("invalid-argument", `${field} must be a string or null`);
  return candidate;
}

function booleanField(value: Readonly<Record<string, unknown>>, field: string): boolean {
  const candidate = value[field];
  if (typeof candidate !== "boolean") throw new ContractError("invalid-argument", `${field} must be boolean`);
  return candidate;
}

function numberField(value: Readonly<Record<string, unknown>>, field: string): number {
  const candidate = value[field];
  if (typeof candidate !== "number" || !Number.isFinite(candidate)) {
    throw new ContractError("invalid-argument", `${field} must be a finite number`);
  }
  return candidate;
}

function integerField(value: Readonly<Record<string, unknown>>, field: string): number {
  const candidate = numberField(value, field);
  if (!Number.isSafeInteger(candidate)) throw new ContractError("invalid-argument", `${field} must be an integer`);
  return candidate;
}

function opaqueField(value: Readonly<Record<string, unknown>>, field: string): string {
  const candidate = stringField(value, field);
  if (!opaqueId.test(candidate)) throw new ContractError("invalid-argument", `${field} must be path-safe`);
  return candidate;
}

function localDate(value: Readonly<Record<string, unknown>>, field: string, nullable = false): string | null {
  const candidate = value[field];
  if (nullable && (candidate === null || candidate === undefined)) return null;
  if (typeof candidate !== "string") throw new ContractError("invalid-argument", `${field} must be a local date`);
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(candidate);
  if (match === null) throw new ContractError("invalid-argument", `${field} must be YYYY-MM-DD`);
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== day) {
    throw new ContractError("invalid-argument", `${field} is not a calendar date`);
  }
  return candidate;
}

function localTime(value: Readonly<Record<string, unknown>>, field: string): string {
  const candidate = stringField(value, field);
  if (!/^(0\d|1\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/.test(candidate)) {
    throw new ContractError("invalid-argument", `${field} must be a local time`);
  }
  return candidate;
}

function zoneId(value: Readonly<Record<string, unknown>>, field: string): string {
  const candidate = stringField(value, field);
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: candidate }).format();
  } catch {
    throw new ContractError("invalid-argument", `${field} must be an IANA zone ID`);
  }
  return candidate;
}

function isoInstant(value: Readonly<Record<string, unknown>>, field: string, nullable = false): Date | null {
  const candidate = value[field];
  if (nullable && (candidate === null || candidate === undefined)) return null;
  if (typeof candidate !== "string") throw new ContractError("invalid-argument", `${field} must be an ISO instant`);
  const match = /^(\d{4}-\d{2}-\d{2})T(0\d|1\d|2[0-3]):[0-5]\d:[0-5]\d(?:[.]\d{1,9})?Z$/.exec(candidate);
  if (match === null) throw new ContractError("invalid-argument", `${field} must be a UTC ISO instant`);
  localDate({ date: match[1] }, "date");
  const parsed = new Date(candidate);
  if (Number.isNaN(parsed.valueOf())) throw new ContractError("invalid-argument", `${field} must be an ISO instant`);
  return parsed;
}

function oneOf<T extends string>(candidate: string, allowed: readonly T[], field: string): T {
  const match = allowed.find((value) => value === candidate);
  if (match === undefined) throw new ContractError("invalid-argument", `${field} is unsupported`);
  return match;
}

function ownerCollection(value: string): OwnerCollection {
  switch (value) {
    case "personalPlants":
    case "miniHomes":
    case "placements":
    case "consents":
    case "identificationRequests":
      return value;
    default:
      throw new ContractError("invalid-argument", "Collection is not owner-writable");
  }
}

function serverCollection(value: string): ServerCollection {
  switch (value) {
    case "notificationDeliveries":
    case "weatherSnapshots":
    case "weatherRisks":
    case "deletionRequests":
    case "ownedItems":
    case "shareLinks":
      return value;
    default:
      throw new ContractError("invalid-argument", "Collection is not server-derived");
  }
}

async function validateOwnerPayload(
  collection: OwnerCollection,
  mutationType: OwnerMutationType,
  payload: Readonly<Record<string, unknown>>,
  ownerUid: string,
  documentId: string,
  store: MutationStore,
): Promise<void> {
  switch (collection) {
    case "personalPlants": {
      if (mutationType === "UPDATE") {
        exactFields(payload, [], ["lastWateredDate", "location", "note"]);
        if (Object.keys(payload).length === 0) {
          throw new ContractError("invalid-argument", "Personal plant update patch must not be empty");
        }
        if ("location" in payload) {
          const location = nullableStringField(payload, "location");
          if (location !== null && graphemeCount(location) > 50) {
            throw new ContractError("invalid-argument", "location must contain at most 50 characters");
          }
        }
        if ("note" in payload) {
          const note = nullableStringField(payload, "note");
          if (note !== null && graphemeCount(note) > 1000) {
            throw new ContractError("invalid-argument", "note must contain at most 1000 characters");
          }
        }
        if ("lastWateredDate" in payload) {
          await validateLastWateredDate(payload, ownerUid, store);
        }
        return;
      }
      exactFields(payload, ["displayName", "registrationMethod"], ["contentId", "representativePhotoPath", "location", "note", "lastWateredDate"]);
      const displayName = stringField(payload, "displayName");
      const graphemes = graphemeCount(displayName);
      if (displayName !== displayName.trim() || graphemes < 1 || graphemes > 100) {
        throw new ContractError("invalid-argument", "displayName must be normalized and contain 1 to 100 characters");
      }
      oneOf(stringField(payload, "registrationMethod"), ["IDENTIFIED", "IDENTIFICATION_EDITED", "MANUAL"] as const, "registrationMethod");
      const contentId = nullableStringField(payload, "contentId");
      if (contentId !== null) {
        opaqueField(payload, "contentId");
        if (!(await store.publicPlantContentExists(contentId))) {
          throw new ContractError("invalid-argument", "contentId must reference published plant content");
        }
      }
      const photoPath = nullableStringField(payload, "representativePhotoPath");
      if (photoPath !== null && !new RegExp(`^plant-photos/${ownerUid}/${documentId}/representative[.](jpg|png|webp|heif|heic)$`).test(photoPath)) {
        throw new ContractError("invalid-argument", "representativePhotoPath must belong to the target plant");
      }
      const location = nullableStringField(payload, "location");
      const note = nullableStringField(payload, "note");
      if (location !== null && graphemeCount(location) > 50) {
        throw new ContractError("invalid-argument", "location must contain at most 50 characters");
      }
      if (note !== null && graphemeCount(note) > 1000) {
        throw new ContractError("invalid-argument", "note must contain at most 1000 characters");
      }
      await validateLastWateredDate(payload, ownerUid, store);
      return;
    }
    case "miniHomes":
      exactFields(payload, ["name"]);
      stringField(payload, "name");
      return;
    case "placements": {
      exactFields(payload, ["plantId", "itemId", "normalizedX", "normalizedY", "zIndex"]);
      const plantId = nullableStringField(payload, "plantId");
      const itemId = nullableStringField(payload, "itemId");
      if ((plantId === null) === (itemId === null)) throw new ContractError("invalid-argument", "Placement must reference exactly one target");
      if (plantId !== null) opaqueField(payload, "plantId");
      if (itemId !== null) opaqueField(payload, "itemId");
      const x = numberField(payload, "normalizedX");
      const y = numberField(payload, "normalizedY");
      if (x < 0 || x > 1 || y < 0 || y > 1) throw new ContractError("invalid-argument", "Placement coordinates must be normalized");
      integerField(payload, "zIndex");
      return;
    }
    case "consents":
      exactFields(payload, ["type", "granted", "recordedAt"]);
      oneOf(stringField(payload, "type"), ["IDENTIFICATION_PHOTO_PROCESSING", "LOCATION", "ANALYTICS"] as const, "type");
      booleanField(payload, "granted");
      isoInstant(payload, "recordedAt");
      return;
    case "identificationRequests": {
      exactFields(payload, ["temporaryOriginalPath", "createdAt", "expiresAt"]);
      stringField(payload, "temporaryOriginalPath");
      const created = isoInstant(payload, "createdAt");
      const expires = isoInstant(payload, "expiresAt");
      if (created !== null && expires !== null && expires <= created) throw new ContractError("invalid-argument", "expiresAt must follow createdAt");
      return;
    }
  }
}

function validateServerPayload(collection: ServerCollection, payload: Readonly<Record<string, unknown>>): void {
  switch (collection) {
    case "notificationDeliveries": {
      exactFields(
        payload,
        ["plantId", "dueDate", "attempt", "status", "scheduledFor", "deliveredAt", "deduplicationKey"],
        ["endpointResults"],
      );
      opaqueField(payload, "plantId");
      localDate(payload, "dueDate");
      const attempt = integerField(payload, "attempt");
      if (attempt !== 0 && attempt !== 1) throw new ContractError("invalid-argument", "attempt must be zero or one");
      oneOf(stringField(payload, "status"), ["SENT"] as const, "status");
      const scheduled = isoInstant(payload, "scheduledFor");
      const delivered = isoInstant(payload, "deliveredAt", true);
      stringField(payload, "deduplicationKey");
      if (delivered === null) throw new ContractError("invalid-argument", "deliveredAt is required");
      if (scheduled !== null && delivered !== null && delivered < scheduled) throw new ContractError("invalid-argument", "deliveredAt precedes scheduledFor");
      return;
    }
    case "weatherSnapshots": {
      exactFields(payload, ["regionCode", "temperatureCelsius", "humidityPercent", "precipitationMillimeters", "observedAt", "expiresAt"]);
      stringField(payload, "regionCode");
      numberField(payload, "temperatureCelsius");
      const humidity = integerField(payload, "humidityPercent");
      const precipitation = numberField(payload, "precipitationMillimeters");
      if (humidity < 0 || humidity > 100 || precipitation < 0) throw new ContractError("invalid-argument", "Weather values are outside supported ranges");
      const observed = isoInstant(payload, "observedAt");
      const expires = isoInstant(payload, "expiresAt");
      if (observed !== null && expires !== null && expires <= observed) throw new ContractError("invalid-argument", "expiresAt must follow observedAt");
      return;
    }
    case "weatherRisks":
      exactFields(payload, ["plantId", "snapshotId", "type", "detectedAt", "active"], ["action"]);
      opaqueField(payload, "plantId");
      opaqueField(payload, "snapshotId");
      oneOf(stringField(payload, "type"), ["HIGH_TEMPERATURE", "LOW_TEMPERATURE", "DRY", "OVERWATERED"] as const, "type");
      nullableStringField(payload, "action");
      isoInstant(payload, "detectedAt");
      booleanField(payload, "active");
      return;
    case "deletionRequests": {
      exactFields(payload, ["status", "requestedAt", "scheduledFor", "completedAt"]);
      const status = oneOf(stringField(payload, "status"), ["RECEIVED", "PROCESSING", "COMPLETED", "FAILED", "PARTIALLY_FAILED", "CANCELLED"] as const, "status");
      const requested = isoInstant(payload, "requestedAt");
      const scheduled = isoInstant(payload, "scheduledFor");
      const completed = isoInstant(payload, "completedAt", true);
      if (requested !== null && scheduled !== null && scheduled < requested) throw new ContractError("invalid-argument", "scheduledFor precedes requestedAt");
      if (completed !== null && scheduled !== null && completed < scheduled) throw new ContractError("invalid-argument", "completedAt precedes scheduledFor");
      if (status === "COMPLETED" && completed === null) throw new ContractError("invalid-argument", "completedAt is required");
      return;
    }
    case "ownedItems":
      exactFields(payload, ["itemId", "acquiredAt", "applied"]);
      opaqueField(payload, "itemId");
      isoInstant(payload, "acquiredAt");
      booleanField(payload, "applied");
      return;
    case "shareLinks": {
      exactFields(payload, ["miniHomeId", "sourceRevision", "snapshotPath", "createdAt", "expiresAt", "revokedAt"]);
      opaqueField(payload, "miniHomeId");
      if (integerField(payload, "sourceRevision") < 1) throw new ContractError("invalid-argument", "sourceRevision must be positive");
      stringField(payload, "snapshotPath");
      const created = isoInstant(payload, "createdAt");
      const expires = isoInstant(payload, "expiresAt");
      const revoked = isoInstant(payload, "revokedAt", true);
      if (created !== null && expires !== null && expires <= created) throw new ContractError("invalid-argument", "expiresAt must follow createdAt");
      if (created !== null && revoked !== null && revoked < created) throw new ContractError("invalid-argument", "revokedAt precedes createdAt");
      return;
    }
  }
}

async function validateLastWateredDate(
  payload: Readonly<Record<string, unknown>>,
  ownerUid: string,
  store: MutationStore,
): Promise<void> {
  const watered = localDate(payload, "lastWateredDate", true);
  if (watered === null) return;
  const ownerZone = await store.ownerZoneId(ownerUid);
  zoneId({ ownerZone }, "ownerZone");
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: ownerZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((value) => value.type === type)?.value;
  const today = `${part("year")}-${part("month")}-${part("day")}`;
  if (watered > today) throw new ContractError("invalid-argument", "lastWateredDate cannot be in the future");
}

export async function executeOwnerMutation(
  auth: AuthContext | null,
  input: unknown,
  store: MutationStore,
): Promise<MutationResult> {
  if (auth === null || !opaqueId.test(auth.uid)) throw new ContractError("unauthenticated", "Authentication is required");
  const value = record(input, "input");
  exactFields(value, ["expectedOwnerUid", "collection", "documentId", "mutationType", "expectedRevision", "idempotencyKey", "payload"]);
  const expectedOwnerUid = opaqueField(value, "expectedOwnerUid");
  if (expectedOwnerUid !== auth.uid) {
    throw new ContractError("permission-denied", "Authenticated owner does not match the request");
  }
  const collection = ownerCollection(stringField(value, "collection"));
  const documentId = stringField(value, "documentId");
  const mutationType = oneOf(stringField(value, "mutationType"), ["CREATE", "UPDATE"] as const, "mutationType");
  const idempotencyKey = stringField(value, "idempotencyKey");
  const expectedRevision = value.expectedRevision;
  if (!opaqueId.test(documentId) || !operationId.test(idempotencyKey) || typeof expectedRevision !== "number" || !Number.isSafeInteger(expectedRevision) || expectedRevision < 0) {
    throw new ContractError("invalid-argument", "Mutation identifiers or revision are invalid");
  }
  const payload = record(value.payload, "payload");
  await validateOwnerPayload(collection, mutationType, payload, auth.uid, documentId, store);
  const requestHash = mutationHash({
    ownerUid: auth.uid,
    collection,
    documentId,
    mutationType,
    expectedRevision,
    payload,
  });
  return store.applyOwnerMutation({
    ownerUid: auth.uid,
    collection,
    documentId,
    documentPath: `users/${auth.uid}/${collection}/${documentId}`,
    mutationType,
    expectedRevision,
    idempotencyKey,
    requestHash,
    payload,
  });
}

export async function executeServerStateWrite(
  context: ServerContext,
  ownerUid: string,
  input: unknown,
  store: MutationStore,
): Promise<void> {
  if (!context.trusted) throw new ContractError("permission-denied", "Trusted server context is required");
  if (!opaqueId.test(ownerUid)) throw new ContractError("invalid-argument", "Owner is invalid");
  const value = record(input, "input");
  exactFields(value, ["collection", "documentId", "payload"]);
  const collection = serverCollection(stringField(value, "collection"));
  const documentId = stringField(value, "documentId");
  if (!opaqueId.test(documentId)) throw new ContractError("invalid-argument", "Document ID is invalid");
  const payload = record(value.payload, "payload");
  validateServerPayload(collection, payload);
  await store.writeServerState({
    ownerUid,
    collection,
    documentId,
    documentPath: `users/${ownerUid}/${collection}/${documentId}`,
    payload,
  });
}
