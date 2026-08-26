import {
  PRIVATE_MEDIA_SEAL_CONTENT_TYPE,
  PrivateMediaError,
  type ClaimExpiredReservedUploadCommand,
  type CommitPrivateMediaCommand,
  type DeleteGenerationResult,
  type MarkPrivateMediaSealedCommand,
  type PrivateMediaObject,
  type PrivateMediaObjectStore,
  type PrivateMediaReservation,
  type PrivateMediaReservationRepository,
  type ResolvePrivateMediaCommand,
} from "./private-media.js";

/** Mutable reservation repository; mutation is the persistence behavior under test. */
export class MemoryPrivateMediaRepository implements PrivateMediaReservationRepository {
  readonly records = new Map<string, PrivateMediaReservation>();
  private readonly idempotency = new Map<string, string>();
  locked = false;
  failNextSeal = false;

  async load(reservationId: string): Promise<PrivateMediaReservation | null> {
    return this.records.get(reservationId) ?? null;
  }

  async reserve(reservation: PrivateMediaReservation): Promise<PrivateMediaReservation> {
    this.assertMutable(reservation.ownerUid);
    const key = `${reservation.ownerUid}:${reservation.idempotencyKeyHash}`;
    const existingId = this.idempotency.get(key);
    if (existingId !== undefined) {
      const existing = this.records.get(existingId);
      if (existing === undefined || existing.requestHash !== reservation.requestHash) {
        throw new PrivateMediaError("invalid-argument", "Idempotency key belongs to another request");
      }
      return existing;
    }
    this.records.set(reservation.reservationId, reservation);
    this.idempotency.set(key, reservation.reservationId);
    return reservation;
  }

  async commit(command: CommitPrivateMediaCommand): Promise<PrivateMediaReservation> {
    this.assertMutable(command.ownerUid);
    const current = this.required(command.reservationId);
    if (current.ownerUid !== command.ownerUid) {
      throw new PrivateMediaError("permission-denied", "Reservation is not owned by this account");
    }
    if (current.cleanupClaimReason !== null && current.cleanupClaimReason !== undefined) {
      throw new PrivateMediaError("failed-precondition", "Reservation cleanup was already claimed");
    }
    if (current.state === "COMMITTED" && current.objectGeneration === command.generation) {
      return current;
    }
    if (current.state !== "RESERVED") {
      throw new PrivateMediaError("failed-precondition", "Reservation cannot be committed");
    }
    const committed: PrivateMediaReservation = {
      ...current,
      state: "COMMITTED",
      objectGeneration: command.generation,
      committedAtMillis: command.committedAtMillis,
    };
    this.records.set(current.reservationId, committed);
    return committed;
  }

  async claimExpiredReservedUpload(
    command: ClaimExpiredReservedUploadCommand,
  ): Promise<PrivateMediaReservation | null> {
    const current = this.records.get(command.reservation.reservationId);
    if (
      current === undefined ||
      (current.state !== "RESERVED" &&
        !(current.state === "SEALED" &&
          current.cleanupClaimReason === "EXPIRED_RESERVED_UPLOAD")) ||
      current.expiresAtMillis > command.nowMillis ||
      current.identificationRequestId !== null ||
      (current.cleanupClaimReason !== null &&
        current.cleanupClaimReason !== undefined &&
        current.cleanupClaimReason !== "EXPIRED_RESERVED_UPLOAD")
    ) return null;
    const claimed: PrivateMediaReservation = {
      ...current,
      cleanupClaimGeneration: null,
      cleanupClaimReason: "EXPIRED_RESERVED_UPLOAD",
    };
    this.records.set(current.reservationId, claimed);
    return claimed;
  }

