import { Timestamp, type Firestore, type Transaction } from "firebase-admin/firestore";
import { z } from "zod";
import { runAccountMutationTransaction } from "./account-mutation-lock.js";
import { parseStoredPrivateMediaReservation } from "./firestore-private-media.js";
import {
  IDENTIFICATION_CLAIM_LEASE_MILLIS,
  IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
  IDENTIFICATION_REQUEST_STATUSES,
  IDENTIFICATION_SEND_STATES,
  IdentificationAuthorizationError,
  isTerminalIdentificationStatus,
  unadmittedIdentificationOriginalExpiryMillis,
  type AuthorizedIdentificationRequest,
  type CancelIdentificationCommand,
  type ClaimIdentificationCommand,
  type CreateIdentificationRequestCommand,
  type FinalizeIdentificationCommand,
  type IdentificationAuthorizationRepository,
  type IdentificationRequestAdmissionRepository,
  type IdentificationClaim,
  type MarkIdentificationSendingCommand,
} from "./identification-authorization-contract.js";

const timestampSchema = z.instanceof(Timestamp);
const storedRequestSchema = z.object({
  schemaVersion: z.literal(1),
  requestId: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/),
  ownerUid: z.string().regex(/^[A-Za-z0-9_-]{1,128}$/),
  mediaReference: z.object({
    reservationId: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/),
    generation: z.string().regex(/^[1-9][0-9]*$/),
  }).strict(),
  disclosureVersion: z.number().int().min(1),
  status: z.enum(IDENTIFICATION_REQUEST_STATUSES),
  claimGeneration: z.number().int().min(0),
  claimOperationKey: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/).nullable(),
  claimExpiresAt: timestampSchema.nullable(),
  sendState: z.enum(IDENTIFICATION_SEND_STATES),
  acknowledgedAt: timestampSchema,
  createdAt: timestampSchema,
  hardExpiresAt: timestampSchema,
  terminalAt: timestampSchema.nullable(),
  retentionExpiresAt: timestampSchema.nullable(),
  identificationResult: z.unknown().optional(),
}).strict();

