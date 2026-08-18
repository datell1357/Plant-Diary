import { createHash } from "node:crypto";
import type { MutationResult } from "./contracts.js";

export type WateringAuthContext = Readonly<{ uid: string }>;

export type WateringCompletionCommand = Readonly<{
  ownerUid: string;
  plantId: string;
  expectedPlantRevision: number;
  idempotencyKey: string;
  requestHash: string;
  requestedWateredDate?: string;
}>;

export interface WateringCompletionStore {
  completeWatering(command: WateringCompletionCommand, now: Date): Promise<MutationResult>;
}

export type WateringErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition";

export class WateringError extends Error {
  constructor(readonly code: WateringErrorCode, message: string) {
    super(message);
    this.name = "WateringError";
  }
}

const opaqueId = /^[A-Za-z0-9_-]{1,128}$/;
const operationId = /^[A-Za-z0-9_-]{8,128}$/;

export async function executeWateringCompletion(
  auth: WateringAuthContext | null,
  input: unknown,
  store: WateringCompletionStore,
  now: Date = new Date(),
): Promise<MutationResult> {
  if (auth === null || !opaqueId.test(auth.uid)) {
    throw new WateringError("unauthenticated", "Authentication is required");
  }
  const value = asRecord(input, "input");
  exactFields(value, [
    "expectedOwnerUid",
    "collection",
    "documentId",
    "mutationType",
    "expectedRevision",
    "idempotencyKey",
    "payload",
  ]);
  const expectedOwnerUid = stringField(value, "expectedOwnerUid");
  if (expectedOwnerUid !== auth.uid) {
    throw new WateringError("permission-denied", "Authenticated owner does not match the request");
  }
  if (value.collection !== "wateringCompletions" || value.mutationType !== "UPDATE") {
    throw new WateringError("invalid-argument", "Watering completion mutation is invalid");
  }
  const plantId = stringField(value, "documentId");
  const idempotencyKey = stringField(value, "idempotencyKey");
  const expectedRevision = value.expectedRevision;
  if (
    !opaqueId.test(plantId) ||
    !operationId.test(idempotencyKey) ||
    typeof expectedRevision !== "number" ||
    !Number.isSafeInteger(expectedRevision) ||
    expectedRevision < 1
  ) {
    throw new WateringError("invalid-argument", "Watering identifiers or revision are invalid");
  }
  const payload = asRecord(value.payload, "payload");
  exactFields(payload, [], ["wateredDate"]);
  const requested = payload.wateredDate;
  if (requested !== undefined && typeof requested !== "string") {
    throw new WateringError("invalid-argument", "wateredDate must be a local date");
  }
  if (typeof requested === "string") parseLocalDate(requested, "wateredDate");
  const requestHash = createHash("sha256")
    .update(
      JSON.stringify({
        ownerUid: auth.uid,
        plantId,
        expectedPlantRevision: expectedRevision,
        idempotencyKey,
        requestedWateredDate: requested ?? null,
      }),
      "utf8",
    )
    .digest("hex");
  return store.completeWatering(
    {
      ownerUid: auth.uid,
      plantId,
      expectedPlantRevision: expectedRevision,
      idempotencyKey,
      requestHash,
      ...(requested === undefined ? {} : { requestedWateredDate: requested }),
    },
    now,
  );
}

export function resolveAccountLocalDate(
  requested: string | undefined,
  zoneId: string,
  now: Date,
): string {
  validateZone(zoneId);
  if (Number.isNaN(now.valueOf())) {
    throw new WateringError("invalid-argument", "Current instant is invalid");
  }
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone: zoneId,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
  const parts = formatter.formatToParts(now);
  const part = (type: Intl.DateTimeFormatPartTypes): string => {
    const value = parts.find((candidate) => candidate.type === type)?.value;
    if (value === undefined) throw new WateringError("invalid-argument", "Account date is unavailable");
    return value;
  };
  const today = `${part("year")}-${part("month")}-${part("day")}`;
  const wateredDate = requested ?? today;
  parseLocalDate(wateredDate, "wateredDate");
  if (wateredDate > today) {
    throw new WateringError("invalid-argument", "wateredDate cannot be in the future");
  }
  return wateredDate;
}

export function addLocalDays(value: string, days: number): string {
  parseLocalDate(value, "wateredDate");
  if (!Number.isSafeInteger(days) || days < 1 || days > 365) {
    throw new WateringError("failed-precondition", "Published watering interval is unavailable");
  }
  const [year, month, day] = value.split("-").map(Number) as [number, number, number];
  const date = new Date(Date.UTC(year, month - 1, day));
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function asRecord(value: unknown, label: string): Readonly<Record<string, unknown>> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new WateringError("invalid-argument", `${label} must be an object`);
  }
  return Object.fromEntries(Object.entries(value));
}

function exactFields(
  value: Readonly<Record<string, unknown>>,
  required: readonly string[],
  optional: readonly string[] = [],
): void {
  const allowed = new Set([...required, ...optional]);
  if (
    !required.every((field) => field in value) ||
    !Object.keys(value).every((field) => allowed.has(field))
  ) {
    throw new WateringError("invalid-argument", "Fields do not match the watering contract");
  }
}

function stringField(value: Readonly<Record<string, unknown>>, field: string): string {
  const candidate = value[field];
  if (typeof candidate !== "string" || candidate.length === 0) {
    throw new WateringError("invalid-argument", `${field} must be a non-empty string`);
  }
  return candidate;
}

function parseLocalDate(value: string, field: string): void {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (match === null) throw new WateringError("invalid-argument", `${field} must be YYYY-MM-DD`);
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  if (
    parsed.getUTCFullYear() !== year ||
    parsed.getUTCMonth() !== month - 1 ||
    parsed.getUTCDate() !== day
  ) {
    throw new WateringError("invalid-argument", `${field} is not a calendar date`);
  }
}

function validateZone(value: string): void {
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value }).format();
  } catch {
    throw new WateringError("invalid-argument", "Account timezone is invalid");
  }
}
