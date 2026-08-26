import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { FirebaseLegacyIdentificationOriginalStore } from "./firebase-identification-cleanup.js";
import { FirebasePrivateMediaObjectStore } from "./firebase-private-media.js";
import { FirestoreIdentificationCleanupPersistence } from "./firestore-identification-cleanup.js";
import { FirestoreIdentificationAuthorizationRepository } from "./firestore-identification-authorization.js";
import { IDENTIFICATION_DISCLOSURE_VERSION } from "./identification-authorization.js";
import {
  privateMediaReceiptId,
  FirestorePrivateMediaReservationRepository,
} from "./firestore-private-media.js";
import { runIdentificationCleanup } from "./identification-cleanup.js";
import { isPrivateMediaSeal } from "./private-media-contract.js";

const PROJECT_ID = "demo-planterior";
const BUCKET_NAME = `${PROJECT_ID}.firebasestorage.app`;
const NOW = Date.parse("2026-08-24T12:00:00.000Z");
const HOUR = 60 * 60 * 1_000;

function reservationData(
  reservationId: string,
  state: "RESERVED" | "COMMITTED",
  generation: string | null,
  mediaKind:
    "IDENTIFICATION_ORIGINAL" | "PLANT_PHOTO" = "IDENTIFICATION_ORIGINAL",
  requestId: string | null = null,
) {
  return {
    schemaVersion: 1,
    reservationId,
    ownerUid: "cleanup-owner",
    mediaKind,
    contentType: "image/webp",
    byteSize: 3,
    objectPath: `private-media-v2/${reservationId}`,
    identificationRequestId: requestId,
    idempotencyKeyHash: createHash("sha256")
      .update(`key:${reservationId}`)
      .digest("hex"),
    requestHash: createHash("sha256")
      .update(`request:${reservationId}`)
      .digest("hex"),
    state,
    objectGeneration: generation,
    sealedGeneration: null,
    createdAt: Timestamp.fromMillis(NOW - 48 * HOUR),
    expiresAt: Timestamp.fromMillis(NOW - HOUR),
    committedAt:
      state === "COMMITTED" ? Timestamp.fromMillis(NOW - 47 * HOUR) : null,
    sealedAt: null,
  };
}

function requestData(
  requestId: string,
  reservationId: string,
  generation: string,
  terminal: boolean,
) {
  const createdAt = NOW - 48 * HOUR;
  const terminalAt = NOW - 24 * HOUR;
  return {
    schemaVersion: 1,
    requestId,
    ownerUid: "cleanup-owner",
    mediaReference: { reservationId, generation },
    disclosureVersion: 1,
    status: terminal ? "FAILED" : "PENDING",
    claimGeneration: terminal ? 1 : 0,
    claimOperationKey: null,
    claimExpiresAt: null,
    sendState: terminal ? "SENT" : "NOT_SENT",
    acknowledgedAt: Timestamp.fromMillis(createdAt),
    createdAt: Timestamp.fromMillis(createdAt),
    hardExpiresAt: Timestamp.fromMillis(createdAt + 24 * HOUR),
    terminalAt: terminal ? Timestamp.fromMillis(terminalAt) : null,
    retentionExpiresAt: terminal ? Timestamp.fromMillis(NOW) : null,
    identificationResult: terminal
      ? { kind: "failed", reason: "timeout" }
      : null,
  };
}

function deferred<T = void>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((resolvePromise) => { resolve = resolvePromise; });
  return { promise, resolve };
}

async function deleteCollection(
  firestore: ReturnType<typeof getFirestore>,
  path: string,
): Promise<void> {
  const documents = await firestore.collection(path).get();
  await Promise.all(documents.docs.map((document) => document.ref.delete()));
}