export class FirestoreIdentificationAuthorizationRepository
implements IdentificationAuthorizationRepository, IdentificationRequestAdmissionRepository {
  constructor(
    private readonly firestore: Firestore,
    private readonly hooks: Readonly<{ beforeRequestCreate?: () => void | Promise<void> }> = {},
  ) {}

  async load(
    ownerUid: string,
    requestId: string,
  ): Promise<AuthorizedIdentificationRequest | null> {
    const snapshot = await this.reference(ownerUid, requestId).get();
    if (!snapshot.exists) return null;
    const stored = parseStoredIdentificationRequest(snapshot.data());
    if (stored.request.ownerUid !== ownerUid) {
      throw new IdentificationAuthorizationError(
        "permission-denied",
        "Identification request is not owned by this account",
      );
    }
    return stored.request;
  }

  /**
   * Atomic admission boundary. Reservation validation/link and request creation are in the same
   * Firestore transaction, so a create failure or transaction retry cannot strand a linked
   * committed original that cleanup cannot discover.
   */
  async admit(
    command: CreateIdentificationRequestCommand,
  ): Promise<AuthorizedIdentificationRequest> {
    return runAccountMutationTransaction(
      this.firestore,
      command.ownerUid,
      async (transaction) => {
        const reservationReference = this.firestore.doc(
          `privateMediaReservations/${command.mediaReference.reservationId}`,
        );
        const requestReference = this.reference(command.ownerUid, command.requestId);
        const [reservationSnapshot, requestSnapshot] = await Promise.all([
          transaction.get(reservationReference),
          transaction.get(requestReference),
        ]);
        if (!reservationSnapshot.exists) {
          throw new IdentificationAuthorizationError(
            "failed-precondition",
            "Identification original is not a committed reservation for this owner",
          );
        }
        let reservation;
        try {
          reservation = parseStoredPrivateMediaReservation(reservationSnapshot.data());
        } catch {
          throw new IdentificationAuthorizationError(
            "failed-precondition",
            "Identification original is not a committed reservation for this owner",
          );
        }
        if (
          reservation.ownerUid !== command.ownerUid
          || reservation.mediaKind !== "IDENTIFICATION_ORIGINAL"
          || reservation.state !== "COMMITTED"
          || reservation.objectGeneration !== command.mediaReference.generation
          || command.nowMillis >= unadmittedIdentificationOriginalExpiryMillis(reservation)
          || (
            reservation.identificationRequestId !== null
            && reservation.identificationRequestId !== command.requestId
          )
        ) {
          throw new IdentificationAuthorizationError(
            "failed-precondition",
            "Identification original is not a committed reservation for this owner",
          );
        }
        if (requestSnapshot.exists) {
          const existing = parseStoredIdentificationRequest(requestSnapshot.data()).request;
          assertExactReplay(existing, command);
          // This only heals documents created before atomic admission shipped; it is still one
          // transaction and never creates a new link without its matching request.
          if (reservation.identificationRequestId === null) {
            transaction.update(reservationReference, { identificationRequestId: command.requestId });
          }
          return existing;
        }
        if (reservation.identificationRequestId !== null) {
          throw new IdentificationAuthorizationError(
            "failed-precondition",
            "Identification reservation link has no matching request",
          );
        }
        const created = newAuthorizedRequest(command);
        transaction.update(reservationReference, { identificationRequestId: command.requestId });
        await this.hooks.beforeRequestCreate?.();
        transaction.create(requestReference, storedIdentificationRequest(created));
        return created;
      },
    );
  }

  async claim(command: ClaimIdentificationCommand): Promise<IdentificationClaim> {
    return runAccountMutationTransaction(
      this.firestore,
      command.ownerUid,
      async (transaction) => {
        const reference = this.reference(command.ownerUid, command.requestId);
        const stored = await this.require(transaction, command.ownerUid, command.requestId);
        if (isTerminalIdentificationStatus(stored.request.status)) {
          if (stored.request.status === "CANCELLED") {
            throw new IdentificationAuthorizationError(
              "failed-precondition",
              "Identification request was cancelled",
            );
          }
          return { kind: "replay", request: stored.request, result: stored.result };
        }
        if (command.nowMillis >= stored.request.hardExpiresAtMillis) {
          throw new IdentificationAuthorizationError(
            "failed-precondition",
            "Identification request has expired",
          );
        }
        if (
          stored.request.claimExpiresAtMillis !== null &&
          command.nowMillis < stored.request.claimExpiresAtMillis
        ) {
          if (stored.request.claimOperationKey !== command.operationKey) {
            throw new IdentificationAuthorizationError(
              "failed-precondition",
              "Identification request is already claimed",
            );
          }
          return { kind: "in_flight", request: stored.request };
        }
        if (stored.request.sendState !== "NOT_SENT") {
          throw new IdentificationAuthorizationError(
            "failed-precondition",
            "Identification original was already transmitted for this request",
          );
        }
        const claimed: AuthorizedIdentificationRequest = {
          ...stored.request,
          status: "PENDING",
          claimGeneration: stored.request.claimGeneration + 1,
          claimOperationKey: command.operationKey,
          claimExpiresAtMillis: command.nowMillis + IDENTIFICATION_CLAIM_LEASE_MILLIS,
        };
        transaction.update(reference, {
          status: claimed.status,
          claimGeneration: claimed.claimGeneration,
          claimOperationKey: claimed.claimOperationKey,
          claimExpiresAt: Timestamp.fromMillis(command.nowMillis + IDENTIFICATION_CLAIM_LEASE_MILLIS),
        });
        return { kind: "start", request: claimed };
      },
    );
  }

  async markSending(command: MarkIdentificationSendingCommand): Promise<void> {
    await runAccountMutationTransaction(this.firestore, command.ownerUid, async (transaction) => {
      const reference = this.reference(command.ownerUid, command.requestId);
      const stored = await this.require(transaction, command.ownerUid, command.requestId);
      assertClaimHolder(stored.request, command.operationKey, command.claimGeneration);
      await this.assertOriginalAvailable(transaction, stored.request);
      if (
        stored.request.claimExpiresAtMillis === null
        || command.nowMillis >= stored.request.claimExpiresAtMillis
        || command.nowMillis >= stored.request.hardExpiresAtMillis
      ) {
        throw new IdentificationAuthorizationError(
          "failed-precondition",
          "Identification claim expired before the provider send boundary",
        );
      }
      transaction.update(reference, { sendState: "SENDING" });
    });
  }

  async finalize(
    command: FinalizeIdentificationCommand,
  ): Promise<AuthorizedIdentificationRequest> {
    return runAccountMutationTransaction(
      this.firestore,
      command.ownerUid,
      async (transaction) => {
        const reference = this.reference(command.ownerUid, command.requestId);
        const stored = await this.require(transaction, command.ownerUid, command.requestId);
        assertClaimHolder(stored.request, command.operationKey, command.claimGeneration);
        await this.assertOriginalAvailable(transaction, stored.request);
        const terminal = isTerminalIdentificationStatus(command.status);
        const finalized: AuthorizedIdentificationRequest = {
          ...stored.request,
          status: command.status,
          claimOperationKey: null,
          claimExpiresAtMillis: null,
          sendState: stored.request.sendState === "SENDING" ? "SENT" : stored.request.sendState,
          terminalAtMillis: terminal ? command.nowMillis : stored.request.terminalAtMillis,
          retentionExpiresAtMillis: terminal
            ? command.nowMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS
            : stored.request.retentionExpiresAtMillis,
        };
        transaction.update(reference, {
          ...retentionFields(finalized),
          identificationResult: command.result ?? null,
        });
        return finalized;
      },
    );
  }

  async cancel(command: CancelIdentificationCommand): Promise<AuthorizedIdentificationRequest> {
    return runAccountMutationTransaction(
      this.firestore,
      command.ownerUid,
      async (transaction) => {
        const reference = this.reference(command.ownerUid, command.requestId);
        const stored = await this.require(transaction, command.ownerUid, command.requestId);
        if (stored.request.status === "CANCELLED") return stored.request;
        await this.assertOriginalAvailable(transaction, stored.request);
        if (stored.request.sendState !== "NOT_SENT") {
          throw new IdentificationAuthorizationError(
            "failed-precondition",
            "Identification original already left the provider send boundary",
          );
        }
        const cancelled: AuthorizedIdentificationRequest = {
          ...stored.request,
          status: "CANCELLED",
          claimOperationKey: null,
          claimExpiresAtMillis: null,
          terminalAtMillis: command.nowMillis,
          retentionExpiresAtMillis:
            command.nowMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
        };
        transaction.update(reference, retentionFields(cancelled));
        return cancelled;
      },
    );
  }

  private async assertOriginalAvailable(
    transaction: Transaction,
    request: AuthorizedIdentificationRequest,
  ): Promise<void> {
    const snapshot = await transaction.get(
      this.firestore.doc(
        `privateMediaReservations/${request.mediaReference.reservationId}`,
      ),
    );
    if (!snapshot.exists) originalCleanupClaimed();
    const reservation = parseStoredPrivateMediaReservation(snapshot.data());
    if (
      reservation.ownerUid !== request.ownerUid
      || reservation.mediaKind !== "IDENTIFICATION_ORIGINAL"
      || reservation.identificationRequestId !== request.requestId
      || reservation.state !== "COMMITTED"
      || reservation.objectGeneration !== request.mediaReference.generation
      || reservation.cleanupClaimGeneration !== null
      || reservation.cleanupClaimReason !== null
    ) originalCleanupClaimed();
  }

  private async require(
    transaction: Transaction,
    ownerUid: string,
    requestId: string,
  ): Promise<Readonly<{ request: AuthorizedIdentificationRequest; result: unknown }>> {
    const snapshot = await transaction.get(this.reference(ownerUid, requestId));
    if (!snapshot.exists) {
      throw new IdentificationAuthorizationError(
        "not-found",
        "Identification request was not found",
      );
    }
    const stored = parseStoredIdentificationRequest(snapshot.data());
    if (stored.request.ownerUid !== ownerUid) {
      throw new IdentificationAuthorizationError(
        "permission-denied",
        "Identification request is not owned by this account",
      );
    }
    return stored;
  }

  private reference(ownerUid: string, requestId: string) {
    return this.firestore.doc(`users/${ownerUid}/identificationRequests/${requestId}`);
  }
}

