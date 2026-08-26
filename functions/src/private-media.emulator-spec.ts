import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import {
  FirebasePrivateMediaObjectStore,
} from "./firebase-private-media.js";
import { FirestorePrivateMediaReservationRepository } from "./firestore-private-media.js";
import {
  commitPrivateMediaReservation,
  PrivateMediaError,
  reservePrivateMediaUpload,
  type PrivateMediaSigner,
} from "./private-media.js";
import {
  handlePrivateMediaFinalized,
  sealOwnerPrivateMedia,
} from "./private-media-seal.js";

const PROJECT_ID = "demo-planterior";
const BUCKET_NAME = `${PROJECT_ID}.firebasestorage.app`;
const NOW_MILLIS = Date.parse("2026-08-24T00:00:00.000Z");
const signer: PrivateMediaSigner = {
  async signPut(command) {
    return { url: `https://upload.invalid/${command.reservationId}` };
  },
};

function receiptId(ownerUid: string, idempotencyKey: string): string {
  const keyHash = createHash("sha256").update(idempotencyKey).digest("hex");
  return createHash("sha256").update(ownerUid).update("\0").update(keyHash).digest("hex");
}

test("Firestore transaction linearizes concurrent reservation replays", async () => {
  // Given
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const app = initializeApp({ projectId: PROJECT_ID }, "private-media-reserve-emulator");
  const firestore = getFirestore(app);
  const repository = new FirestorePrivateMediaReservationRepository(firestore);
  const ownerUid = "private-media-race-owner";
  const idempotencyKey = "private-media-race-operation";
  const reservationIds = ["private_media_race_a", "private_media_race_b"] as const;
  const input = {
    expectedOwnerUid: ownerUid,
    mediaKind: "PLANT_PHOTO",
    contentType: "image/webp",
    byteSize: 3,
    idempotencyKey,
  } as const;

  try {
    await Promise.all([
      ...reservationIds.map((reservationId) =>
        firestore.doc(`privateMediaReservations/${reservationId}`).delete()
      ),
      firestore.doc(`privateMediaReservationReceipts/${receiptId(ownerUid, idempotencyKey)}`).delete(),
      firestore.doc(`accountDeletionRequests/${ownerUid}`).delete(),
    ]);

    // When
    const [first, second] = await Promise.all([
      reservePrivateMediaUpload({ uid: ownerUid }, input, {
        repository,
        signer,
        nowMillis: () => NOW_MILLIS,
        reservationId: () => reservationIds[0],
      }),
      reservePrivateMediaUpload({ uid: ownerUid }, input, {
        repository,
        signer,
        nowMillis: () => NOW_MILLIS,
        reservationId: () => reservationIds[1],
      }),
    ]);

    // Then
    assert.equal(second.reservationId, first.reservationId);
    const reservations = await firestore.collection("privateMediaReservations")
      .where("ownerUid", "==", ownerUid)
      .get();
    assert.equal(reservations.size, 1);
    assert.equal(reservations.docs[0]?.id, first.reservationId);
    await assert.rejects(
      reservePrivateMediaUpload({ uid: ownerUid }, { ...input, byteSize: 4 }, {
        repository,
        signer,
        nowMillis: () => NOW_MILLIS + 1,
        reservationId: () => "private_media_race_c",
      }),
      (error: unknown) => error instanceof PrivateMediaError && error.code === "invalid-argument",
    );

    const foreignReservationId = "private_media_foreign";
    await firestore.doc(`privateMediaReservations/${foreignReservationId}`).set({
      ...reservations.docs[0]?.data(),
      reservationId: foreignReservationId,
      ownerUid: "foreign-owner",
      objectPath: `private-media-v2/${foreignReservationId}`,
    });
    await firestore.doc(`privateMediaReservationReceipts/${receiptId(ownerUid, idempotencyKey)}`)
      .update({ reservationId: foreignReservationId });
    await assert.rejects(
      reservePrivateMediaUpload({ uid: ownerUid }, input, {
        repository,
        signer,
        nowMillis: () => NOW_MILLIS + 1,
        reservationId: () => "private_media_race_d",
      }),
      (error: unknown) => error instanceof PrivateMediaError
        && error.code === "failed-precondition",
    );
  } finally {
    await Promise.all([
      ...reservationIds.map((reservationId) =>
        firestore.doc(`privateMediaReservations/${reservationId}`).delete()
      ),
      firestore.doc("privateMediaReservations/private_media_race_c").delete(),
      firestore.doc("privateMediaReservations/private_media_race_d").delete(),
      firestore.doc("privateMediaReservations/private_media_foreign").delete(),
      firestore.doc(`privateMediaReservationReceipts/${receiptId(ownerUid, idempotencyKey)}`).delete(),
      firestore.doc(`accountDeletionRequests/${ownerUid}`).delete(),
    ]);
    await deleteApp(app);
  }
});

