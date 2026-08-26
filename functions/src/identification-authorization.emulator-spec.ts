import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { FirestoreIdentificationAuthorizationRepository } from "./firestore-identification-authorization.js";
import {
  IDENTIFICATION_DISCLOSURE_VERSION,
  IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
} from "./identification-authorization.js";

const projectId = "demo-planterior";
const ownerUid = "atomic-admission-owner";
const nowMillis = Date.parse("2026-08-24T00:00:00.000Z");

function command(requestId: string, reservationId: string) {
  return {
    ownerUid,
    requestId,
    mediaReference: { reservationId, generation: "7" },
    disclosureVersion: IDENTIFICATION_DISCLOSURE_VERSION,
    nowMillis,
  } as const;
}

async function seedCommittedReservation(
  firestore: ReturnType<typeof getFirestore>,
  reservationId: string,
) {
  const reference = firestore.doc(`privateMediaReservations/${reservationId}`);
  await reference.set({
    schemaVersion: 1,
    reservationId,
    ownerUid,
    mediaKind: "IDENTIFICATION_ORIGINAL",
    contentType: "image/webp",
    byteSize: 3,
    objectPath: `private-media-v2/${reservationId}`,
    identificationRequestId: null,
    idempotencyKeyHash: "a".repeat(64),
    requestHash: "b".repeat(64),
    state: "COMMITTED",
    objectGeneration: "7",
    sealedGeneration: null,
    createdAt: Timestamp.fromMillis(nowMillis - 1),
    expiresAt: Timestamp.fromMillis(nowMillis + 10 * 60 * 1_000),
    committedAt: Timestamp.fromMillis(nowMillis - 1),
    sealedAt: null,
  });
  return reference;
}

test("atomic admission rollback leaves a failed request create with no linked reservation", async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const requestId = "atomic_failure_request_12345678";
  const reservationId = "atomic_failure_reservation_12345678";
  const app = initializeApp({ projectId }, "identification-atomic-failure");
  const firestore = getFirestore(app);
  const reservation = await seedCommittedReservation(firestore, reservationId);
  const request = firestore.doc(`users/${ownerUid}/identificationRequests/${requestId}`);
  try {
    const failing = new FirestoreIdentificationAuthorizationRepository(firestore, {
      beforeRequestCreate: () => {
        throw new Error("injected request create failure");
      },
    });

    await assert.rejects(failing.admit(command(requestId, reservationId)), /injected request create failure/);
    assert.equal((await reservation.get()).get("identificationRequestId"), null);
    assert.equal((await request.get()).exists, false);

    const admitted = await new FirestoreIdentificationAuthorizationRepository(firestore)
      .admit(command(requestId, reservationId));
    const replayed = await new FirestoreIdentificationAuthorizationRepository(firestore)
      .admit(command(requestId, reservationId));
    assert.deepEqual(replayed, admitted);
    assert.equal((await reservation.get()).get("identificationRequestId"), requestId);
    const stored = await request.get();
    assert.equal(stored.exists, true);
    assert.equal(stored.get("hardExpiresAt").toMillis() - stored.get("createdAt").toMillis(), IDENTIFICATION_ORIGINAL_RETENTION_MILLIS);
    assert.equal(stored.get("retentionExpiresAt"), null);
  } finally {
    await Promise.all([request.delete(), reservation.delete()]);
    await deleteApp(app);
  }
});

test("Firestore final send transition rejects exact claim expiry without changing SENDING", async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const requestId = "atomic_send_expiry_request_12345678";
  const reservationId = "atomic_send_expiry_reservation_12345678";
  const app = initializeApp({ projectId }, "identification-send-expiry");
  const firestore = getFirestore(app);
  const reservation = await seedCommittedReservation(firestore, reservationId);
  const request = firestore.doc(`users/${ownerUid}/identificationRequests/${requestId}`);
  try {
    const repository = new FirestoreIdentificationAuthorizationRepository(firestore);
    await repository.admit(command(requestId, reservationId));
    const claim = await repository.claim({
      ownerUid, requestId, operationKey: "atomic_send_operation_12345678", nowMillis,
    });
    assert.equal(claim.kind, "start");
    if (claim.kind !== "start") return;
    await assert.rejects(repository.markSending({
      ownerUid, requestId, operationKey: "atomic_send_operation_12345678",
      claimGeneration: claim.request.claimGeneration,
      nowMillis: claim.request.claimExpiresAtMillis!,
    }), /expired before the provider send boundary/);
    assert.equal((await request.get()).get("sendState"), "NOT_SENT");
  } finally {
    await Promise.all([request.delete(), reservation.delete()]);
    await deleteApp(app);
  }
});

test("concurrent atomic admissions converge on exactly one linked request", async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const requestId = "atomic_concurrent_request_12345678";
  const reservationId = "atomic_concurrent_reservation_12345678";
  const app = initializeApp({ projectId }, "identification-atomic-concurrent");
  const firestore = getFirestore(app);
  const reservation = await seedCommittedReservation(firestore, reservationId);
  const request = firestore.doc(`users/${ownerUid}/identificationRequests/${requestId}`);
  try {
    const firstRepository = new FirestoreIdentificationAuthorizationRepository(firestore);
    const secondRepository = new FirestoreIdentificationAuthorizationRepository(firestore);
    const [first, second] = await Promise.all([
      firstRepository.admit(command(requestId, reservationId)),
      secondRepository.admit(command(requestId, reservationId)),
    ]);
    assert.deepEqual(second, first);
    assert.equal((await reservation.get()).get("identificationRequestId"), requestId);
    const stored = await request.get();
    assert.equal(stored.exists, true);
    assert.equal(stored.get("requestId"), requestId);
    assert.equal(stored.get("hardExpiresAt").toMillis(), nowMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS);
  } finally {
    await Promise.all([request.delete(), reservation.delete()]);
    await deleteApp(app);
  }
});