function originalCleanupClaimed(): never {
  throw new IdentificationAuthorizationError(
    "failed-precondition",
    "Identification original cleanup was already claimed",
  );
}

function assertExactReplay(
  existing: AuthorizedIdentificationRequest,
  command: CreateIdentificationRequestCommand,
): void {
  if (
    existing.ownerUid !== command.ownerUid
    || existing.mediaReference.reservationId !== command.mediaReference.reservationId
    || existing.mediaReference.generation !== command.mediaReference.generation
    || existing.disclosureVersion !== command.disclosureVersion
  ) {
    throw new IdentificationAuthorizationError(
      "permission-denied",
      "Identification request id belongs to another authorization",
    );
  }
}

function newAuthorizedRequest(
  command: CreateIdentificationRequestCommand,
): AuthorizedIdentificationRequest {
  return {
    schemaVersion: 1,
    requestId: command.requestId,
    ownerUid: command.ownerUid,
    mediaReference: command.mediaReference,
    disclosureVersion: command.disclosureVersion,
    status: "APPROVED",
    claimGeneration: 0,
    claimOperationKey: null,
    claimExpiresAtMillis: null,
    sendState: "NOT_SENT",
    acknowledgedAtMillis: command.nowMillis,
    createdAtMillis: command.nowMillis,
    hardExpiresAtMillis: command.nowMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
    terminalAtMillis: null,
    retentionExpiresAtMillis: null,
  };
}

