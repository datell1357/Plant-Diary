import { createHash } from "node:crypto";
import {
  FieldPath,
  Timestamp,
  type Firestore,
  type Query,
} from "firebase-admin/firestore";
import { z } from "zod";
import {
  assertAccountMutationAllowed,
  isAccountDeletionIrreversible,
} from "./account-mutation-lock.js";
import {
  PRIVATE_MEDIA_KINDS,
  PRIVATE_MEDIA_STATES,
  PrivateMediaError,
  type ClaimExpiredReservedUploadCommand,
  type CommitPrivateMediaCommand,
  type MarkPrivateMediaSealedCommand,
  type PrivateMediaReservation,
  type PrivateMediaReservationRepository,
  type ResolvePrivateMediaCommand,
  type ResolvedPrivateMedia,
} from "./private-media-contract.js";

const PAGE_SIZE = 100;
const timestampSchema = z.instanceof(Timestamp);
const storedReservationSchema = z.object({
  schemaVersion: z.literal(1),
  reservationId: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/),
  ownerUid: z.string().regex(/^[A-Za-z0-9_-]{1,128}$/),
  mediaKind: z.enum(PRIVATE_MEDIA_KINDS),
  contentType: z.string().regex(/^image\/(jpeg|png|webp|heif|heic)$/),
  byteSize: z.number().int().min(1).max(20 * 1024 * 1024),
  objectPath: z.string().regex(/^private-media-v2\/[A-Za-z0-9_-]{8,128}$/),
  identificationRequestId: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/).nullable().optional(),
  idempotencyKeyHash: z.string().regex(/^[a-f0-9]{64}$/),
  requestHash: z.string().regex(/^[a-f0-9]{64}$/),
  state: z.enum(PRIVATE_MEDIA_STATES),
  objectGeneration: z.string().regex(/^[1-9][0-9]*$/).nullable(),
  sealedGeneration: z.string().regex(/^[1-9][0-9]*$/).nullable(),
  cleanupClaimGeneration: z.string().regex(/^[1-9][0-9]*$/).nullable().optional(),
  cleanupClaimReason: z.enum([
    "EXPIRED_RESERVED_UPLOAD",
    "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL",
    "IDENTIFICATION_REQUEST_RETENTION",
  ]).nullable().optional(),
  createdAt: timestampSchema,
  expiresAt: timestampSchema,
  committedAt: timestampSchema.nullable(),
  sealedAt: timestampSchema.nullable(),
}).strict();
const receiptSchema = z.object({
  schemaVersion: z.literal(1),
  ownerUid: z.string().regex(/^[A-Za-z0-9_-]{1,128}$/),
  idempotencyKeyHash: z.string().regex(/^[a-f0-9]{64}$/),
  requestHash: z.string().regex(/^[a-f0-9]{64}$/),
  reservationId: z.string().regex(/^[A-Za-z0-9_-]{8,128}$/),
  createdAt: timestampSchema,
}).strict();

