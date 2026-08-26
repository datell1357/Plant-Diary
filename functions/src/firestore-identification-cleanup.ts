import {
  FieldPath,
  Timestamp,
  type DocumentSnapshot,
  type Firestore,
  type Query,
} from "firebase-admin/firestore";
import {
  effectiveRetentionExpiryMillis,
  IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
  isTerminalIdentificationStatus,
} from "./identification-authorization-contract.js";
import {
  type IdentificationCleanupPersistence,
  type IdentificationCleanupRequestCandidate,
  type IdentificationCleanupScanBatch,
} from "./identification-cleanup.js";
import { parseStoredIdentificationRequest } from "./firestore-identification-authorization.js";
import {
  parseStoredPrivateMediaReservation,
  privateMediaReceiptId,
  storedReservation,
} from "./firestore-private-media.js";
import {
  PrivateMediaError,
  type PrivateMediaReservation,
} from "./private-media-contract.js";

export const IDENTIFICATION_CLEANUP_QUERY_PAGE_SIZE = 25;
const NONTERMINAL_STATUSES = ["APPROVED", "PENDING"] as const;
const TERMINAL_STATUSES = [
  "CANDIDATES",
  "NO_CANDIDATES",
  "FAILED",
  "CANCELLED",
] as const;

export class FirestoreIdentificationCleanupPersistence implements IdentificationCleanupPersistence {
  constructor(
    private readonly firestore: Firestore,
    private readonly hooks: Readonly<{
      beforeCommittedOrphanClaim?: () => void | Promise<void>;
      beforeRequestCleanupClaim?: () => void | Promise<void>;
      afterRequestCleanupClaim?: () => void | Promise<void>;
    }> = {},
  ) {}

  async scanExpiredReservedUploads(
    nowMillis: number,
    limit: number,
  ): Promise<IdentificationCleanupScanBatch<PrivateMediaReservation>> {
    const query = this.firestore
      .collection("privateMediaReservations")
      .where("mediaKind", "==", "IDENTIFICATION_ORIGINAL")
      .where("state", "in", ["RESERVED", "SEALED"])
      .where("expiresAt", "<=", Timestamp.fromMillis(nowMillis))
      .orderBy("expiresAt")
      .orderBy(FieldPath.documentId());
    return this.scan(query, limit, (document) => {
      const reservation = parseStoredPrivateMediaReservation(document.data());
      if (reservation.reservationId !== document.id) {
        throw new PrivateMediaError(
          "failed-precondition",
          "Reservation document id is malformed",
        );
      }
      return reservation;
    });
  }

  async scanExpiredCommittedOrphanedOriginals(
    nowMillis: number,
    limit: number,
  ): Promise<IdentificationCleanupScanBatch<PrivateMediaReservation>> {
    const query = this.firestore
      .collection("privateMediaReservations")
      .where("mediaKind", "==", "IDENTIFICATION_ORIGINAL")
      .where("state", "in", ["COMMITTED", "SEALED"])
      .where("committedAt", "<=", Timestamp.fromMillis(nowMillis - IDENTIFICATION_ORIGINAL_RETENTION_MILLIS))
      .orderBy("committedAt")
      .orderBy(FieldPath.documentId());
    return this.scan(query, limit, (document) => {
      const reservation = parseStoredPrivateMediaReservation(document.data());
      if (reservation.reservationId !== document.id) {
        throw new PrivateMediaError(
          "failed-precondition",
          "Reservation document id is malformed",
        );
      }
      return reservation;
    });
  }

  async claimCommittedOrphanedOriginal(
    reservation: PrivateMediaReservation,
    nowMillis: number,
  ): Promise<PrivateMediaReservation | null> {
    // Test-only synchronization happens before the transaction; Firestore holds document locks
    // while a callback runs, so awaiting a barrier inside it would manufacture a lock timeout.
    await this.hooks.beforeCommittedOrphanClaim?.();
    return this.firestore.runTransaction(async (transaction) => {
      const reservationReference = this.firestore.doc(
        `privateMediaReservations/${reservation.reservationId}`,
      );
      const reservationSnapshot = await transaction.get(reservationReference);
      if (!reservationSnapshot.exists) return null;
      const current = parseStoredPrivateMediaReservation(reservationSnapshot.data());
      if (!sameReservationIdentity(current, reservation)) malformedPurge();
      if (current.mediaKind !== "IDENTIFICATION_ORIGINAL" || current.committedAtMillis === null || current.committedAtMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS > nowMillis) return null;
      if (
        current.state === "SEALED" &&
        current.cleanupClaimReason === "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL" &&
        current.cleanupClaimGeneration !== null
      ) return current;
      if (current.state !== "COMMITTED" || current.objectGeneration === null || current.objectGeneration !== reservation.objectGeneration) return null;
      const requestSnapshot = current.identificationRequestId === null
        ? null
        : await transaction.get(this.firestore.doc(`users/${current.ownerUid}/identificationRequests/${current.identificationRequestId}`));
      if (requestSnapshot?.exists) return null;
      const claimed: PrivateMediaReservation = {
        ...current,
        state: "SEALED",
        objectGeneration: null,
        sealedGeneration: null,
        sealedAtMillis: nowMillis,
        cleanupClaimGeneration: current.objectGeneration,
        cleanupClaimReason: "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL",
      };
      transaction.set(reservationReference, storedReservation(claimed), { merge: false });
      return claimed;
    });
  }

