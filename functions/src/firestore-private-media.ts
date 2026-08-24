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
  isAccountMutationLocked,
} from "./account-mutation-lock.js";
import {
  PRIVATE_MEDIA_KINDS,
  PRIVATE_MEDIA_STATES,
  PrivateMediaError,
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
  idempotencyKeyHash: z.string().regex(/^[a-f0-9]{64}$/),
  requestHash: z.string().regex(/^[a-f0-9]{64}$/),
  state: z.enum(PRIVATE_MEDIA_STATES),
  objectGeneration: z.string().regex(/^[1-9][0-9]*$/).nullable(),
  sealedGeneration: z.string().regex(/^[1-9][0-9]*$/).nullable(),
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
  constructor(private readonly firestore: Firestore) {}

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

  async markSealed(command: MarkPrivateMediaSealedCommand): Promise<void> {
    await this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.reservationId);
      const snapshot = await transaction.get(reference);
      if (!snapshot.exists) throw new PrivateMediaError("not-found", "Reservation was not found");
      const current = parseStoredPrivateMediaReservation(snapshot.data());
      if (current.ownerUid !== command.ownerUid) {
        throw new PrivateMediaError("permission-denied", "Reservation is not owned by this account");
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
      (deletion.exists && isAccountMutationLocked(
        deletion.get("status"),
        deletion.get("completedScopes"),
      ))
    );
  }

  private reference(reservationId: string) {
    return this.firestore.doc(`privateMediaReservations/${reservationId}`);
  }

  private receipt(ownerUid: string, idempotencyKeyHash: string) {
    const receiptId = createHash("sha256").update(ownerUid).update("\0").update(idempotencyKeyHash).digest("hex");
    return this.firestore.doc(`privateMediaReservationReceipts/${receiptId}`);
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
    idempotencyKeyHash: data.idempotencyKeyHash,
    requestHash: data.requestHash,
    state: data.state,
    objectGeneration: data.objectGeneration,
    sealedGeneration: data.sealedGeneration,
    createdAtMillis: data.createdAt.toMillis(),
    expiresAtMillis: data.expiresAt.toMillis(),
    committedAtMillis: data.committedAt?.toMillis() ?? null,
    sealedAtMillis: data.sealedAt?.toMillis() ?? null,
  };
}

function storedReservation(reservation: PrivateMediaReservation) {
  return storedReservationSchema.parse({
    schemaVersion: 1,
    reservationId: reservation.reservationId,
    ownerUid: reservation.ownerUid,
    mediaKind: reservation.mediaKind,
    contentType: reservation.contentType,
    byteSize: reservation.byteSize,
    objectPath: reservation.objectPath,
    idempotencyKeyHash: reservation.idempotencyKeyHash,
    requestHash: reservation.requestHash,
    state: reservation.state,
    objectGeneration: reservation.objectGeneration,
    sealedGeneration: reservation.sealedGeneration,
    createdAt: Timestamp.fromMillis(reservation.createdAtMillis),
    expiresAt: Timestamp.fromMillis(reservation.expiresAtMillis),
    committedAt: reservation.committedAtMillis === null ? null : Timestamp.fromMillis(reservation.committedAtMillis),
    sealedAt: reservation.sealedAtMillis === null ? null : Timestamp.fromMillis(reservation.sealedAtMillis),
  });
}

function malformed(cause?: unknown): never {
  throw new PrivateMediaError("failed-precondition", "Stored private media reservation is malformed", { cause });
}