export class FirestorePrivateMediaReservationRepository
implements PrivateMediaReservationRepository {
  constructor(
    private readonly firestore: Firestore,
    private readonly hooks: Readonly<{
      beforeExpiredReservedClaim?: () => void | Promise<void>;
      afterExpiredReservedClaim?: () => void | Promise<void>;
    }> = {},
  ) {}

  async load(reservationId: string): Promise<PrivateMediaReservation | null> {
    const snapshot = await this.reference(reservationId).get();
    return snapshot.exists ? parseStoredPrivateMediaReservation(snapshot.data()) : null;
  }

  async reserve(reservation: PrivateMediaReservation): Promise<PrivateMediaReservation> {
    return this.firestore.runTransaction(async (transaction) => {
      await assertAccountMutationAllowed(transaction, this.firestore, reservation.ownerUid);
      const receipt = this.receipt(reservation.ownerUid, reservation.idempotencyKeyHash);
      const receiptSnapshot = await transaction.get(receipt);
      if (receiptSnapshot.exists) {
        const parsed = receiptSchema.safeParse(receiptSnapshot.data());
        if (
          !parsed.success ||
          parsed.data.ownerUid !== reservation.ownerUid ||
          parsed.data.idempotencyKeyHash !== reservation.idempotencyKeyHash
        ) malformed(parsed.success ? undefined : parsed.error);
        if (parsed.data.requestHash !== reservation.requestHash) {
          throw new PrivateMediaError(
            "invalid-argument",
            "Idempotency key belongs to another private media request",
          );
        }
        const current = await transaction.get(this.reference(parsed.data.reservationId));
        if (!current.exists) malformed();
        const replay = parseStoredPrivateMediaReservation(current.data());
        if (
          replay.reservationId !== parsed.data.reservationId ||
          replay.ownerUid !== reservation.ownerUid ||
          replay.idempotencyKeyHash !== reservation.idempotencyKeyHash ||
          replay.requestHash !== reservation.requestHash
        ) malformed();
        return replay;
      }
      transaction.create(this.reference(reservation.reservationId), storedReservation(reservation));
      transaction.create(receipt, receiptSchema.parse({
        schemaVersion: 1,
        ownerUid: reservation.ownerUid,
        idempotencyKeyHash: reservation.idempotencyKeyHash,
        requestHash: reservation.requestHash,
        reservationId: reservation.reservationId,
        createdAt: Timestamp.fromMillis(reservation.createdAtMillis),
      }));
      return reservation;
    });
  }

  async commit(command: CommitPrivateMediaCommand): Promise<PrivateMediaReservation> {
    return this.firestore.runTransaction(async (transaction) => {
      await assertAccountMutationAllowed(transaction, this.firestore, command.ownerUid);
      const reference = this.reference(command.reservationId);
      const snapshot = await transaction.get(reference);
      if (!snapshot.exists) throw new PrivateMediaError("not-found", "Reservation was not found");
      const current = parseStoredPrivateMediaReservation(snapshot.data());
      if (current.ownerUid !== command.ownerUid) {
        throw new PrivateMediaError("permission-denied", "Reservation is not owned by this account");
      }
      if (current.cleanupClaimReason !== null) {
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
      transaction.set(reference, storedReservation(committed), { merge: false });
      return committed;
    });
  }

  async claimExpiredReservedUpload(
    command: ClaimExpiredReservedUploadCommand,
  ): Promise<PrivateMediaReservation | null> {
    await this.hooks.beforeExpiredReservedClaim?.();
    const claimed = await this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.reservation.reservationId);
      const snapshot = await transaction.get(reference);
      if (!snapshot.exists) return null;
      const current = parseStoredPrivateMediaReservation(snapshot.data());
      if (!sameReservationIdentity(current, command.reservation)) {
        throw new PrivateMediaError(
          "failed-precondition",
          "Expired upload cleanup reservation identity changed",
        );
      }
      if (
        (current.state === "RESERVED" || current.state === "SEALED") &&
        current.cleanupClaimReason === "EXPIRED_RESERVED_UPLOAD" &&
        current.cleanupClaimGeneration === null
      ) return current;
      if (
        current.state !== "RESERVED" ||
        current.expiresAtMillis > command.nowMillis ||
        current.identificationRequestId !== null ||
        current.cleanupClaimReason !== null
      ) return null;
      const next: PrivateMediaReservation = {
        ...current,
        cleanupClaimGeneration: null,
        cleanupClaimReason: "EXPIRED_RESERVED_UPLOAD",
      };
      transaction.update(reference, {
        cleanupClaimGeneration: null,
        cleanupClaimReason: next.cleanupClaimReason,
      });
      return next;
    });
    if (claimed !== null) await this.hooks.afterExpiredReservedClaim?.();
    return claimed;
  }

  async resolve(command: ResolvePrivateMediaCommand): Promise<ResolvedPrivateMedia | null> {
    const reservation = await this.load(command.reference.reservationId);
    if (
      reservation === null ||
      reservation.ownerUid !== command.ownerUid ||
      reservation.mediaKind !== command.mediaKind ||
      reservation.state !== "COMMITTED" ||
      reservation.objectGeneration !== command.reference.generation
    ) return null;
    return {
      reference: command.reference,
      ownerUid: reservation.ownerUid,
      mediaKind: reservation.mediaKind,
      objectPath: reservation.objectPath,
      contentType: reservation.contentType,
      byteSize: reservation.byteSize,
    };
  }

  async listOwner(ownerUid: string): Promise<readonly PrivateMediaReservation[]> {
    const reservations: PrivateMediaReservation[] = [];
    let query: Query = this.firestore.collection("privateMediaReservations")
      .where("ownerUid", "==", ownerUid)
      .orderBy(FieldPath.documentId())
      .limit(PAGE_SIZE);
    while (true) {
      const page = await query.get();
      reservations.push(...page.docs.map((document) => parseStoredPrivateMediaReservation(document.data())));
      const cursor = page.docs.at(-1);
      if (cursor === undefined || page.size < PAGE_SIZE) return reservations;
      query = this.firestore.collection("privateMediaReservations")
        .where("ownerUid", "==", ownerUid)
        .orderBy(FieldPath.documentId())
        .startAfter(cursor)
        .limit(PAGE_SIZE);
    }
  }

  async purgeOwnerMetadata(ownerUid: string): Promise<void> {
    const reservations = this.firestore.collection("privateMediaReservations")
      .where("ownerUid", "==", ownerUid);
    let reservationPage = await reservations.limit(PAGE_SIZE).get();
    while (!reservationPage.empty) {
      for (const document of reservationPage.docs) {
        const reservation = parseStoredPrivateMediaReservation(document.data());
        if (reservation.ownerUid !== ownerUid || reservation.state !== "SEALED") {
          throw new PrivateMediaError(
            "failed-precondition",
            "Owner private media metadata cannot be purged before seal convergence",
          );
        }
      }
      const batch = this.firestore.batch();
      for (const document of reservationPage.docs) batch.delete(document.ref);
      await batch.commit();
      reservationPage = await reservations.limit(PAGE_SIZE).get();
    }

    const receipts = this.firestore.collection("privateMediaReservationReceipts")
      .where("ownerUid", "==", ownerUid);
    let receiptPage = await receipts.limit(PAGE_SIZE).get();
    while (!receiptPage.empty) {
      const batch = this.firestore.batch();
      for (const document of receiptPage.docs) batch.delete(document.ref);
      await batch.commit();
      receiptPage = await receipts.limit(PAGE_SIZE).get();
    }

    const [remainingReservations, remainingReceipts] = await Promise.all([
      reservations.limit(1).get(),
      receipts.limit(1).get(),
    ]);
    if (!remainingReservations.empty || !remainingReceipts.empty) {
      throw new PrivateMediaError(
        "failed-precondition",
        "Owner private media metadata purge did not converge",
      );
    }
  }

  async markSealed(command: MarkPrivateMediaSealedCommand): Promise<void> {
    await this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.expectedReservation.reservationId);
      const snapshot = await transaction.get(reference);
      if (!snapshot.exists) throw new PrivateMediaError("not-found", "Reservation was not found");
      const current = parseStoredPrivateMediaReservation(snapshot.data());
      if (!sameSealExpectation(current, command.expectedReservation)) {
        throw new PrivateMediaError(
          "failed-precondition",
          "Reservation changed before seal metadata was persisted",
        );
      }
      transaction.set(reference, storedReservation({
        ...current,
        state: "SEALED",
        objectGeneration: null,
        sealedGeneration: command.sealedGeneration,
        sealedAtMillis: command.sealedAtMillis,
      }), { merge: false });
    });
  }

  async shouldDeleteFinalized(reservationId: string, ownerUid: string): Promise<boolean> {
    const [reservation, deletion] = await Promise.all([
      this.reference(reservationId).get(),
      this.firestore.doc(`accountDeletionRequests/${ownerUid}`).get(),
    ]);
    if (!reservation.exists) return false;
    const parsed = parseStoredPrivateMediaReservation(reservation.data());
    return parsed.ownerUid === ownerUid && (
      parsed.state === "SEALED" ||
      (deletion.exists && isAccountDeletionIrreversible(
        deletion.get("status"),
        deletion.get("completedScopes"),
      ))
    );
  }

  private reference(reservationId: string) {
    return this.firestore.doc(`privateMediaReservations/${reservationId}`);
  }

  private receipt(ownerUid: string, idempotencyKeyHash: string) {
    return this.firestore.doc(
      `privateMediaReservationReceipts/${privateMediaReceiptId(ownerUid, idempotencyKeyHash)}`,
    );
  }
}