  async scanExpiredNonterminalRequests(
    nowMillis: number,
    limit: number,
  ): Promise<
    IdentificationCleanupScanBatch<IdentificationCleanupRequestCandidate>
  > {
    const query = this.firestore
      .collectionGroup("identificationRequests")
      .where("status", "in", NONTERMINAL_STATUSES)
      .where("retentionExpiresAt", "==", null)
      .where("hardExpiresAt", "<=", Timestamp.fromMillis(nowMillis))
      .orderBy("hardExpiresAt")
      .orderBy(FieldPath.documentId());
    return this.scanRequests(query, limit, false);
  }

  async scanExpiredTerminalRequests(
    nowMillis: number,
    limit: number,
  ): Promise<
    IdentificationCleanupScanBatch<IdentificationCleanupRequestCandidate>
  > {
    const query = this.firestore
      .collectionGroup("identificationRequests")
      .where("status", "in", TERMINAL_STATUSES)
      .where("retentionExpiresAt", "<=", Timestamp.fromMillis(nowMillis))
      .orderBy("retentionExpiresAt")
      .orderBy(FieldPath.documentId());
    return this.scanRequests(query, limit, true);
  }

  async claimIdentificationRequest(
    candidate: IdentificationCleanupRequestCandidate,
    reservation: PrivateMediaReservation,
    nowMillis: number,
  ): Promise<PrivateMediaReservation | null> {
    await this.hooks.beforeRequestCleanupClaim?.();
    const claimed = await this.firestore.runTransaction(async (transaction) => {
      const requestReference = this.firestore.doc(candidate.documentPath);
      const reservationReference = this.firestore.doc(
        `privateMediaReservations/${reservation.reservationId}`,
      );
      const [requestSnapshot, reservationSnapshot] = await Promise.all([
        transaction.get(requestReference),
        transaction.get(reservationReference),
      ]);
      if (!requestSnapshot.exists || !reservationSnapshot.exists) return null;
      const currentRequest = parseStoredIdentificationRequest(
        requestSnapshot.data(),
      ).request;
      const currentReservation = parseStoredPrivateMediaReservation(
        reservationSnapshot.data(),
      );
      if (
        currentRequest.ownerUid !== candidate.request.ownerUid
        || currentRequest.requestId !== candidate.request.requestId
        || currentRequest.mediaReference.reservationId !== reservation.reservationId
        || currentRequest.mediaReference.generation !== candidate.request.mediaReference.generation
        || !sameReservationIdentity(currentReservation, reservation)
        || currentReservation.identificationRequestId !== currentRequest.requestId
      ) malformedPurge();
      if (effectiveRetentionExpiryMillis(currentRequest) > nowMillis) return null;
      if (
        currentReservation.cleanupClaimReason === "IDENTIFICATION_REQUEST_RETENTION"
        && currentReservation.cleanupClaimGeneration === currentRequest.mediaReference.generation
      ) return currentReservation;
      if (currentReservation.cleanupClaimReason !== null) return null;
      if (
        currentRequest.sendState === "SENDING"
        && (
          currentRequest.claimExpiresAtMillis === null
          || nowMillis < currentRequest.claimExpiresAtMillis
        )
      ) return null;
      const generationMatches = currentReservation.state === "COMMITTED"
        && currentReservation.objectGeneration === currentRequest.mediaReference.generation;
      const alreadySealed = currentReservation.state === "SEALED"
        && currentReservation.objectGeneration === null
        && currentReservation.sealedGeneration !== null;
      if (!generationMatches && !alreadySealed) return null;
      const next: PrivateMediaReservation = {
        ...currentReservation,
        cleanupClaimGeneration: currentRequest.mediaReference.generation,
        cleanupClaimReason: "IDENTIFICATION_REQUEST_RETENTION",
      };
      transaction.update(reservationReference, {
        cleanupClaimGeneration: next.cleanupClaimGeneration,
        cleanupClaimReason: next.cleanupClaimReason,
      });
      return next;
    });
    if (claimed !== null) await this.hooks.afterRequestCleanupClaim?.();
    return claimed;
  }

