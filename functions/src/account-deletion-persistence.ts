import { Timestamp } from "firebase-admin/firestore";
import { z } from "zod";
import {
  ACCOUNT_DELETION_SCOPES,
  ACCOUNT_DELETION_STATUSES,
  type AccountDeletionRecord,
  type AccountDeletionScope,
} from "./account-deletion-contract.js";

const timestampSchema = z.instanceof(Timestamp);
export const storedAccountDeletionSchema = z
  .object({
    schemaVersion: z.literal(1),
    ownerUid: z.string().regex(/^[A-Za-z0-9_-]{1,128}$/),
    requestId: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/),
    idempotencyKeyHash: z.string().regex(/^[a-f0-9]{64}$/),
    status: z.enum(ACCOUNT_DELETION_STATUSES),
    requestedAt: timestampSchema,
    scheduledFor: timestampSchema,
    nextAttemptAt: timestampSchema.nullable(),
    leaseExpiresAt: timestampSchema.nullable(),
    completedAt: timestampSchema.nullable(),
    completedScopes: z.array(z.enum(ACCOUNT_DELETION_SCOPES)),
    failedScopes: z.array(z.enum(ACCOUNT_DELETION_SCOPES)),
    claimGeneration: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    updatedAt: timestampSchema,
  })
  .strict();

export type StoredAccountDeletion = z.infer<typeof storedAccountDeletionSchema>;
export type AccountDeletionReceiptKind = "REQUEST" | "CANCEL" | "RETRY";

const storedReceiptSchema = z
  .object({
    schemaVersion: z.literal(1),
    ownerUid: z.string().regex(/^[A-Za-z0-9_-]{1,128}$/),
    commandKind: z.enum(["REQUEST", "CANCEL", "RETRY"]),
    commandKey: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/),
    acceptedAt: timestampSchema,
    result: storedAccountDeletionSchema,
  })
  .strict();

export class AccountDeletionPersistenceError extends Error {
  override readonly name = "AccountDeletionPersistenceError";
}

export function parseStoredAccountDeletion(value: unknown): AccountDeletionRecord {
  const parsed = storedAccountDeletionSchema.safeParse(value);
  if (!parsed.success) malformed(parsed.error);
  const stored = parsed.data;
  const record: AccountDeletionRecord = {
    schemaVersion: 1,
    ownerUid: stored.ownerUid,
    requestId: stored.requestId,
    idempotencyKeyHash: stored.idempotencyKeyHash,
    status: stored.status,
    requestedAtMillis: stored.requestedAt.toMillis(),
    scheduledForMillis: stored.scheduledFor.toMillis(),
    nextAttemptAtMillis: stored.nextAttemptAt?.toMillis() ?? null,
    leaseExpiresAtMillis: stored.leaseExpiresAt?.toMillis() ?? null,
    completedAtMillis: stored.completedAt?.toMillis() ?? null,
    completedScopes: stored.completedScopes,
    failedScopes: stored.failedScopes,
    claimGeneration: stored.claimGeneration,
    updatedAtMillis: stored.updatedAt.toMillis(),
  };
  assertRecordState(record);
  return record;
}

export function parseStoredReceipt(
  value: unknown,
  ownerUid: string,
  commandKind: AccountDeletionReceiptKind,
  commandKey: string,
): AccountDeletionRecord {
  const parsed = storedReceiptSchema.safeParse(value);
  if (
    !parsed.success ||
    parsed.data.ownerUid !== ownerUid ||
    parsed.data.commandKind !== commandKind ||
    parsed.data.commandKey !== commandKey
  ) malformed(parsed.success ? undefined : parsed.error);
  return parseStoredAccountDeletion(parsed.data.result);
}

export function storedReceipt(
  ownerUid: string,
  commandKind: AccountDeletionReceiptKind,
  commandKey: string,
  record: AccountDeletionRecord,
  acceptedAtMillis: number,
): z.infer<typeof storedReceiptSchema> {
  return storedReceiptSchema.parse({
    schemaVersion: 1,
    ownerUid,
    commandKind,
    commandKey,
    acceptedAt: Timestamp.fromMillis(acceptedAtMillis),
    result: toStoredAccountDeletion(record),
  });
}