function assertClaimHolder(
  request: AuthorizedIdentificationRequest,
  operationKey: string,
  claimGeneration: number,
): void {
  if (request.claimGeneration !== claimGeneration || request.claimOperationKey !== operationKey) {
    throw new IdentificationAuthorizationError(
      "failed-precondition",
      "Identification claim is stale",
    );
  }
}

function retentionFields(request: AuthorizedIdentificationRequest) {
  return {
    status: request.status,
    claimOperationKey: request.claimOperationKey,
    claimExpiresAt: request.claimExpiresAtMillis === null
      ? null
      : Timestamp.fromMillis(request.claimExpiresAtMillis),
    sendState: request.sendState,
    terminalAt: request.terminalAtMillis === null
      ? null
      : Timestamp.fromMillis(request.terminalAtMillis),
    retentionExpiresAt: request.retentionExpiresAtMillis === null
      ? null
      : Timestamp.fromMillis(request.retentionExpiresAtMillis),
  };
}

export function parseStoredIdentificationRequest(
  value: unknown,
): Readonly<{ request: AuthorizedIdentificationRequest; result: unknown }> {
  const parsed = storedRequestSchema.safeParse(value);
  if (!parsed.success) {
    throw new IdentificationAuthorizationError(
      "failed-precondition",
      "Stored identification request is malformed",
      { cause: parsed.error },
    );
  }
  const data = parsed.data;
  return {
    request: {
      schemaVersion: 1,
      requestId: data.requestId,
      ownerUid: data.ownerUid,
      mediaReference: data.mediaReference,
      disclosureVersion: data.disclosureVersion,
      status: data.status,
      claimGeneration: data.claimGeneration,
      claimOperationKey: data.claimOperationKey,
      claimExpiresAtMillis: data.claimExpiresAt?.toMillis() ?? null,
      sendState: data.sendState,
      acknowledgedAtMillis: data.acknowledgedAt.toMillis(),
      createdAtMillis: data.createdAt.toMillis(),
      hardExpiresAtMillis: data.hardExpiresAt.toMillis(),
      terminalAtMillis: data.terminalAt?.toMillis() ?? null,
      retentionExpiresAtMillis: data.retentionExpiresAt?.toMillis() ?? null,
    },
    result: data.identificationResult ?? null,
  };
}

export function storedIdentificationRequest(request: AuthorizedIdentificationRequest) {
  return {
    schemaVersion: 1,
    requestId: request.requestId,
    ownerUid: request.ownerUid,
    mediaReference: request.mediaReference,
    disclosureVersion: request.disclosureVersion,
    status: request.status,
    claimGeneration: request.claimGeneration,
    claimOperationKey: request.claimOperationKey,
    claimExpiresAt: request.claimExpiresAtMillis === null
      ? null
      : Timestamp.fromMillis(request.claimExpiresAtMillis),
    sendState: request.sendState,
    acknowledgedAt: Timestamp.fromMillis(request.acknowledgedAtMillis),
    createdAt: Timestamp.fromMillis(request.createdAtMillis),
    hardExpiresAt: Timestamp.fromMillis(request.hardExpiresAtMillis),
    terminalAt: request.terminalAtMillis === null
      ? null
      : Timestamp.fromMillis(request.terminalAtMillis),
    retentionExpiresAt: request.retentionExpiresAtMillis === null
      ? null
      : Timestamp.fromMillis(request.retentionExpiresAtMillis),
  };
}
