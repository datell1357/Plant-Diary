import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import {
  FirebasePrivateMediaObjectStore,
} from "./firebase-private-media.js";
import {
  FirestorePrivateMediaReservationRepository,
  privateMediaReceiptId,
} from "./firestore-private-media.js";
import {
  commitPrivateMediaReservation,
  PrivateMediaError,
  reservePrivateMediaUpload,
  type PrivateMediaSigner,
} from "./private-media.js";
import { handlePrivateMediaFinalized } from "./private-media-seal.js";

const PROJECT_ID = "demo-planterior";
const BUCKET_NAME = `${PROJECT_ID}.firebasestorage.app`;
const NOW_MILLIS = Date.parse("2026-08-26T00:00:00.000Z");
const OWNER_UID = "private-media-size-owner";
const signer: PrivateMediaSigner = {
  async signPut(command) {
    return { url: `https://upload.invalid/${command.reservationId}` };
  },
};

function receiptPath(idempotencyKey: string): string {
  const keyHash = createHash("sha256").update(idempotencyKey).digest("hex");
  return `privateMediaReservationReceipts/${privateMediaReceiptId(OWNER_UID, keyHash)}`;
}

test("real adapters generation-delete oversized commit and RESERVED finalize mismatches", async () => {
  // Given
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.ok(process.env.STORAGE_EMULATOR_HOST);
  const app = initializeApp(
    { projectId: PROJECT_ID, storageBucket: BUCKET_NAME },
    "private-media-size-binding-emulator",
  );
  const firestore = getFirestore(app);
  const storage = getStorage(app);
  const repository = new FirestorePrivateMediaReservationRepository(firestore);
  const objects = new FirebasePrivateMediaObjectStore(storage);
  const commitCase = {
    reservationId: "private_size_commit",
    idempotencyKey: "private-size-commit-operation",
  } as const;
  const finalizeCase = {
    reservationId: "private_size_finalize",
    idempotencyKey: "private-size-finalize-operation",
  } as const;
  const cases = [commitCase, finalizeCase] as const;
  const commitPath = `private-media-v2/${commitCase.reservationId}`;
  const finalizePath = `private-media-v2/${finalizeCase.reservationId}`;
  const paths = [commitPath, finalizePath] as const;

  try {
    await Promise.all([
      ...cases.map(({ reservationId }) =>
        firestore.doc(`privateMediaReservations/${reservationId}`).delete()
      ),
      ...cases.map(({ idempotencyKey }) => firestore.doc(receiptPath(idempotencyKey)).delete()),
      ...paths.map((path) => storage.bucket().file(path).delete({ ignoreNotFound: true })),
    ]);
    await Promise.all(cases.map(({ reservationId, idempotencyKey }) =>
      reservePrivateMediaUpload({ uid: OWNER_UID }, {
        expectedOwnerUid: OWNER_UID,
        mediaKind: "IDENTIFICATION_ORIGINAL",
        contentType: "image/webp",
        byteSize: 3,
        idempotencyKey,
      }, {
        repository,
        signer,
        nowMillis: () => NOW_MILLIS,
        reservationId: () => reservationId,
      })
    ));
    await Promise.all(cases.map(({ reservationId }) =>
      storage.bucket().file(`private-media-v2/${reservationId}`).save(Buffer.from([1, 2, 3, 4]), {
        resumable: false,
        metadata: {
          contentType: "image/webp",
          metadata: { ownerUid: OWNER_UID, reservationId },
        },
      })
    ));
    const finalized = await objects.inspect(finalizePath);
    assert.ok(finalized !== null);

    // When
    await assert.rejects(
      commitPrivateMediaReservation({ uid: OWNER_UID }, {
        expectedOwnerUid: OWNER_UID,
        reservationId: commitCase.reservationId,
      }, { repository, objects, nowMillis: () => NOW_MILLIS + 1 }),
      (error: unknown) => error instanceof PrivateMediaError && error.code === "failed-precondition",
    );
    await handlePrivateMediaFinalized({ object: finalized, repository, objects });

    // Then
    assert.equal(await objects.inspect(commitPath), null);
    assert.equal(await objects.inspect(finalizePath), null);
  } finally {
    await Promise.all([
      ...cases.map(({ reservationId }) =>
        firestore.doc(`privateMediaReservations/${reservationId}`).delete()
      ),
      ...cases.map(({ idempotencyKey }) => firestore.doc(receiptPath(idempotencyKey)).delete()),
      ...paths.map((path) => storage.bucket().file(path).delete({ ignoreNotFound: true })),
    ]);
    await deleteApp(app);
  }
});
