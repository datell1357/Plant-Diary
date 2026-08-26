import assert from "node:assert/strict";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { FirebaseLegacyIdentificationOriginalStore } from "./firebase-identification-cleanup.js";
import { FirebasePrivateMediaObjectStore } from "./firebase-private-media.js";
import { FirestoreIdentificationAuthorizationRepository } from "./firestore-identification-authorization.js";
import { FirestoreIdentificationCleanupPersistence } from "./firestore-identification-cleanup.js";
import { FirestorePrivateMediaReservationRepository } from "./firestore-private-media.js";
import { IDENTIFICATION_DISCLOSURE_VERSION } from "./identification-authorization.js";

const PROJECT_ID = "demo-planterior";
const BUCKET_NAME = `${PROJECT_ID}.firebasestorage.app`;
export const OWNER_UID = "cleanup-race-owner";
export const NOW = Date.parse("2026-08-25T12:00:00.000Z");
export const DAY = 24 * 60 * 60 * 1_000;

type Deferred = Readonly<{
  promise: Promise<void>;
  resolve: () => void;
}>;

export function deferred(): Deferred {
  let settle: (() => void) | undefined;
  const promise = new Promise<void>((resolve) => {
    settle = resolve;
  });
  return {
    promise,
    resolve: () => {
      if (settle === undefined) throw new TypeError("Deferred was not initialized");
      settle();
    },
  };
}

type RaceFixtureConfig = Readonly<{
  appName: string;
  reservationId: string;
  requestId: string;
}>;

export async function raceFixture(config: RaceFixtureConfig) {
  const app = initializeApp(
    { projectId: PROJECT_ID, storageBucket: BUCKET_NAME },
    config.appName,
  );
  const firestore = getFirestore(app);
  const storage = getStorage(app);
  const objects = new FirebasePrivateMediaObjectStore(storage);
  const reservations = new FirestorePrivateMediaReservationRepository(firestore);
  const authorization = new FirestoreIdentificationAuthorizationRepository(firestore);
  const path = `private-media-v2/${config.reservationId}`;
  const request = firestore.doc(
    `users/${OWNER_UID}/identificationRequests/${config.requestId}`,
  );
  const reservation = firestore.doc(
    `privateMediaReservations/${config.reservationId}`,
  );
  const file = storage.bucket().file(path);
  await Promise.all([
    request.delete(),
    reservation.delete(),
    file.delete({ ignoreNotFound: true }),
  ]);
  await file.save(Buffer.from([1, 2, 3]), {
    resumable: false,
    metadata: {
      contentType: "image/webp",
      metadata: { ownerUid: OWNER_UID, reservationId: config.reservationId },
    },
  });
  const original = await objects.inspect(path);
  assert.ok(original !== null);
  await reservation.set({
    schemaVersion: 1,
    reservationId: config.reservationId,
    ownerUid: OWNER_UID,
    mediaKind: "IDENTIFICATION_ORIGINAL",
    contentType: "image/webp",
    byteSize: 3,
    objectPath: path,
    identificationRequestId: null,
    idempotencyKeyHash: "a".repeat(64),
    requestHash: "b".repeat(64),
    state: "COMMITTED",
    objectGeneration: original.generation,
    sealedGeneration: null,
    cleanupClaimGeneration: null,
    cleanupClaimReason: null,
    createdAt: Timestamp.fromMillis(NOW - DAY - 1),
    expiresAt: Timestamp.fromMillis(NOW - DAY),
    committedAt: Timestamp.fromMillis(NOW - DAY),
    sealedAt: null,
  });
  await authorization.admit({
    ownerUid: OWNER_UID,
    requestId: config.requestId,
    mediaReference: {
      reservationId: config.reservationId,
      generation: original.generation,
    },
    disclosureVersion: IDENTIFICATION_DISCLOSURE_VERSION,
    nowMillis: NOW - DAY,
  });
  return {
    app,
    firestore,
    storage,
    objects,
    reservations,
    authorization,
    path,
    request,
    reservation,
    file,
    original,
    config,
  };
}

export async function disposeRaceFixture(
  fixture: Awaited<ReturnType<typeof raceFixture>>,
): Promise<void> {
  await Promise.all([
    fixture.request.delete(),
    fixture.reservation.delete(),
    fixture.file.delete({ ignoreNotFound: true }),
  ]);
  await deleteApp(fixture.app);
}

export function cleanupDependencies(
  fixture: Awaited<ReturnType<typeof raceFixture>>,
  persistence: FirestoreIdentificationCleanupPersistence,
) {
  return {
    persistence,
    reservations: fixture.reservations,
    objects: fixture.objects,
    legacyObjects: new FirebaseLegacyIdentificationOriginalStore(fixture.storage),
    nowMillis: () => NOW,
  };
}
