import assert from "node:assert/strict";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { FirebaseLegacyIdentificationOriginalStore } from "./firebase-identification-cleanup.js";
import { FirebasePrivateMediaObjectStore } from "./firebase-private-media.js";
import { FirestoreIdentificationCleanupPersistence } from "./firestore-identification-cleanup.js";
import { FirestorePrivateMediaReservationRepository } from "./firestore-private-media.js";
import type {
  PrivateMediaObjectStore,
  PrivateMediaReservationRepository,
} from "./private-media.js";

const PROJECT_ID = "demo-planterior";
const BUCKET_NAME = `${PROJECT_ID}.firebasestorage.app`;
export const CLEANUP_RACE_NOW = Date.parse("2026-08-26T00:00:00.000Z");
export const CLEANUP_RACE_OWNER = "private-media-cleanup-race-owner";

export type Barrier = Readonly<{
  arrive: () => void;
  release: () => void;
  waitForArrival: () => Promise<void>;
  waitForRelease: () => Promise<void>;
}>;

export function barrier(): Barrier {
  let arrive: (() => void) | undefined;
  let release: (() => void) | undefined;
  const arrived = new Promise<void>((resolve) => { arrive = resolve; });
  const released = new Promise<void>((resolve) => { release = resolve; });
  const bounded = async (signal: Promise<void>): Promise<void> => {
    let timeout: NodeJS.Timeout | undefined;
    try {
      await Promise.race([
        signal,
        new Promise<never>((_resolve, reject) => {
          timeout = setTimeout(() => reject(new Error("Race barrier timed out")), 5_000);
        }),
      ]);
    } finally {
      if (timeout !== undefined) clearTimeout(timeout);
    }
  };
  return {
    arrive: () => arrive?.(),
    release: () => release?.(),
    waitForArrival: () => bounded(arrived),
    waitForRelease: () => bounded(released),
  };
}

export async function expiredReservedFixture(appName: string, reservationId: string) {
  const app = initializeApp(
    { projectId: PROJECT_ID, storageBucket: BUCKET_NAME },
    appName,
  );
  const firestore = getFirestore(app);
  const storage = getStorage(app);
  const repository = new FirestorePrivateMediaReservationRepository(firestore);
  const objects = new FirebasePrivateMediaObjectStore(storage);
  const path = `private-media-v2/${reservationId}`;
  const reservation = firestore.doc(`privateMediaReservations/${reservationId}`);
  const file = storage.bucket().file(path);
  await Promise.all([
    reservation.delete(),
    firestore.doc(`accountDeletionRequests/${CLEANUP_RACE_OWNER}`).delete(),
    file.delete({ ignoreNotFound: true }),
  ]);
  await file.save(Buffer.from([1, 2, 3]), {
    resumable: false,
    metadata: {
      contentType: "image/webp",
      metadata: { ownerUid: CLEANUP_RACE_OWNER, reservationId },
    },
  });
  const original = await objects.inspect(path);
  assert.ok(original !== null);
  await reservation.set({
    schemaVersion: 1,
    reservationId,
    ownerUid: CLEANUP_RACE_OWNER,
    mediaKind: "IDENTIFICATION_ORIGINAL",
    contentType: "image/webp",
    byteSize: 3,
    objectPath: path,
    identificationRequestId: null,
    idempotencyKeyHash: "a".repeat(64),
    requestHash: "b".repeat(64),
    state: "RESERVED",
    objectGeneration: null,
    sealedGeneration: null,
    cleanupClaimGeneration: null,
    cleanupClaimReason: null,
    createdAt: Timestamp.fromMillis(CLEANUP_RACE_NOW - 2),
    expiresAt: Timestamp.fromMillis(CLEANUP_RACE_NOW - 1),
    committedAt: null,
    sealedAt: null,
  });
  return {
    app,
    firestore,
    storage,
    repository,
    objects,
    path,
    reservation,
    file,
    original,
  };
}

export function cleanupDependencies(
  fixture: Awaited<ReturnType<typeof expiredReservedFixture>>,
  reservations: PrivateMediaReservationRepository,
  objects: PrivateMediaObjectStore = fixture.objects,
) {
  return {
    persistence: new FirestoreIdentificationCleanupPersistence(fixture.firestore),
    reservations,
    objects,
    legacyObjects: new FirebaseLegacyIdentificationOriginalStore(fixture.storage),
    nowMillis: () => CLEANUP_RACE_NOW,
    limitPerScan: 1,
  };
}

export async function disposeExpiredReservedFixture(
  fixture: Awaited<ReturnType<typeof expiredReservedFixture>>,
): Promise<void> {
  await Promise.all([
    fixture.reservation.delete(),
    fixture.firestore.doc(`accountDeletionRequests/${CLEANUP_RACE_OWNER}`).delete(),
    fixture.file.delete({ ignoreNotFound: true }),
  ]);
  await deleteApp(fixture.app);
}
