import {
  IDENTIFICATION_CLAIM_LEASE_MILLIS,
  IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
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
} from "./identification-authorization.js";
import { PrivateMediaError, type PrivateMediaReservationRepository } from "./private-media.js";

/** Mutable in-memory authorization store; state transition is the behavior under test. */
export class MemoryIdentificationAuthorizationRepository
implements IdentificationAuthorizationRepository {
  readonly records = new Map<string, AuthorizedIdentificationRequest>();
  readonly results = new Map<string, unknown>();

  async load(
    ownerUid: string,
    requestId: string,
  ): Promise<AuthorizedIdentificationRequest | null> {
    const current = this.records.get(requestId) ?? null;
    return current !== null && current.ownerUid === ownerUid ? current : null;
  }

  async create(
    command: CreateIdentificationRequestCommand,
  ): Promise<AuthorizedIdentificationRequest> {
    const existing = this.records.get(command.requestId);
    if (existing !== undefined) {
      if (
        existing.ownerUid !== command.ownerUid ||
        existing.mediaReference.reservationId !== command.mediaReference.reservationId ||
        existing.mediaReference.generation !== command.mediaReference.generation ||
        existing.disclosureVersion !== command.disclosureVersion
      ) {
        throw new IdentificationAuthorizationError(
          "permission-denied",
          "Identification request id belongs to another authorization",
        );
      }
      return existing;
    }
    const created: AuthorizedIdentificationRequest = {
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
    this.records.set(created.requestId, created);
    return created;
  }

  async claim(command: ClaimIdentificationCommand): Promise<IdentificationClaim> {
    const current = this.required(command.ownerUid, command.requestId);
    if (isTerminalIdentificationStatus(current.status)) {
      if (current.status === "CANCELLED") {
        throw new IdentificationAuthorizationError(
          "failed-precondition",
          "Identification request was cancelled",
        );
      }
      return {
        kind: "replay",
        request: current,
        result: this.results.get(current.requestId),
      };
    }
    if (command.nowMillis >= current.hardExpiresAtMillis) {
      throw new IdentificationAuthorizationError(
        "failed-precondition",
        "Identification request has expired",
      );
    }
    if (
      current.claimExpiresAtMillis !== null &&
      command.nowMillis < current.claimExpiresAtMillis
    ) {
      if (current.claimOperationKey !== command.operationKey) {
        throw new IdentificationAuthorizationError(
          "failed-precondition",
          "Identification request is already claimed",
        );
      }
      return { kind: "in_flight", request: current };
    }
    if (current.sendState === "SENDING" || current.sendState === "SENT") {
      throw new IdentificationAuthorizationError(
        "failed-precondition",
        "Identification original was already transmitted for this request",
      );
    }
    const claimed: AuthorizedIdentificationRequest = {
      ...current,
      status: "PENDING",
      claimGeneration: current.claimGeneration + 1,
      claimOperationKey: command.operationKey,
      claimExpiresAtMillis: command.nowMillis + IDENTIFICATION_CLAIM_LEASE_MILLIS,
    };
    this.records.set(claimed.requestId, claimed);
    return { kind: "start", request: claimed };
  }

  async markSending(command: MarkIdentificationSendingCommand): Promise<void> {
    const current = this.required(command.ownerUid, command.requestId);
    this.assertClaimHolder(current, command.operationKey, command.claimGeneration);
    if (
      current.claimExpiresAtMillis === null
      || command.nowMillis >= current.claimExpiresAtMillis
      || command.nowMillis >= current.hardExpiresAtMillis
    ) {
      throw new IdentificationAuthorizationError(
        "failed-precondition",
        "Identification claim expired before the provider send boundary",
      );
    }
    this.records.set(current.requestId, { ...current, sendState: "SENDING" });
  }

  async finalize(
    command: FinalizeIdentificationCommand,
  ): Promise<AuthorizedIdentificationRequest> {
    const current = this.required(command.ownerUid, command.requestId);
    this.assertClaimHolder(current, command.operationKey, command.claimGeneration);
    const terminal = isTerminalIdentificationStatus(command.status);
    const finalized: AuthorizedIdentificationRequest = {
      ...current,
      status: command.status,
      claimOperationKey: null,
      claimExpiresAtMillis: null,
      sendState: current.sendState === "SENDING" ? "SENT" : current.sendState,
      terminalAtMillis: terminal ? command.nowMillis : current.terminalAtMillis,
      retentionExpiresAtMillis: terminal
        ? command.nowMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS
        : current.retentionExpiresAtMillis,
    };
    this.records.set(finalized.requestId, finalized);
    this.results.set(finalized.requestId, command.result);
    return finalized;
  }

  async cancel(command: CancelIdentificationCommand): Promise<AuthorizedIdentificationRequest> {
    const current = this.required(command.ownerUid, command.requestId);
    if (current.sendState !== "NOT_SENT") {
      throw new IdentificationAuthorizationError(
        "failed-precondition",
        "Identification original already left the provider send boundary",
      );
    }
    const cancelled: AuthorizedIdentificationRequest = {
      ...current,
      status: "CANCELLED",
      claimOperationKey: null,
      claimExpiresAtMillis: null,
      terminalAtMillis: command.nowMillis,
      retentionExpiresAtMillis: command.nowMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
    };
    this.records.set(cancelled.requestId, cancelled);
    return cancelled;
  }

  private assertClaimHolder(
    current: AuthorizedIdentificationRequest,
    operationKey: string,
    claimGeneration: number,
  ): void {
    if (
      current.claimGeneration !== claimGeneration ||
      current.claimOperationKey !== operationKey
    ) {
      throw new IdentificationAuthorizationError(
        "failed-precondition",
        "Identification claim is stale",
      );
    }
  }

  private required(ownerUid: string, requestId: string): AuthorizedIdentificationRequest {
    const current = this.records.get(requestId);
    if (current === undefined) {
      throw new IdentificationAuthorizationError(
        "not-found",
        "Identification request was not found",
      );
    }
    if (current.ownerUid !== ownerUid) {
      throw new IdentificationAuthorizationError(
        "permission-denied",
        "Identification request is not owned by this account",
      );
    }
    return current;
  }
}

/** In-memory transactional admission fixture. Mutations are rolled back together on any failure. */
export class MemoryIdentificationRequestAdmissionRepository
implements IdentificationRequestAdmissionRepository {
  failNextRequestCreate = false;

  constructor(
    private readonly requests: MemoryIdentificationAuthorizationRepository,
    private readonly media: PrivateMediaReservationRepository & Readonly<{
      records: Map<string, { identificationRequestId: string | null }>;
    }>,
  ) {}

  async admit(command: CreateIdentificationRequestCommand): Promise<AuthorizedIdentificationRequest> {
    const reservation = await this.media.load(command.mediaReference.reservationId);
    if (
      reservation === null
      || reservation.ownerUid !== command.ownerUid
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
    const existing = await this.requests.load(command.ownerUid, command.requestId);
    if (existing !== null) {
      if (
        existing.mediaReference.reservationId !== command.mediaReference.reservationId
        || existing.mediaReference.generation !== command.mediaReference.generation
        || existing.disclosureVersion !== command.disclosureVersion
      ) {
        throw new IdentificationAuthorizationError(
          "permission-denied",
          "Identification request id belongs to another authorization",
        );
      }
      if (reservation.identificationRequestId === null) {
        this.media.records.set(command.mediaReference.reservationId, {
          ...reservation,
          identificationRequestId: command.requestId,
        });
      }
      return existing;
    }
    if (reservation.identificationRequestId !== null) {
      throw new IdentificationAuthorizationError(
        "failed-precondition",
        "Identification reservation link has no matching request",
      );
    }
    const requestSnapshot = new Map(this.requests.records);
    const mediaSnapshot = new Map(this.media.records);
    try {
      if (this.failNextRequestCreate) {
        this.failNextRequestCreate = false;
        throw new PrivateMediaError("unavailable", "Injected request create failure");
      }
      const created = await this.requests.create(command);
      this.media.records.set(command.mediaReference.reservationId, {
        ...reservation,
        identificationRequestId: command.requestId,
      });
      return created;
    } catch (error: unknown) {
      this.requests.records.clear();
      for (const [requestId, request] of requestSnapshot) this.requests.records.set(requestId, request);
      this.media.records.clear();
      for (const [reservationId, current] of mediaSnapshot) this.media.records.set(reservationId, current);
      throw error;
    }
  }
}