test("real Firestore and Storage adapters commit, resolve, finalize-delete, and seal exactly", async () => {
  // Given
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.ok(process.env.STORAGE_EMULATOR_HOST);
  const app = initializeApp(
    { projectId: PROJECT_ID, storageBucket: BUCKET_NAME },
    "private-media-lifecycle-emulator",
  );
  const firestore = getFirestore(app);
  const storage = getStorage(app);
  const repository = new FirestorePrivateMediaReservationRepository(firestore);
  const objects = new FirebasePrivateMediaObjectStore(storage);
  const ownerUid = "private-media-lifecycle-owner";
  const committedId = "private_media_committed";
  const finalizedId = "private_media_finalized";
  const committedPath = `private-media-v2/${committedId}`;
  const finalizedPath = `private-media-v2/${finalizedId}`;
  const committedKey = "private-media-commit-operation";
  const finalizedKey = "private-media-finalize-operation";
  const bytes = Buffer.from([1, 2, 3]);

  async function reserve(reservationId: string, idempotencyKey: string) {
    return reservePrivateMediaUpload({ uid: ownerUid }, {
      expectedOwnerUid: ownerUid,
      mediaKind: "IDENTIFICATION_ORIGINAL",
      contentType: "image/webp",
      byteSize: bytes.length,
      idempotencyKey,
    }, {
      repository,
      signer,
      nowMillis: () => NOW_MILLIS,
      reservationId: () => reservationId,
    });
  }

  try {
    await Promise.all([
      firestore.doc(`privateMediaReservations/${committedId}`).delete(),
      firestore.doc(`privateMediaReservations/${finalizedId}`).delete(),
      firestore.doc(`privateMediaReservationReceipts/${receiptId(ownerUid, committedKey)}`).delete(),
      firestore.doc(`privateMediaReservationReceipts/${receiptId(ownerUid, finalizedKey)}`).delete(),
      firestore.doc(`accountDeletionRequests/${ownerUid}`).delete(),
      storage.bucket().file(committedPath).delete({ ignoreNotFound: true }),
      storage.bucket().file(finalizedPath).delete({ ignoreNotFound: true }),
    ]);
    await Promise.all([
      reserve(committedId, committedKey),
      reserve(finalizedId, finalizedKey),
    ]);
    await Promise.all([
      storage.bucket().file(committedPath).save(bytes, {
        resumable: false,
        metadata: {
          contentType: "image/webp",
          metadata: { ownerUid, reservationId: committedId },
        },
      }),
      storage.bucket().file(finalizedPath).save(bytes, {
        resumable: false,
        metadata: {
          contentType: "image/webp",
          metadata: { ownerUid, reservationId: finalizedId },
        },
      }),
    ]);

    // When
    const committed = await commitPrivateMediaReservation({ uid: ownerUid }, {
      expectedOwnerUid: ownerUid,
      reservationId: committedId,
    }, { repository, objects, nowMillis: () => NOW_MILLIS + 1 });
    const resolved = await repository.resolve({
      ownerUid,
      reference: committed.reference,
      mediaKind: "IDENTIFICATION_ORIGINAL",
    });

    // Then
    assert.equal(resolved?.objectPath, committedPath);
    assert.equal(await repository.resolve({
      ownerUid: "foreign-owner",
      reference: committed.reference,
      mediaKind: "IDENTIFICATION_ORIGINAL",
    }), null);
    assert.equal(await repository.resolve({
      ownerUid,
      reference: {
        ...committed.reference,
        generation: committed.reference.generation === "1" ? "2" : "1",
      },
      mediaKind: "IDENTIFICATION_ORIGINAL",
    }), null);

    const finalized = await objects.inspect(finalizedPath);
    assert.ok(finalized !== null);
    const deletionAt = Timestamp.fromMillis(NOW_MILLIS + 2);
    await firestore.doc(`accountDeletionRequests/${ownerUid}`).set({
      ownerUid,
      status: "PROCESSING",
      completedScopes: ["PUBLIC_SHARES"],
      updatedAt: deletionAt,
    });
    await handlePrivateMediaFinalized({ object: finalized, repository, objects });
    assert.equal(await objects.inspect(finalizedPath), null);

    await sealOwnerPrivateMedia({
      ownerUid,
      repository,
      objects,
      nowMillis: () => NOW_MILLIS + 3,
    });
    for (const reservationId of [committedId, finalizedId]) {
      const reservation = await repository.load(reservationId);
      const object = await objects.inspect(`private-media-v2/${reservationId}`);
      assert.equal(reservation?.state, "SEALED");
      assert.equal(reservation?.sealedGeneration, object?.generation);
      assert.equal(object?.byteSize, 0);
      assert.deepEqual(object?.customMetadata, { privateMediaSeal: "true" });
    }
    assert.equal(await repository.resolve({
      ownerUid,
      reference: committed.reference,
      mediaKind: "IDENTIFICATION_ORIGINAL",
    }), null);
  } finally {
    await Promise.all([
      firestore.doc(`privateMediaReservations/${committedId}`).delete(),
      firestore.doc(`privateMediaReservations/${finalizedId}`).delete(),
      firestore.doc(`privateMediaReservationReceipts/${receiptId(ownerUid, committedKey)}`).delete(),
      firestore.doc(`privateMediaReservationReceipts/${receiptId(ownerUid, finalizedKey)}`).delete(),
      firestore.doc(`accountDeletionRequests/${ownerUid}`).delete(),
      storage.bucket().file(committedPath).delete({ ignoreNotFound: true }),
      storage.bucket().file(finalizedPath).delete({ ignoreNotFound: true }),
    ]);
    await deleteApp(app);
  }
});