test("cleanup queries paginate deterministically to the configured bound", async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const app = initializeApp(
    { projectId: PROJECT_ID },
    "identification-cleanup-pagination",
  );
  const firestore = getFirestore(app);
  const persistence = new FirestoreIdentificationCleanupPersistence(firestore);
  const prefix = "cleanup_page_";

  try {
    await deleteCollection(firestore, "privateMediaReservations");
    const batch = firestore.batch();
    for (let index = 0; index < 30; index += 1) {
      const reservationId = `${prefix}${String(index).padStart(3, "0")}`;
      batch.set(
        firestore.doc(`privateMediaReservations/${reservationId}`),
        reservationData(reservationId, "RESERVED", null),
      );
    }
    batch.set(
      firestore.doc("privateMediaReservations/cleanup_page_plant_photo"),
      reservationData(
        "cleanup_page_plant_photo",
        "RESERVED",
        null,
        "PLANT_PHOTO",
      ),
    );
    await batch.commit();

    const page = await persistence.scanExpiredReservedUploads(NOW, 27);

    assert.equal(page.failures.length, 0);
    assert.equal(page.items.length, 27);
    assert.deepEqual(
      page.items.map((item) => item.reservationId),
      Array.from(
        { length: 27 },
        (_, index) => `${prefix}${String(index).padStart(3, "0")}`,
      ),
    );
  } finally {
    await deleteCollection(firestore, "privateMediaReservations");
    await deleteApp(app);
  }
});

test("committed orphan claim atomically fences exact-24h unlinked and legacy-missing records", async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const app = initializeApp(
    { projectId: PROJECT_ID },
    "identification-cleanup-committed-orphans",
  );
  const firestore = getFirestore(app);
  const persistence = new FirestoreIdentificationCleanupPersistence(firestore);
  const ids = {
    unlinked: "cleanup_orphan_unlinked",
    legacyMissing: "cleanup_orphan_legacy_missing",
    valid: "cleanup_orphan_valid_request",
    young: "cleanup_orphan_young",
    plant: "cleanup_orphan_plant_photo",
  };
  const validRequestId = "request_cleanup_orphan_valid";
  try {
    await deleteCollection(firestore, "privateMediaReservations");
    const writes = firestore.batch();
    for (const [id, requestId, mediaKind, committedAt] of [
      [ids.unlinked, null, "IDENTIFICATION_ORIGINAL", NOW - 24 * HOUR],
      [ids.legacyMissing, "request_cleanup_orphan_missing", "IDENTIFICATION_ORIGINAL", NOW - 24 * HOUR],
      [ids.valid, validRequestId, "IDENTIFICATION_ORIGINAL", NOW - 24 * HOUR],
      [ids.young, null, "IDENTIFICATION_ORIGINAL", NOW - 24 * HOUR + 1],
      [ids.plant, null, "PLANT_PHOTO", NOW - 24 * HOUR],
    ] as const) {
      writes.set(
        firestore.doc(`privateMediaReservations/${id}`),
        {
          ...reservationData(id, "COMMITTED", "7", mediaKind, requestId),
          committedAt: Timestamp.fromMillis(committedAt),
        },
      );
    }
    writes.set(
      firestore.doc(`users/cleanup-owner/identificationRequests/${validRequestId}`),
      requestData(validRequestId, ids.valid, "7", false),
    );
    await writes.commit();

    const batch = await persistence.scanExpiredCommittedOrphanedOriginals(NOW, 10);
    assert.deepEqual(
      batch.items.map((item) => item.reservationId).sort(),
      [ids.legacyMissing, ids.unlinked, ids.valid].sort(),
    );
    const byId = new Map(batch.items.map((item) => [item.reservationId, item]));
    assert.equal(
      (await persistence.claimCommittedOrphanedOriginal(byId.get(ids.unlinked)!, NOW))?.cleanupClaimReason,
      "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL",
    );
    assert.equal(
      (await persistence.claimCommittedOrphanedOriginal(byId.get(ids.legacyMissing)!, NOW))?.cleanupClaimReason,
      "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL",
    );
    assert.equal(
      await persistence.claimCommittedOrphanedOriginal(byId.get(ids.valid)!, NOW),
      null,
    );
  } finally {
    await Promise.all([
      ...Object.values(ids).map((id) => firestore.doc(`privateMediaReservations/${id}`).delete()),
      firestore.doc(`users/cleanup-owner/identificationRequests/${validRequestId}`).delete(),
    ]);
    await deleteApp(app);
  }
});

