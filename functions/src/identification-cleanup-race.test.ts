import assert from "node:assert/strict";
import test from "node:test";
import type { AuthorizedIdentificationRequest } from "./identification-authorization-contract.js";
import {
  runIdentificationCleanup,
  type IdentificationCleanupPersistence,
  type IdentificationCleanupRequestCandidate,
} from "./identification-cleanup.js";
import type {
  PrivateMediaObjectStore,
  PrivateMediaReservation,
  PrivateMediaReservationRepository,
} from "./private-media-contract.js";

const NOW = Date.parse("2026-08-25T12:00:00.000Z");
const REQUEST_ID = "cleanup_active_send_request";
const RESERVATION_ID = "cleanup_active_send_reservation";

const reservation: PrivateMediaReservation = {
  schemaVersion: 1,
  reservationId: RESERVATION_ID,
  ownerUid: "cleanup-owner",
  mediaKind: "IDENTIFICATION_ORIGINAL",
  contentType: "image/webp",
  byteSize: 3,
  objectPath: `private-media-v2/${RESERVATION_ID}`,
  identificationRequestId: REQUEST_ID,
  idempotencyKeyHash: "a".repeat(64),
  requestHash: "b".repeat(64),
  state: "COMMITTED",
  objectGeneration: "7",
  sealedGeneration: null,
  cleanupClaimGeneration: null,
  cleanupClaimReason: null,
  createdAtMillis: NOW - 86_400_000,
  expiresAtMillis: NOW - 86_000_000,
  committedAtMillis: NOW - 86_399_999,
  sealedAtMillis: null,
};

const request: AuthorizedIdentificationRequest = {
  schemaVersion: 1,
  requestId: REQUEST_ID,
  ownerUid: reservation.ownerUid,
  mediaReference: { reservationId: RESERVATION_ID, generation: "7" },
  disclosureVersion: 1,
  status: "PENDING",
  claimGeneration: 1,
  claimOperationKey: "cleanup_active_send_operation",
  claimExpiresAtMillis: NOW + 1,
  sendState: "SENDING",
  acknowledgedAtMillis: NOW - 86_400_000,
  createdAtMillis: NOW - 86_400_000,
  hardExpiresAtMillis: NOW,
  terminalAtMillis: null,
  retentionExpiresAtMillis: null,
};

const candidate: IdentificationCleanupRequestCandidate = {
  documentPath: `users/${request.ownerUid}/identificationRequests/${REQUEST_ID}`,
  request,
};

function unused(): never {
  throw new TypeError("Cleanup crossed the active-send claim fence");
}

test("active SENDING claim defers expired cleanup before any object operation", async () => {
  // Given
  let claimCalls = 0;
  let objectCalls = 0;
  const persistence: IdentificationCleanupPersistence = {
    async scanExpiredReservedUploads() {
      return { items: [], failures: [] };
    },
    async scanExpiredCommittedOrphanedOriginals() {
      return { items: [], failures: [] };
    },
    async claimCommittedOrphanedOriginal() {
      return null;
    },
    async scanExpiredNonterminalRequests() {
      return { items: [candidate], failures: [] };
    },
    async scanExpiredTerminalRequests() {
      return { items: [], failures: [] };
    },
    async claimIdentificationRequest() {
      claimCalls += 1;
      return null;
    },
    async purgeReservedUpload() {
      return unused();
    },
    async purgeIdentificationRequest() {
      return unused();
    },
    async purgeCommittedOrphanedOriginal() {
      return unused();
    },
  };
  const reservations: PrivateMediaReservationRepository = {
    async load() {
      return reservation;
    },
    async reserve() {
      return unused();
    },
    async commit() {
      return unused();
    },
    async claimExpiredReservedUpload() {
      return unused();
    },
    async resolve() {
      return unused();
    },
    async listOwner() {
      return [];
    },
    async markSealed() {
      return unused();
    },
    async shouldDeleteFinalized() {
      return false;
    },
  };
  const objects: PrivateMediaObjectStore = {
    async inspect() {
      objectCalls += 1;
      return unused();
    },
    async deleteGeneration() {
      objectCalls += 1;
      return unused();
    },
    async createSeal() {
      objectCalls += 1;
      return unused();
    },
  };

  // When
  const result = await runIdentificationCleanup({
    persistence,
    reservations,
    objects,
    legacyObjects: {
      async listLegacyIdentificationOriginals() {
        return { items: [], failures: [] };
      },
      async deleteLegacyIdentificationOriginal() {
        return unused();
      },
    },
    nowMillis: () => NOW,
  });

  // Then
  assert.equal(claimCalls, 1);
  assert.equal(objectCalls, 0);
  assert.deepEqual(result.nonterminalDeadlines, {
    scanned: 1,
    cleaned: 0,
    deferred: 1,
  });
  assert.equal(result.failures.length, 0);
});
