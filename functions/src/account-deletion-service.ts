import { createHash } from "node:crypto";
import { z } from "zod";
import {
  ACCOUNT_DELETION_GRACE_MILLIS,
  ACCOUNT_DELETION_RECENT_AUTH_SECONDS,
  ACCOUNT_DELETION_SCOPES,
  type AccountDeletionAuth,
  AccountDeletionError,
  type AccountDeletionRecord,
  type AccountDeletionStore,
  type AccountDeletionView,
} from "./account-deletion-contract.js";

const ownerUidSchema = z.string().regex(/^[A-Za-z0-9_-]{1,128}$/);
const ownerInputSchema = z.object({ expectedOwnerUid: ownerUidSchema }).strict();
const cancelInputSchema = z
  .object({
    expectedOwnerUid: ownerUidSchema,
    requestId: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/),
  })
  .strict();
const confirmedInputSchema = z
  .object({
    expectedOwnerUid: ownerUidSchema,
    confirmed: z.boolean(),
    idempotencyKey: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/),
  })
  .strict();

type RequestDependencies = Readonly<{
  store: AccountDeletionStore;
  nowMillis: () => number;
  requestId: () => string;
}>;

type TimedStoreDependencies = Readonly<{
  store: AccountDeletionStore;
  nowMillis: () => number;
}>;

function parseBoundary<T>(schema: z.ZodType<T>, input: unknown): T {
  const result = schema.safeParse(input);
  if (!result.success) {
    throw new AccountDeletionError("invalid-argument", "Payload does not match the contract", {
      cause: result.error,
    });
  }
  return result.data;
}

function authenticatedOwner(
  auth: AccountDeletionAuth | null,
  expectedOwnerUid: string,
): AccountDeletionAuth {
  if (auth === null) {
    throw new AccountDeletionError("unauthenticated", "Authentication is required");
  }
  if (auth.uid !== expectedOwnerUid) {
    throw new AccountDeletionError("permission-denied", "Authenticated owner does not match");
  }
  return auth;
}

function requireRecentAuthentication(auth: AccountDeletionAuth, nowMillis: number): void {
  const nowSeconds = Math.floor(nowMillis / 1_000);
  if (
    auth.authTimeSeconds === null ||
    !Number.isSafeInteger(auth.authTimeSeconds) ||
    auth.authTimeSeconds > nowSeconds ||
    nowSeconds - auth.authTimeSeconds > ACCOUNT_DELETION_RECENT_AUTH_SECONDS
  ) {
    throw new AccountDeletionError("failed-precondition", "Recent authentication is required");
  }
}

function view(record: AccountDeletionRecord): AccountDeletionView {
  return {
    ownerUid: record.ownerUid,
    requestId: record.requestId,
    status: record.status,
    requestedAtMillis: record.requestedAtMillis,
    scheduledForMillis: record.scheduledForMillis,
    completedAtMillis: record.completedAtMillis,
    completedScopes: record.completedScopes,
    failedScopes: record.failedScopes,
  };
}

export async function previewAccountDeletion(
  auth: AccountDeletionAuth | null,
  input: unknown,
  store: AccountDeletionStore,
): Promise<
  Readonly<{
    scope: Readonly<{
      categories: readonly (typeof ACCOUNT_DELETION_SCOPES)[number][];
      gracePeriodMillis: number;
    }>;
    request: AccountDeletionView | null;
  }>
> {
  const command = parseBoundary(ownerInputSchema, input);
  const authenticated = authenticatedOwner(auth, command.expectedOwnerUid);
  const record = await store.load(authenticated.uid);
  return {
    scope: {
      categories: ACCOUNT_DELETION_SCOPES,
      gracePeriodMillis: ACCOUNT_DELETION_GRACE_MILLIS,
    },
    request: record === null ? null : view(record),
  };
}

export async function getAccountDeletionStatus(
  auth: AccountDeletionAuth | null,
  input: unknown,
  store: AccountDeletionStore,
): Promise<AccountDeletionView | null> {
  const command = parseBoundary(ownerInputSchema, input);
  const authenticated = authenticatedOwner(auth, command.expectedOwnerUid);
  const record = await store.load(authenticated.uid);
  return record === null ? null : view(record);
}

export async function requestAccountDeletion(
  auth: AccountDeletionAuth | null,
  input: unknown,
  dependencies: RequestDependencies,
): Promise<AccountDeletionView> {
  const command = parseBoundary(confirmedInputSchema, input);
  const authenticated = authenticatedOwner(auth, command.expectedOwnerUid);
  const nowMillis = dependencies.nowMillis();
  if (!command.confirmed) {
    throw new AccountDeletionError("failed-precondition", "Explicit confirmation is required");
  }
  requireRecentAuthentication(authenticated, nowMillis);
  const record: AccountDeletionRecord = {
    schemaVersion: 1,
    ownerUid: authenticated.uid,
    requestId: dependencies.requestId(),
    idempotencyKeyHash: createHash("sha256").update(command.idempotencyKey, "utf8").digest("hex"),
    status: "RECEIVED",
    requestedAtMillis: nowMillis,
    scheduledForMillis: nowMillis + ACCOUNT_DELETION_GRACE_MILLIS,
    nextAttemptAtMillis: nowMillis + ACCOUNT_DELETION_GRACE_MILLIS,
    leaseExpiresAtMillis: null,
    completedAtMillis: null,
    completedScopes: [],
    failedScopes: [],
    claimGeneration: 0,
    updatedAtMillis: nowMillis,
  };
  return view(await dependencies.store.request({ record }));
}

export async function cancelAccountDeletion(
  auth: AccountDeletionAuth | null,
  input: unknown,
  dependencies: TimedStoreDependencies,
): Promise<AccountDeletionView> {
  const command = parseBoundary(cancelInputSchema, input);
  const authenticated = authenticatedOwner(auth, command.expectedOwnerUid);
  const record = await dependencies.store.cancel({
    ownerUid: authenticated.uid,
    requestId: command.requestId,
    nowMillis: dependencies.nowMillis(),
  });
  if (record === null) {
    throw new AccountDeletionError(
      "failed-precondition",
      "Only a received account deletion can be cancelled during its grace period",
    );
  }
  return view(record);
}

export async function retryAccountDeletion(
  auth: AccountDeletionAuth | null,
  input: unknown,
  dependencies: RequestDependencies,
): Promise<AccountDeletionView> {
  const command = parseBoundary(confirmedInputSchema, input);
  const authenticated = authenticatedOwner(auth, command.expectedOwnerUid);
  const nowMillis = dependencies.nowMillis();
  if (!command.confirmed) {
    throw new AccountDeletionError("failed-precondition", "Explicit confirmation is required");
  }
  requireRecentAuthentication(authenticated, nowMillis);
  const record = await dependencies.store.retry({
    ownerUid: authenticated.uid,
    requestId: dependencies.requestId(),
    idempotencyKeyHash: createHash("sha256")
      .update(command.idempotencyKey, "utf8")
      .digest("hex"),
    nowMillis,
    scheduledForMillis: nowMillis + ACCOUNT_DELETION_GRACE_MILLIS,
  });
  if (record === null) {
    throw new AccountDeletionError(
      "failed-precondition",
      "Only a failed or partially failed account deletion can be retried",
    );
  }
  return view(record);
}