test("reserved cleanup defers metadata purge when a concurrent identification link wins", async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const app = initializeApp(
    { projectId: PROJECT_ID },
    "identification-cleanup-reserved-link-race",
  );
  const firestore = getFirestore(app);
  const persistence = new FirestoreIdentificationCleanupPersistence(firestore);
  const reservations = new FirestorePrivateMediaReservationRepository(
    firestore,
  );
  const reservationId = "cleanup_reserved_link_race";
  const reference = firestore.doc(`privateMediaReservations/${reservationId}`);

  try {
    const stored = reservationData(reservationId, "RESERVED", null);
    await reference.set(stored);
    const scanned = await reservations.load(reservationId);
    assert.ok(scanned !== null);

    await reference.set({
      ...stored,
      identificationRequestId: "request_cleanup_link_race",
      state: "SEALED",
      sealedGeneration: "9",
      sealedAt: Timestamp.fromMillis(NOW),
    });

    const outcome = await persistence.purgeReservedUpload(scanned, "9", NOW);

    assert.equal(outcome, "deferred");
    assert.equal((await reference.get()).exists, true);
  } finally {
    await reference.delete();
    await deleteApp(app);
  }
});

test("cleanup and admission transaction races commit in either order without a request whose bytes are missing", async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.ok(process.env.STORAGE_EMULATOR_HOST);
  const app = initializeApp({ projectId: PROJECT_ID, storageBucket: BUCKET_NAME }, "identification-cleanup-admission-race");
  const firestore = getFirestore(app);
  const storage = getStorage(app);
  const reservations = new FirestorePrivateMediaReservationRepository(firestore);
  const objects = new FirebasePrivateMediaObjectStore(storage);
  const legacyObjects = new FirebaseLegacyIdentificationOriginalStore(storage);

  async function seed(id: string) {
    const path = `private-media-v2/${id}`;
    await storage.bucket().file(path).save(Buffer.from([1, 2, 3]), {
      resumable: false,
      metadata: { contentType: "image/webp", metadata: { ownerUid: "cleanup-owner", reservationId: id } },
    });
    const object = await objects.inspect(path);
    assert.ok(object !== null);
    await firestore.doc(`privateMediaReservations/${id}`).set({
      ...reservationData(id, "COMMITTED", object.generation),
      committedAt: Timestamp.fromMillis(NOW - 24 * HOUR),
    });
    return { path, generation: object.generation };
  }
  async function cleanup(id: string, persistence: FirestoreIdentificationCleanupPersistence) {
    return runIdentificationCleanup({ persistence, reservations, objects, legacyObjects, nowMillis: () => NOW });
  }
  async function clear(id: string, requestId: string) {
    await Promise.all([
      firestore.doc(`privateMediaReservations/${id}`).delete(),
      firestore.doc(`users/cleanup-owner/identificationRequests/${requestId}`).delete(),
      storage.bucket().file(`private-media-v2/${id}`).delete({ ignoreNotFound: true }),
    ]);
  }

  try {
    // Admission commits first; the stale cleanup transaction retries and observes the request.
    const admissionFirstId = "cleanup_race_admission_first";
    const admissionFirstRequest = "cleanup_race_admission_first_request";
    const first = await seed(admissionFirstId);
    const claimRead = deferred();
    let blockClaimOnce = true;
    const cleanupFirst = new FirestoreIdentificationCleanupPersistence(firestore, {
      beforeCommittedOrphanClaim: async () => {
        if (!blockClaimOnce) return;
        blockClaimOnce = false;
        claimRead.resolve();
        await deferredRelease.promise;
      },
    });
    const deferredRelease = deferred();
    const pendingCleanup = cleanup(admissionFirstId, cleanupFirst);
    await claimRead.promise;
    await new FirestoreIdentificationAuthorizationRepository(firestore).admit({
      ownerUid: "cleanup-owner", requestId: admissionFirstRequest,
      mediaReference: { reservationId: admissionFirstId, generation: first.generation },
      disclosureVersion: IDENTIFICATION_DISCLOSURE_VERSION, nowMillis: NOW - 1,
    });
    deferredRelease.resolve();
    await pendingCleanup;
    assert.equal((await firestore.doc(`users/cleanup-owner/identificationRequests/${admissionFirstRequest}`).get()).exists, true);
    assert.equal((await objects.inspect(first.path))?.generation, first.generation);
    await clear(admissionFirstId, admissionFirstRequest);

    // Cleanup commits first; the admission transaction retries against SEALED and rejects.
    const cleanupFirstId = "cleanup_race_cleanup_first";
    const cleanupFirstRequest = "cleanup_race_cleanup_first_request";
    const second = await seed(cleanupFirstId);
    const cleanupRead = deferred();
    const releaseCleanup = deferred();
    let holdCleanupOnce = true;
    const persistence = new FirestoreIdentificationCleanupPersistence(firestore, {
      beforeCommittedOrphanClaim: async () => {
        if (!holdCleanupOnce) return;
        holdCleanupOnce = false;
        cleanupRead.resolve();
        await releaseCleanup.promise;
      },
    });
    const pendingClaim = cleanup(cleanupFirstId, persistence);
    await cleanupRead.promise;
    // The barrier releases the cleanup transaction before admission starts, making the
    // cleanup-first linearization deterministic without holding a Firestore transaction open.
    releaseCleanup.resolve();
    await pendingClaim;
    await assert.rejects(new FirestoreIdentificationAuthorizationRepository(firestore).admit({
      ownerUid: "cleanup-owner", requestId: cleanupFirstRequest,
      mediaReference: { reservationId: cleanupFirstId, generation: second.generation },
      disclosureVersion: IDENTIFICATION_DISCLOSURE_VERSION, nowMillis: NOW - 1,
    }), /not a committed reservation/);
    assert.equal((await firestore.doc(`users/cleanup-owner/identificationRequests/${cleanupFirstRequest}`).get()).exists, false);
    assert.equal(isPrivateMediaSeal((await objects.inspect(second.path))!), true);
    await clear(cleanupFirstId, cleanupFirstRequest);
  } finally {
    await deleteApp(app);
  }
});

