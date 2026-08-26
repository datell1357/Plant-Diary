import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { deleteApp, initializeApp, type App } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { getStorage, type Storage } from "firebase-admin/storage";
import {
  ACCOUNT_DELETION_SCOPES,
  type AccountDeletionStatus,
} from "./account-deletion-contract.js";
import { FirebasePrivateMediaObjectStore } from "./firebase-private-media.js";
import { FirestoreAccountDeletionStore } from "./firestore-account-deletion-store.js";
import {
  FirestorePrivateMediaReservationRepository,
  privateMediaReceiptId,
} from "./firestore-private-media.js";
import {
  commitPrivateMediaReservation,
  reservePrivateMediaUpload,
  type CommittedPrivateMedia,
  type PrivateMediaObject,
  type PrivateMediaSigner,
} from "./private-media.js";
import { handlePrivateMediaFinalized } from "./private-media-seal.js";

const PROJECT_ID = "demo-planterior";
const BUCKET_NAME = `${PROJECT_ID}.firebasestorage.app`;
const NOW_MILLIS = Date.parse("2026-08-26T00:00:00.000Z");
const signer: PrivateMediaSigner = {
  async signPut(command) {
    return { url: `https://upload.invalid/${command.reservationId}` };
  },
};

type EmulatorContext = Readonly<{
  app: App;
  firestore: Firestore;
  storage: Storage;
  repository: FirestorePrivateMediaReservationRepository;
  objects: FirebasePrivateMediaObjectStore;
}>;
type CommittedMedia = Readonly<{
  ownerUid: string;
  reservationId: string;
  objectPath: string;
  receiptPath: string;
  committed: CommittedPrivateMedia;
  finalized: PrivateMediaObject;
}>;

function createContext(name: string): EmulatorContext {
  const app = initializeApp({ projectId: PROJECT_ID, storageBucket: BUCKET_NAME }, name);
  const firestore = getFirestore(app);
  const storage = getStorage(app);
  return {
    app,
    firestore,
    storage,
    repository: new FirestorePrivateMediaReservationRepository(firestore),
    objects: new FirebasePrivateMediaObjectStore(storage),
  };
}

async function createCommittedMedia(
  context: EmulatorContext,
  suffix: string,
): Promise<CommittedMedia> {
  const ownerUid = `finalize-cancel-${suffix}`;
  const reservationId = `finalize_cancel_${suffix}`;
  const idempotencyKey = `private-media-finalize-${suffix}-operation`;
  const objectPath = `private-media-v2/${reservationId}`;
  const idempotencyKeyHash = createHash("sha256").update(idempotencyKey).digest("hex");
  const receiptPath =
    `privateMediaReservationReceipts/${privateMediaReceiptId(ownerUid, idempotencyKeyHash)}`;
  await reservePrivateMediaUpload({ uid: ownerUid }, {
    expectedOwnerUid: ownerUid,
    mediaKind: "PLANT_PHOTO",
    contentType: "image/webp",
    byteSize: 3,
    idempotencyKey,
  }, {
    repository: context.repository,
    signer,
    nowMillis: () => NOW_MILLIS,
    reservationId: () => reservationId,
  });
  await context.storage.bucket().file(objectPath).save(Buffer.from([1, 2, 3]), {
    resumable: false,
    metadata: {
      contentType: "image/webp",
      metadata: { ownerUid, reservationId },
    },
  });
  const committed = await commitPrivateMediaReservation({ uid: ownerUid }, {
    expectedOwnerUid: ownerUid,
    reservationId,
  }, { repository: context.repository, objects: context.objects, nowMillis: () => NOW_MILLIS + 1 });
  const finalized = await context.objects.inspect(objectPath);
  assert.ok(finalized !== null);
  return { ownerUid, reservationId, objectPath, receiptPath, committed, finalized };
}

async function requestReceived(
  context: EmulatorContext,
  media: CommittedMedia,
  requestId: string,
): Promise<FirestoreAccountDeletionStore> {
  const store = new FirestoreAccountDeletionStore(context.firestore);
  await store.request({
    record: {
      schemaVersion: 1,
      ownerUid: media.ownerUid,
      requestId,
      idempotencyKeyHash: createHash("sha256").update(requestId).digest("hex"),
      status: "RECEIVED",
      requestedAtMillis: NOW_MILLIS + 2,
      scheduledForMillis: NOW_MILLIS + 10_000,
      nextAttemptAtMillis: NOW_MILLIS + 10_000,
      leaseExpiresAtMillis: null,
      completedAtMillis: null,
      completedScopes: [],
      failedScopes: [],
      claimGeneration: 0,
      analyticsResultEligible: false,
      analyticsRequestOutcome: "CONSENT_OFF",
      analyticsRecordedResultKeys: [],
      updatedAtMillis: NOW_MILLIS + 2,
    },
  });
  return store;
}