  async resolve(command: ResolvePrivateMediaCommand) {
    const current = this.records.get(command.reference.reservationId);
    if (
      current === undefined ||
      current.ownerUid !== command.ownerUid ||
      current.mediaKind !== command.mediaKind ||
      current.state !== "COMMITTED" ||
      current.objectGeneration !== command.reference.generation
    ) return null;
    return {
      reference: command.reference,
      ownerUid: current.ownerUid,
      mediaKind: current.mediaKind,
      objectPath: current.objectPath,
      contentType: current.contentType,
      byteSize: current.byteSize,
    };
  }

  async listOwner(ownerUid: string): Promise<readonly PrivateMediaReservation[]> {
    return [...this.records.values()].filter((reservation) => reservation.ownerUid === ownerUid);
  }

  async markSealed(command: MarkPrivateMediaSealedCommand): Promise<void> {
    if (this.failNextSeal) {
      this.failNextSeal = false;
      throw new PrivateMediaError("unavailable", "Injected seal persistence crash");
    }
    const current = this.required(command.expectedReservation.reservationId);
    if (
      current.state !== command.expectedReservation.state ||
      current.objectGeneration !== command.expectedReservation.objectGeneration ||
      current.cleanupClaimGeneration !== command.expectedReservation.cleanupClaimGeneration ||
      current.cleanupClaimReason !== command.expectedReservation.cleanupClaimReason
    ) {
      throw new PrivateMediaError("failed-precondition", "Reservation changed before sealing");
    }
    this.records.set(current.reservationId, {
      ...current,
      state: "SEALED",
      objectGeneration: null,
      sealedGeneration: command.sealedGeneration,
      sealedAtMillis: command.sealedAtMillis,
    });
  }

  async shouldDeleteFinalized(reservationId: string, ownerUid: string): Promise<boolean> {
    const reservation = this.records.get(reservationId);
    return reservation?.ownerUid === ownerUid && (this.locked || reservation.state === "SEALED");
  }

  private required(reservationId: string): PrivateMediaReservation {
    const reservation = this.records.get(reservationId);
    if (reservation === undefined) throw new PrivateMediaError("not-found", "Reservation was not found");
    return reservation;
  }

  private assertMutable(_ownerUid: string): void {
    if (this.locked) throw new PrivateMediaError("failed-precondition", "Account is immutable");
  }
}

/** Mutable generation-aware object fake; mutation models conditional object operations. */
export class FakePrivateMediaObjectStore implements PrivateMediaObjectStore {
  readonly objects = new Map<string, PrivateMediaObject>();
  private nextGeneration = 1;
  beforeNextDelete: (() => void) | null = null;
  beforeNextSealCreate: (() => void) | null = null;

  admitUpload(object: Omit<PrivateMediaObject, "generation">): () => "created" | "precondition_failed" {
    return () => {
      if (this.objects.has(object.path)) return "precondition_failed";
      this.objects.set(object.path, { ...object, generation: String(this.nextGeneration++) });
      return "created";
    };
  }

  async inspect(path: string): Promise<PrivateMediaObject | null> {
    return this.objects.get(path) ?? null;
  }

  async deleteGeneration(path: string, generation: string): Promise<DeleteGenerationResult> {
    const beforeDelete = this.beforeNextDelete;
    this.beforeNextDelete = null;
    beforeDelete?.();
    const current = this.objects.get(path);
    if (current === undefined) return "absent";
    if (current.generation !== generation) return "generation_changed";
    this.objects.delete(path);
    return "deleted";
  }

  async createSeal(path: string) {
    const beforeCreate = this.beforeNextSealCreate;
    this.beforeNextSealCreate = null;
    beforeCreate?.();
    if (this.objects.has(path)) return { kind: "occupied" as const };
    const seal: PrivateMediaObject = {
      path,
      generation: String(this.nextGeneration++),
      byteSize: 0,
      contentType: PRIVATE_MEDIA_SEAL_CONTENT_TYPE,
      customMetadata: { privateMediaSeal: "true" },
    };
    this.objects.set(path, seal);
    return { kind: "created" as const, generation: seal.generation };
  }
}
