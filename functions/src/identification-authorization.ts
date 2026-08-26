import {
  IDENTIFICATION_DISCLOSURE_VERSION,
  IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
  IdentificationAuthorizationError,
  parseCreateIdentificationRequestInput,
  type AuthorizedIdentificationRequest,
  type IdentificationAuthorizationRepository,
  type IdentificationRequestAdmissionRepository,
} from "./identification-authorization-contract.js";
import type { PrivateMediaAuth } from "./private-media-contract.js";

export {
  IDENTIFICATION_CLAIM_LEASE_MILLIS,
  IDENTIFICATION_CLEANUP_SCAN_LIMIT,
  IDENTIFICATION_DISCLOSURE_VERSION,
  IDENTIFICATION_ORIGINAL_RETENTION_HOURS,
  IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
  IDENTIFICATION_REQUEST_STATUSES,
  IDENTIFICATION_SEND_STATES,
  IdentificationAuthorizationError,
  effectiveRetentionExpiryMillis,
  isIdentificationOriginalReadable,
  isTerminalIdentificationStatus,
  unadmittedIdentificationOriginalExpiryMillis,
  parseCreateIdentificationRequestInput,
} from "./identification-authorization-contract.js";
export type {
  AuthorizedIdentificationRequest,
  CancelIdentificationCommand,
  ClaimIdentificationCommand,
  CreateIdentificationRequestCommand,
  FinalizeIdentificationCommand,
  IdentificationAuthorizationErrorCode,
  IdentificationAuthorizationRepository,
  IdentificationRequestAdmissionRepository,
  IdentificationClaim,
  IdentificationMediaReference,
  IdentificationRequestStatus,
  IdentificationSendState,
  MarkIdentificationSendingCommand,
} from "./identification-authorization-contract.js";

type CreateDependencies = Readonly<{
  admissions: IdentificationRequestAdmissionRepository;
  nowMillis: () => number;
}>;

export type CreatedIdentificationRequest = Readonly<{
  requestId: string;
  disclosureVersion: number;
  acknowledgedAtMillis: number;
  createdAtMillis: number;
  hardExpiresAtMillis: number;
}>;

/**
 * Server-authoritative authorization boundary for one identification original. The client can
 * only reserve, upload and commit private media; admitting that committed original into the
 * identification pipeline happens here, where the owner, the approved disclosure version and the
 * 24-hour hard expiry are decided by the server clock instead of a client-written document.
 */
export async function createIdentificationRequest(
  auth: PrivateMediaAuth | null,
  input: unknown,
  dependencies: CreateDependencies,
): Promise<CreatedIdentificationRequest> {
  if (auth === null) {
    throw new IdentificationAuthorizationError("unauthenticated", "Sign-in is required");
  }
  const parsed = parseCreateIdentificationRequestInput(input);
  if (parsed.expectedOwnerUid !== auth.uid) {
    throw new IdentificationAuthorizationError(
      "permission-denied",
      "Owner does not match authentication",
    );
  }
  if (parsed.disclosureVersion !== IDENTIFICATION_DISCLOSURE_VERSION) {
    throw new IdentificationAuthorizationError(
      "failed-precondition",
      "Identification disclosure version is not approved",
    );
  }
  const nowMillis = dependencies.nowMillis();
  const created = await dependencies.admissions.admit({
    ownerUid: auth.uid,
    requestId: parsed.requestId,
    mediaReference: parsed.mediaReference,
    disclosureVersion: IDENTIFICATION_DISCLOSURE_VERSION,
    nowMillis,
  });
  return acknowledgment(created);
}

function acknowledgment(
  request: AuthorizedIdentificationRequest,
): CreatedIdentificationRequest {
  if (
    request.hardExpiresAtMillis !==
    request.createdAtMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS
  ) {
    throw new IdentificationAuthorizationError(
      "failed-precondition",
      "Stored identification hard expiry does not match the approved retention window",
    );
  }
  return {
    requestId: request.requestId,
    disclosureVersion: request.disclosureVersion,
    acknowledgedAtMillis: request.acknowledgedAtMillis,
    createdAtMillis: request.createdAtMillis,
    hardExpiresAtMillis: request.hardExpiresAtMillis,
  };
}