async function disposeContext(
  context: EmulatorContext,
  media: readonly CommittedMedia[],
): Promise<void> {
  await Promise.all(media.flatMap((item) => [
    context.firestore.doc(`privateMediaReservations/${item.reservationId}`).delete(),
    context.firestore.doc(item.receiptPath).delete(),
    context.firestore.doc(`accountDeletionRequests/${item.ownerUid}`).delete(),
    context.firestore.recursiveDelete(
      context.firestore.doc(`accountDeletionReceipts/${item.ownerUid}`),
    ),
    context.firestore.recursiveDelete(context.firestore.doc(`users/${item.ownerUid}`)),
    context.storage.bucket().file(item.objectPath).delete({ ignoreNotFound: true }),
  ]));
  await deleteApp(context.app);
}

async function assertMediaIntact(
  context: EmulatorContext,
  media: CommittedMedia,
): Promise<void> {
  assert.equal((await context.repository.resolve({
    ownerUid: media.ownerUid,
    reference: media.committed.reference,
    mediaKind: "PLANT_PHOTO",
  }))?.objectPath, media.objectPath);
  assert.equal(
    (await context.objects.inspect(media.objectPath))?.generation,
    media.committed.reference.generation,
  );
}

test("RECEIVED finalize remains resolvable and present after cancellation", async () => {
  // Given
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.ok(process.env.STORAGE_EMULATOR_HOST);
  const context = createContext("private-media-finalize-received");
  const media = await createCommittedMedia(context, "received");
  const requestId = "finalize-received-request";
  const store = await requestReceived(context, media, requestId);

  try {
    // When
    await handlePrivateMediaFinalized({ object: media.finalized, repository: context.repository, objects: context.objects });
    const cancelled = await store.cancel({
      ownerUid: media.ownerUid,
      requestId,
      nowMillis: NOW_MILLIS + 3,
    });

    // Then
    assert.equal(cancelled?.status, "CANCELLED");
    await assertMediaIntact(context, media);
  } finally {
    await disposeContext(context, [media]);
  }
});

test("PROCESSING without progress survives FAILED recovery and cancellation", async () => {
  // Given
  const context = createContext("private-media-finalize-processing-failed");
  const media = await createCommittedMedia(context, "processing_failed");
  const requestId = "finalize-processing-failed-request";
  const store = await requestReceived(context, media, requestId);
  const claimed = await store.claimDue({
    nowMillis: NOW_MILLIS + 10_000,
    leaseExpiresAtMillis: NOW_MILLIS + 20_000,
    limit: 1,
  });
  const processing = claimed[0];
  assert.ok(processing !== undefined);
  assert.equal(processing.status, "PROCESSING");
  assert.deepEqual(processing.completedScopes, []);

  try {
    // When
    await handlePrivateMediaFinalized({ object: media.finalized, repository: context.repository, objects: context.objects });
    const failed = await store.finish({
      ownerUid: media.ownerUid,
      requestId,
      claimGeneration: processing.claimGeneration,
      completedScopes: [],
      failedScopes: ACCOUNT_DELETION_SCOPES,
      nowMillis: NOW_MILLIS + 10_001,
    });
    const recovered = await store.retry({
      ownerUid: media.ownerUid,
      requestId: "finalize-processing-recovery-request",
      idempotencyKeyHash: "c".repeat(64),
      nowMillis: NOW_MILLIS + 10_002,
      scheduledForMillis: NOW_MILLIS + 30_000,
    });
    assert.equal(failed.status, "FAILED");
    assert.equal(recovered?.status, "RECEIVED");
    const cancelled = await store.cancel({
      ownerUid: media.ownerUid,
      requestId: "finalize-processing-recovery-request",
      nowMillis: NOW_MILLIS + 10_003,
    });

    // Then
    assert.equal(cancelled?.status, "CANCELLED");
    await assertMediaIntact(context, media);
  } finally {
    await disposeContext(context, [media]);
  }
});

const destructiveScenarios: readonly Readonly<{
  suffix: string;
  status: AccountDeletionStatus;
  completedScopes: readonly ["PUBLIC_SHARES"];
}>[] = [
  { suffix: "processing_progress", status: "PROCESSING", completedScopes: ["PUBLIC_SHARES"] },
  { suffix: "partially_failed", status: "PARTIALLY_FAILED", completedScopes: ["PUBLIC_SHARES"] },
  { suffix: "completed", status: "COMPLETED", completedScopes: ["PUBLIC_SHARES"] },
];

for (const scenario of destructiveScenarios) {
  test(`finalize deletes exact COMMITTED media for ${scenario.status} with progress`, async () => {
    // Given
    const context = createContext(`private-media-finalize-${scenario.suffix}`);
    const media = await createCommittedMedia(context, scenario.suffix);
    await context.firestore.doc(`accountDeletionRequests/${media.ownerUid}`).set({
      status: scenario.status,
      completedScopes: scenario.completedScopes,
    });

    try {
      // When
      await handlePrivateMediaFinalized({ object: media.finalized, repository: context.repository, objects: context.objects });

      // Then
      assert.equal(await context.objects.inspect(media.objectPath), null);
    } finally {
      await disposeContext(context, [media]);
    }
  });
}