test("real cleanup seals and purges expired request-linked and orphaned v2 bytes, deletes legacy, and preserves PLANT_PHOTO", async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.ok(process.env.STORAGE_EMULATOR_HOST);
  const app = initializeApp(
    { projectId: PROJECT_ID, storageBucket: BUCKET_NAME },
    "identification-cleanup-lifecycle",
  );
  const firestore = getFirestore(app);
  const storage = getStorage(app);
  const reservations = new FirestorePrivateMediaReservationRepository(
    firestore,
  );
  const objects = new FirebasePrivateMediaObjectStore(storage);
  const persistence = new FirestoreIdentificationCleanupPersistence(firestore);
  const legacyObjects = new FirebaseLegacyIdentificationOriginalStore(storage);
  const reservedId = "cleanup_reserved_original";
  const nonterminalId = "cleanup_nonterminal_original";
  const terminalId = "cleanup_terminal_original";
  const orphanId = "cleanup_orphaned_original";
  const legacyMissingId = "cleanup_legacy_missing_original";
  const plantId = "cleanup_representative_photo";
  const nonterminalRequest = "request_cleanup_nonterminal";
  const terminalRequest = "request_cleanup_terminal";
  const legacyMissingRequest = "request_cleanup_legacy_missing";
  const legacyPath =
    "identification-originals/cleanup-owner/legacy-request/original.webp";
  const ids = [
    reservedId,
    nonterminalId,
    terminalId,
    orphanId,
    legacyMissingId,
    plantId,
  ];
  const paths = [...ids.map((id) => `private-media-v2/${id}`), legacyPath];

  try {
    await Promise.all([
      ...ids.map((id) =>
        firestore.doc(`privateMediaReservations/${id}`).delete(),
      ),
      firestore
        .doc(`users/cleanup-owner/identificationRequests/${nonterminalRequest}`)
        .delete(),
      firestore
        .doc(`users/cleanup-owner/identificationRequests/${terminalRequest}`)
        .delete(),
      ...paths.map((path) =>
        storage.bucket().file(path).delete({ ignoreNotFound: true }),
      ),
    ]);
    const bytes = Buffer.from([1, 2, 3]);
    const generations = new Map<string, string>();
    for (const id of [
      nonterminalId,
      terminalId,
      orphanId,
      legacyMissingId,
      plantId,
    ]) {
      await storage
        .bucket()
        .file(`private-media-v2/${id}`)
        .save(bytes, {
          resumable: false,
          metadata: {
            contentType: "image/webp",
            metadata: { ownerUid: "cleanup-owner", reservationId: id },
          },
        });
      const object = await objects.inspect(`private-media-v2/${id}`);
      assert.ok(object !== null);
      generations.set(id, object.generation);
    }
    await storage
      .bucket()
      .file(legacyPath)
      .save(Buffer.from([9]), { resumable: false });

    const writes = firestore.batch();
    for (const [id, requestId] of [
      [reservedId, null],
      [nonterminalId, nonterminalRequest],
      [terminalId, terminalRequest],
      [orphanId, null],
      [legacyMissingId, legacyMissingRequest],
      [plantId, null],
    ] as const) {
      const mediaKind =
        id === plantId ? "PLANT_PHOTO" : "IDENTIFICATION_ORIGINAL";
      const state = id === reservedId ? "RESERVED" : "COMMITTED";
      const generation = generations.get(id) ?? null;
      const data = reservationData(id, state, generation, mediaKind, requestId);
      writes.set(firestore.doc(`privateMediaReservations/${id}`), data);
      const receiptId = privateMediaReceiptId(
        data.ownerUid,
        data.idempotencyKeyHash,
      );
      writes.set(
        firestore.doc(`privateMediaReservationReceipts/${receiptId}`),
        {
          schemaVersion: 1,
          ownerUid: data.ownerUid,
          idempotencyKeyHash: data.idempotencyKeyHash,
          requestHash: data.requestHash,
          reservationId: id,
          createdAt: data.createdAt,
        },
      );
    }
    writes.set(
      firestore.doc(
        `users/cleanup-owner/identificationRequests/${nonterminalRequest}`,
      ),
      requestData(
        nonterminalRequest,
        nonterminalId,
        generations.get(nonterminalId)!,
        false,
      ),
    );
    writes.set(
      firestore.doc(
        `users/cleanup-owner/identificationRequests/${terminalRequest}`,
      ),
      requestData(
        terminalRequest,
        terminalId,
        generations.get(terminalId)!,
        true,
      ),
    );
    await writes.commit();

    const result = await runIdentificationCleanup({
      persistence,
      reservations,
      objects,
      legacyObjects,
      nowMillis: () => NOW,
    });

    assert.equal(result.failures.length, 0);
    for (const id of [reservedId, nonterminalId, terminalId]) {
      const [reservation, object] = await Promise.all([
        firestore.doc(`privateMediaReservations/${id}`).get(),
        objects.inspect(`private-media-v2/${id}`),
      ]);
      assert.equal(reservation.exists, false);
      assert.ok(object !== null);
      assert.equal(isPrivateMediaSeal(object), true);
    }
    for (const id of [orphanId, legacyMissingId]) {
      assert.equal((await firestore.doc(`privateMediaReservations/${id}`).get()).exists, false);
      assert.equal(isPrivateMediaSeal((await objects.inspect(`private-media-v2/${id}`))!), true);
    }
    assert.equal(
      (
        await firestore
          .doc(
            `users/cleanup-owner/identificationRequests/${nonterminalRequest}`,
          )
          .get()
      ).exists,
      false,
    );
    assert.equal(
      (
        await firestore
          .doc(`users/cleanup-owner/identificationRequests/${terminalRequest}`)
          .get()
      ).exists,
      false,
    );
    assert.equal(await objects.inspect(legacyPath), null);
    assert.equal(
      (await firestore.doc(`privateMediaReservations/${plantId}`).get()).exists,
      true,
    );
    const plantObject = await objects.inspect(`private-media-v2/${plantId}`);
    assert.equal(plantObject?.generation, generations.get(plantId));
    assert.equal(plantObject?.byteSize, 3);
  } finally {
    await Promise.all([
      ...ids.map((id) =>
        firestore.doc(`privateMediaReservations/${id}`).delete(),
      ),
      firestore
        .doc(`users/cleanup-owner/identificationRequests/${nonterminalRequest}`)
        .delete(),
      firestore
        .doc(`users/cleanup-owner/identificationRequests/${terminalRequest}`)
        .delete(),
      ...paths.map((path) =>
        storage.bucket().file(path).delete({ ignoreNotFound: true }),
      ),
    ]);
    await deleteCollection(firestore, "privateMediaReservationReceipts");
    await deleteApp(app);
  }
});