export function parseStoredPrivateMediaReservation(value: unknown): PrivateMediaReservation {
  const parsed = storedReservationSchema.safeParse(value);
  if (!parsed.success) malformed(parsed.error);
  const data = parsed.data;
  if (data.objectPath !== `private-media-v2/${data.reservationId}`) malformed();
  return {
    schemaVersion: 1,
    reservationId: data.reservationId,
    ownerUid: data.ownerUid,
    mediaKind: data.mediaKind,
    contentType: data.contentType,
    byteSize: data.byteSize,
    objectPath: data.objectPath,
    identificationRequestId: data.identificationRequestId ?? null,
    idempotencyKeyHash: data.idempotencyKeyHash,
    requestHash: data.requestHash,
    state: data.state,
    objectGeneration: data.objectGeneration,
    sealedGeneration: data.sealedGeneration,
    cleanupClaimGeneration: data.cleanupClaimGeneration ?? null,
    cleanupClaimReason: data.cleanupClaimReason ?? null,
    createdAtMillis: data.createdAt.toMillis(),
    expiresAtMillis: data.expiresAt.toMillis(),
    committedAtMillis: data.committedAt?.toMillis() ?? null,
    sealedAtMillis: data.sealedAt?.toMillis() ?? null,
  };
}

export function storedReservation(reservation: PrivateMediaReservation) {
  return storedReservationSchema.parse({
    schemaVersion: 1,
    reservationId: reservation.reservationId,
    ownerUid: reservation.ownerUid,
    mediaKind: reservation.mediaKind,
    contentType: reservation.contentType,
    byteSize: reservation.byteSize,
    objectPath: reservation.objectPath,
    identificationRequestId: reservation.identificationRequestId,
    idempotencyKeyHash: reservation.idempotencyKeyHash,
    requestHash: reservation.requestHash,
    state: reservation.state,
    objectGeneration: reservation.objectGeneration,
    sealedGeneration: reservation.sealedGeneration,
    cleanupClaimGeneration: reservation.cleanupClaimGeneration ?? null,
    cleanupClaimReason: reservation.cleanupClaimReason ?? null,
    createdAt: Timestamp.fromMillis(reservation.createdAtMillis),
    expiresAt: Timestamp.fromMillis(reservation.expiresAtMillis),
    committedAt: reservation.committedAtMillis === null ? null : Timestamp.fromMillis(reservation.committedAtMillis),
    sealedAt: reservation.sealedAtMillis === null ? null : Timestamp.fromMillis(reservation.sealedAtMillis),
  });
}

