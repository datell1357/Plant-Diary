import { z } from "zod";

/**
 * Approved disclosure version. The Android disclosure sheet and this server constant are the
 * same authorization fact; a source-bound test pins the two together so a client-only wording
 * or retention change can never silently widen what the server accepts as approved.
 */
export const IDENTIFICATION_DISCLOSURE_VERSION = 1;
export const IDENTIFICATION_ORIGINAL_RETENTION_HOURS = 24;
export const IDENTIFICATION_ORIGINAL_RETENTION_MILLIS =
  IDENTIFICATION_ORIGINAL_RETENTION_HOURS * 60 * 60 * 1_000;
/** Provider claim lease. A lease older than this is reclaimable by a fresh operation key. */
export const IDENTIFICATION_CLAIM_LEASE_MILLIS = 2 * 60 * 1_000;
export const IDENTIFICATION_CLEANUP_SCAN_LIMIT = 100;

export const IDENTIFICATION_REQUEST_STATUSES = [
  "APPROVED",
  "PENDING",
  "CANDIDATES",
  "NO_CANDIDATES",
  "FAILED",
  "CANCELLED",
] as const;
export type IdentificationRequestStatus = (typeof IDENTIFICATION_REQUEST_STATUSES)[number];

const TERMINAL_STATUSES: ReadonlySet<IdentificationRequestStatus> = new Set([
  "CANDIDATES",
  "NO_CANDIDATES",
  "FAILED",
  "CANCELLED",
]);

export function isTerminalIdentificationStatus(status: IdentificationRequestStatus): boolean {
  return TERMINAL_STATUSES.has(status);
}

/**
 * Provider send boundary. `sendState` records how far one claim generation travelled toward the
 * external provider so a process death or an ambiguous response can never be replayed as a second
 * transmission of the same original.
 */
export const IDENTIFICATION_SEND_STATES = ["NOT_SENT", "SENDING", "SENT"] as const;
export type IdentificationSendState = (typeof IDENTIFICATION_SEND_STATES)[number];

export type IdentificationMediaReference = Readonly<{
  reservationId: string;
  generation: string;
}>;

export type AuthorizedIdentificationRequest = Readonly<{
  schemaVersion: 1;
  requestId: string;
  ownerUid: string;
  mediaReference: IdentificationMediaReference;
  disclosureVersion: number;
  status: IdentificationRequestStatus;
  claimGeneration: number;
  claimOperationKey: string | null;
  claimExpiresAtMillis: number | null;
  sendState: IdentificationSendState;
  acknowledgedAtMillis: number;
  createdAtMillis: number;
  hardExpiresAtMillis: number;
  terminalAtMillis: number | null;
  retentionExpiresAtMillis: number | null;
}>;

export type IdentificationAuthorizationErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition"
  | "not-found";

export class IdentificationAuthorizationError extends Error {
  override readonly name = "IdentificationAuthorizationError";

  constructor(
    readonly code: IdentificationAuthorizationErrorCode,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
  }
}

const opaqueIdSchema = z.string().regex(/^[A-Za-z0-9_-]{8,128}$/);
const ownerUidSchema = z.string().regex(/^[A-Za-z0-9_-]{1,128}$/);
const generationSchema = z.string().regex(/^[1-9][0-9]*$/);

export const createIdentificationRequestInputSchema = z.object({
  expectedOwnerUid: ownerUidSchema,
  requestId: opaqueIdSchema,
  mediaReference: z.object({
    reservationId: opaqueIdSchema,
    generation: generationSchema,
  }).strict(),
  disclosureVersion: z.literal(IDENTIFICATION_DISCLOSURE_VERSION),
}).strict();

export type CreateIdentificationRequestInput = z.infer<
  typeof createIdentificationRequestInputSchema
>;

export type CreateIdentificationRequestCommand = Readonly<{
  ownerUid: string;
  requestId: string;
  mediaReference: IdentificationMediaReference;
  disclosureVersion: number;
  nowMillis: number;
}>;

export type ClaimIdentificationCommand = Readonly<{
  ownerUid: string;
  requestId: string;
  operationKey: string;
  nowMillis: number;
}>;

export type FinalizeIdentificationCommand = Readonly<{
  ownerUid: string;
  requestId: string;
  operationKey: string;
  claimGeneration: number;
  status: IdentificationRequestStatus;
  result: unknown;
  nowMillis: number;
}>;

export type MarkIdentificationSendingCommand = Readonly<{
  ownerUid: string;
  requestId: string;
  operationKey: string;
  claimGeneration: number;
  /** Authoritative runtime clock sampled immediately before the provider send boundary. */
  nowMillis: number;
}>;

export type CancelIdentificationCommand = Readonly<{
  ownerUid: string;
  requestId: string;
  nowMillis: number;
}>;

export type IdentificationClaim =
  | Readonly<{
      kind: "start";
      request: AuthorizedIdentificationRequest;
    }>
  | Readonly<{ kind: "replay"; request: AuthorizedIdentificationRequest; result: unknown }>
  | Readonly<{ kind: "in_flight"; request: AuthorizedIdentificationRequest }>;

/**
 * Admission is deliberately separate from request lifecycle operations: production implementations
 * must validate and link the committed reservation and create/replay the request in one datastore
 * transaction. A linked reservation with no request is not a recoverable state.
 */
export interface IdentificationRequestAdmissionRepository {
  admit(command: CreateIdentificationRequestCommand): Promise<AuthorizedIdentificationRequest>;
}

export interface IdentificationAuthorizationRepository {
  load(ownerUid: string, requestId: string): Promise<AuthorizedIdentificationRequest | null>;
  claim(command: ClaimIdentificationCommand): Promise<IdentificationClaim>;
  markSending(command: MarkIdentificationSendingCommand): Promise<void>;
  finalize(command: FinalizeIdentificationCommand): Promise<AuthorizedIdentificationRequest>;
  cancel(command: CancelIdentificationCommand): Promise<AuthorizedIdentificationRequest>;
}

/**
 * Effective retention boundary: a terminal request keeps its original for 24h after the terminal
 * instant, an abandoned non-terminal request keeps it for 24h after creation. Reads and cleanup
 * both derive the boundary from this one function so no caller can drift.
 */
/** A committed original that was never admitted expires 24h after commit, not reservation. */
export function unadmittedIdentificationOriginalExpiryMillis(
  reservation: Readonly<{ createdAtMillis: number; committedAtMillis: number | null }>,
): number {
  return (reservation.committedAtMillis ?? reservation.createdAtMillis)
    + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS;
}

export function effectiveRetentionExpiryMillis(
  request: Readonly<{
    createdAtMillis: number;
    hardExpiresAtMillis: number;
    retentionExpiresAtMillis: number | null;
  }>,
): number {
  return request.retentionExpiresAtMillis ?? request.hardExpiresAtMillis;
}

export function isIdentificationOriginalReadable(
  request: Readonly<{
    createdAtMillis: number;
    hardExpiresAtMillis: number;
    retentionExpiresAtMillis: number | null;
  }>,
  nowMillis: number,
): boolean {
  return nowMillis < effectiveRetentionExpiryMillis(request);
}

export function parseCreateIdentificationRequestInput(
  value: unknown,
): CreateIdentificationRequestInput {
  const parsed = createIdentificationRequestInputSchema.safeParse(value);
  if (!parsed.success) {
    throw new IdentificationAuthorizationError(
      "invalid-argument",
      "Identification authorization request is invalid",
      { cause: parsed.error },
    );
  }
  return parsed.data;
}