  async purgeReservedUpload(
    reservation: PrivateMediaReservation,
    sealedGeneration: string,
    nowMillis: number,
  ): Promise<"purged" | "already_purged" | "deferred"> {
    return this.firestore.runTransaction(async (transaction) => {
      const reservationReference = this.firestore.doc(
        `privateMediaReservations/${reservation.reservationId}`,
      );
      const receiptReference = this.receiptReference(reservation);
      const [reservationSnapshot, receiptSnapshot] = await Promise.all([
        transaction.get(reservationReference),
        transaction.get(receiptReference),
      ]);
      if (!reservationSnapshot.exists) {
        validateReceipt(receiptSnapshot, reservation);
        if (receiptSnapshot.exists) transaction.delete(receiptReference);
        return "already_purged";
      }
      const current = parseStoredPrivateMediaReservation(
        reservationSnapshot.data(),
      );
      if (!sameReservationIdentity(current, reservation)) malformedPurge();
      if (
        current.expiresAtMillis > nowMillis ||
        current.identificationRequestId !== null
      )
        return "deferred";
      if (
        current.cleanupClaimReason !== "EXPIRED_RESERVED_UPLOAD" ||
        current.cleanupClaimGeneration !== null
      ) malformedPurge();
      assertVerifiedSeal(current, sealedGeneration);
      validateReceipt(receiptSnapshot, reservation);
      transaction.delete(reservationReference);
      if (receiptSnapshot.exists) transaction.delete(receiptReference);
      return "purged";
    });
  }

  async purgeCommittedOrphanedOriginal(
    reservation: PrivateMediaReservation,
    sealedGeneration: string,
    nowMillis: number,
  ): Promise<"purged" | "already_purged" | "deferred"> {
    return this.firestore.runTransaction(async (transaction) => {
      const reservationReference = this.firestore.doc(
        `privateMediaReservations/${reservation.reservationId}`,
      );
      const receiptReference = this.receiptReference(reservation);
      const reservationSnapshot = await transaction.get(reservationReference);
      if (!reservationSnapshot.exists) {
        const receiptSnapshot = await transaction.get(receiptReference);
        validateReceipt(receiptSnapshot, reservation);
        if (receiptSnapshot.exists) transaction.delete(receiptReference);
        return "already_purged";
      }
      const current = parseStoredPrivateMediaReservation(reservationSnapshot.data());
      if (!sameReservationIdentity(current, reservation)) malformedPurge();
      if (
        current.state !== "SEALED"
        || current.cleanupClaimReason !== "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL"
        || current.cleanupClaimGeneration !== sealedGeneration
        || current.sealedGeneration === null
        || current.committedAtMillis === null
        || current.committedAtMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS > nowMillis
      ) return "deferred";
      if (current.identificationRequestId !== null) {
        const requestSnapshot = await transaction.get(
          this.firestore.doc(
            `users/${current.ownerUid}/identificationRequests/${current.identificationRequestId}`,
          ),
        );
        if (requestSnapshot.exists) return "deferred";
      }
      // The transactionally persisted cleanup claim pins the original generation before I/O;
      // unlike normal request cleanup there is intentionally no replacement seal object.
      const receiptSnapshot = await transaction.get(receiptReference);
      validateReceipt(receiptSnapshot, reservation);
      transaction.delete(reservationReference);
      if (receiptSnapshot.exists) transaction.delete(receiptReference);
      return "purged";
    });
  }