export function privateMediaReceiptId(ownerUid: string, idempotencyKeyHash: string): string {
  return createHash("sha256")
    .update(ownerUid)
    .update("\0")
    .update(idempotencyKeyHash)
    .digest("hex");
}

function sameReservationIdentity(
  current: PrivateMediaReservation,
  expected: PrivateMediaReservation,
): boolean {
  return current.reservationId === expected.reservationId &&
    current.ownerUid === expected.ownerUid &&
    current.mediaKind === expected.mediaKind &&
    current.contentType === expected.contentType &&
    current.byteSize === expected.byteSize &&
    current.objectPath === expected.objectPath &&
    current.idempotencyKeyHash === expected.idempotencyKeyHash &&
    current.requestHash === expected.requestHash &&
    current.createdAtMillis === expected.createdAtMillis &&
    current.expiresAtMillis === expected.expiresAtMillis;
}

function sameSealExpectation(
  current: PrivateMediaReservation,
  expected: PrivateMediaReservation,
): boolean {
  return sameReservationIdentity(current, expected) &&
    current.state === expected.state &&
    current.objectGeneration === expected.objectGeneration &&
    current.sealedGeneration === expected.sealedGeneration &&
    current.identificationRequestId === expected.identificationRequestId &&
    current.cleanupClaimGeneration === (expected.cleanupClaimGeneration ?? null) &&
    current.cleanupClaimReason === (expected.cleanupClaimReason ?? null);
}

function malformed(cause?: unknown): never {
  throw new PrivateMediaError("failed-precondition", "Stored private media reservation is malformed", { cause });
}
