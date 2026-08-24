export const ACCOUNT_DELETION_GRACE_MILLIS = 7 * 24 * 60 * 60 * 1_000;
export const ACCOUNT_DELETION_RECENT_AUTH_SECONDS = 5 * 60;
export const ACCOUNT_DELETION_LEASE_MILLIS = 10 * 60 * 1_000;
export const ACCOUNT_DELETION_SCAN_LIMIT = 50;

export const ACCOUNT_DELETION_SCOPES = [
  "PUBLIC_SHARES",
  "NOTIFICATION_ENDPOINT_OWNERS",
  "PRIVATE_MEDIA_RESERVATIONS",
  "IDENTIFICATION_ORIGINALS",
  "PLANT_PHOTOS",
  "SHARE_IMAGES",
  "USER_DOCUMENTS",
  "AUTH_ACCOUNT",
] as const;
export type AccountDeletionScope = (typeof ACCOUNT_DELETION_SCOPES)[number];

export const ACCOUNT_DELETION_STATUSES = [
  "RECEIVED",
  "PROCESSING",
  "COMPLETED",
  "FAILED",
  "PARTIALLY_FAILED",
  "CANCELLED",
] as const;
export type AccountDeletionStatus = (typeof ACCOUNT_DELETION_STATUSES)[number];

export type AccountDeletionAuth = Readonly<{
  uid: string;
  authTimeSeconds: number | null;
}>;

export type AccountDeletionRecord = Readonly<{
  schemaVersion: 1;
  ownerUid: string;
  requestId: string;
  idempotencyKeyHash: string;
  status: AccountDeletionStatus;
  requestedAtMillis: number;
  scheduledForMillis: number;
  nextAttemptAtMillis: number | null;
  leaseExpiresAtMillis: number | null;
  completedAtMillis: number | null;
  completedScopes: readonly AccountDeletionScope[];
  failedScopes: readonly AccountDeletionScope[];
  claimGeneration: number;
  updatedAtMillis: number;
}>;

export type AccountDeletionView = Readonly<{
  ownerUid: string;
  requestId: string;
  status: AccountDeletionStatus;
  requestedAtMillis: number;
  scheduledForMillis: number;
  completedAtMillis: number | null;
  completedScopes: readonly AccountDeletionScope[];
  failedScopes: readonly AccountDeletionScope[];
}>;

export type RequestDeletionCommand = Readonly<{ record: AccountDeletionRecord }>;
export type CancelDeletionCommand = Readonly<{
  ownerUid: string;
  requestId: string;
  nowMillis: number;
}>;
export type RetryDeletionCommand = Readonly<{
  ownerUid: string;
  requestId: string;
  idempotencyKeyHash: string;
  nowMillis: number;
  scheduledForMillis: number;
}>;
export type ClaimDeletionCommand = Readonly<{
  nowMillis: number;
  leaseExpiresAtMillis: number;
  limit: number;
}>;
export type FinishDeletionCommand = Readonly<{
  ownerUid: string;
  requestId: string;
  claimGeneration: number;
  completedScopes: readonly AccountDeletionScope[];
  failedScopes: readonly AccountDeletionScope[];
  nowMillis: number;
}>;

export interface AccountDeletionStore {
  load(ownerUid: string): Promise<AccountDeletionRecord | null>;
  request(command: RequestDeletionCommand): Promise<AccountDeletionRecord>;
  cancel(command: CancelDeletionCommand): Promise<AccountDeletionRecord | null>;
  retry(command: RetryDeletionCommand): Promise<AccountDeletionRecord | null>;
  claimDue(command: ClaimDeletionCommand): Promise<readonly AccountDeletionRecord[]>;
  finish(command: FinishDeletionCommand): Promise<AccountDeletionRecord>;
}

export interface AccountDeletionCleaner {
  clean(ownerUid: string, scope: AccountDeletionScope): Promise<void>;
}

export type AccountDeletionErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition";

export class AccountDeletionError extends Error {
  override readonly name = "AccountDeletionError";

  constructor(
    readonly code: AccountDeletionErrorCode,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
  }
}

export class AccountDeletionCleanupError extends Error {
  override readonly name = "AccountDeletionCleanupError";

  constructor(
    readonly ownerUid: string,
    readonly scope: AccountDeletionScope,
    options?: ErrorOptions,
  ) {
    super(`Account deletion cleanup failed for ${scope}`, options);
  }
}