  async purgeIdentificationRequest(
    candidate: IdentificationCleanupRequestCandidate,
    reservation: PrivateMediaReservation,
    sealedGeneration: string,
    nowMillis: number,
  ): Promise<"purged" | "already_purged" | "deferred"> {
    return this.firestore.runTransaction(async (transaction) => {
      const requestReference = this.firestore.doc(candidate.documentPath);
      const reservationReference = this.firestore.doc(
        `privateMediaReservations/${reservation.reservationId}`,
      );
      const receiptReference = this.receiptReference(reservation);
      const [requestSnapshot, reservationSnapshot, receiptSnapshot] =
        await Promise.all([
          transaction.get(requestReference),
          transaction.get(reservationReference),
          transaction.get(receiptReference),
        ]);
      if (!requestSnapshot.exists && !reservationSnapshot.exists) {
        validateReceipt(receiptSnapshot, reservation);
        if (receiptSnapshot.exists) transaction.delete(receiptReference);
        return "already_purged";
      }
      if (requestSnapshot.exists) {
        const currentRequest = parseStoredIdentificationRequest(
          requestSnapshot.data(),
        ).request;
        if (
          currentRequest.ownerUid !== candidate.request.ownerUid ||
          currentRequest.requestId !== candidate.request.requestId ||
          currentRequest.mediaReference.reservationId !==
            reservation.reservationId ||
          currentRequest.mediaReference.generation !==
            candidate.request.mediaReference.generation
        )
          malformedPurge();
        if (effectiveRetentionExpiryMillis(currentRequest) > nowMillis)
          return "deferred";
      }
      if (!reservationSnapshot.exists) malformedPurge();
      const currentReservation = parseStoredPrivateMediaReservation(
        reservationSnapshot.data(),
      );
      if (!sameReservationIdentity(currentReservation, reservation))
        malformedPurge();
      if (
        currentReservation.identificationRequestId !==
        candidate.request.requestId
      ) {
        malformedPurge();
      }
      if (
        currentReservation.cleanupClaimReason !== "IDENTIFICATION_REQUEST_RETENTION"
        || currentReservation.cleanupClaimGeneration !==
          candidate.request.mediaReference.generation
      ) {
        malformedPurge();
      }
      assertVerifiedSeal(currentReservation, sealedGeneration);
      validateReceipt(receiptSnapshot, reservation);
      if (requestSnapshot.exists) transaction.delete(requestReference);
      transaction.delete(reservationReference);
      if (receiptSnapshot.exists) transaction.delete(receiptReference);
      return "purged";
    });
  }

  private async scanRequests(
    query: Query,
    limit: number,
    terminal: boolean,
  ): Promise<
    IdentificationCleanupScanBatch<IdentificationCleanupRequestCandidate>
  > {
    return this.scan(query, limit, (document) => {
      const request = parseStoredIdentificationRequest(document.data()).request;
      if (
        request.requestId !== document.id ||
        isTerminalIdentificationStatus(request.status) !== terminal ||
        document.ref.parent.parent?.id !== request.ownerUid
      ) {
        throw new Error("Identification request document path is malformed");
      }
      return { documentPath: document.ref.path, request };
    });
  }

  private async scan<T>(
    initialQuery: Query,
    limit: number,
    parse: (document: DocumentSnapshot) => T,
  ): Promise<IdentificationCleanupScanBatch<T>> {
    const items: T[] = [];
    const failures: { itemId: string; error: unknown }[] = [];
    let query = initialQuery;
    let remaining = limit;
    while (remaining > 0) {
      const pageLimit = Math.min(
        IDENTIFICATION_CLEANUP_QUERY_PAGE_SIZE,
        remaining,
      );
      const page = await query.limit(pageLimit).get();
      for (const document of page.docs) {
        try {
          items.push(parse(document));
        } catch (error: unknown) {
          failures.push({ itemId: document.ref.path, error });
        }
      }
      remaining -= page.size;
      const cursor = page.docs.at(-1);
      if (cursor === undefined || page.size < pageLimit || remaining === 0)
        break;
      query = initialQuery.startAfter(cursor);
    }
    return { items, failures };
  }

  private receiptReference(reservation: PrivateMediaReservation) {
    return this.firestore.doc(
      `privateMediaReservationReceipts/${privateMediaReceiptId(
        reservation.ownerUid,
        reservation.idempotencyKeyHash,
      )}`,
    );
  }
}

function sameReservationIdentity(
  current: PrivateMediaReservation,
  expected: PrivateMediaReservation,
): boolean {
  return (
    current.reservationId === expected.reservationId &&
    current.ownerUid === expected.ownerUid &&
    current.mediaKind === "IDENTIFICATION_ORIGINAL" &&
    current.idempotencyKeyHash === expected.idempotencyKeyHash &&
    current.objectPath === expected.objectPath
  );
}

function assertVerifiedSeal(
  reservation: PrivateMediaReservation,
  sealedGeneration: string,
): void {
  if (
    reservation.state !== "SEALED" ||
    reservation.objectGeneration !== null ||
    reservation.sealedGeneration !== sealedGeneration
  )
    malformedPurge();
}

function validateReceipt(
  snapshot: DocumentSnapshot,
  reservation: PrivateMediaReservation,
): void {
  if (!snapshot.exists) return;
  if (
    snapshot.get("ownerUid") !== reservation.ownerUid ||
    snapshot.get("reservationId") !== reservation.reservationId ||
    snapshot.get("idempotencyKeyHash") !== reservation.idempotencyKeyHash
  )
    malformedPurge();
}

function malformedPurge(): never {
  throw new PrivateMediaError(
    "failed-precondition",
    "Identification cleanup metadata no longer matches the verified seal",
  );
}