export function toStoredAccountDeletion(
  record: AccountDeletionRecord,
): StoredAccountDeletion {
  assertRecordState(record);
  return storedAccountDeletionSchema.parse({
    schemaVersion: 1,
    ownerUid: record.ownerUid,
    requestId: record.requestId,
    idempotencyKeyHash: record.idempotencyKeyHash,
    status: record.status,
    requestedAt: Timestamp.fromMillis(record.requestedAtMillis),
    scheduledFor: Timestamp.fromMillis(record.scheduledForMillis),
    nextAttemptAt:
      record.nextAttemptAtMillis === null
        ? null
        : Timestamp.fromMillis(record.nextAttemptAtMillis),
    leaseExpiresAt:
      record.leaseExpiresAtMillis === null
        ? null
        : Timestamp.fromMillis(record.leaseExpiresAtMillis),
    completedAt:
      record.completedAtMillis === null
        ? null
        : Timestamp.fromMillis(record.completedAtMillis),
    completedScopes: record.completedScopes,
    failedScopes: record.failedScopes,
    claimGeneration: record.claimGeneration,
    updatedAt: Timestamp.fromMillis(record.updatedAtMillis),
  });
}

function assertRecordState(record: AccountDeletionRecord): void {
  const completed = new Set(record.completedScopes);
  const failed = new Set(record.failedScopes);
  const noAttempt = record.nextAttemptAtMillis === null;
  const noLease = record.leaseExpiresAtMillis === null;
  const noCompletion = record.completedAtMillis === null;
  if (
    record.requestedAtMillis > record.scheduledForMillis ||
    record.updatedAtMillis < record.requestedAtMillis ||
    !canonical(record.completedScopes) ||
    !canonical(record.failedScopes) ||
    record.completedScopes.some((scope) => failed.has(scope))
  ) malformed();

  switch (record.status) {
    case "RECEIVED":
      if (
        noAttempt ||
        !noLease ||
        !noCompletion ||
        completed.size > 0 ||
        failed.size > 0 ||
        record.claimGeneration !== 0 ||
        record.nextAttemptAtMillis !== record.scheduledForMillis
      ) malformed();
      return;
    case "PROCESSING":
      if (
        noAttempt ||
        noLease ||
        !noCompletion ||
        failed.size > 0 ||
        record.updatedAtMillis < record.scheduledForMillis ||
        record.nextAttemptAtMillis !== record.leaseExpiresAtMillis ||
        record.claimGeneration < 1
      ) malformed();
      return;
    case "COMPLETED":
      if (
        !noAttempt ||
        !noLease ||
        noCompletion ||
        failed.size > 0 ||
        completed.size !== ACCOUNT_DELETION_SCOPES.length ||
        record.claimGeneration < 1 ||
        record.completedAtMillis !== record.updatedAtMillis
      ) malformed();
      return;
    case "FAILED":
      if (
        !noAttempt ||
        !noLease ||
        !noCompletion ||
        completed.size > 0 ||
        failed.size !== ACCOUNT_DELETION_SCOPES.length ||
        record.claimGeneration < 1 ||
        record.updatedAtMillis < record.scheduledForMillis
      ) malformed();
      return;
    case "PARTIALLY_FAILED":
      if (
        !noLease ||
        !noCompletion ||
        completed.size === 0 ||
        failed.size === 0 ||
        completed.size + failed.size !== ACCOUNT_DELETION_SCOPES.length ||
        record.claimGeneration < 1 ||
        record.updatedAtMillis < record.scheduledForMillis ||
        (!noAttempt && record.nextAttemptAtMillis !== record.updatedAtMillis)
      ) malformed();
      return;
    case "CANCELLED":
      if (
        !noAttempt ||
        !noLease ||
        !noCompletion ||
        completed.size > 0 ||
        failed.size > 0 ||
        record.claimGeneration !== 0
      ) malformed();
      return;
  }
}

function canonical(scopes: readonly AccountDeletionScope[]): boolean {
  return scopes.every(
    (scope, index) =>
      index === 0 ||
      ACCOUNT_DELETION_SCOPES.indexOf(scopes[index - 1]!) <
        ACCOUNT_DELETION_SCOPES.indexOf(scope),
  );
}

function malformed(cause?: unknown): never {
  throw new AccountDeletionPersistenceError("Stored account deletion is malformed", {
    cause,
  });
}
