import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import {
  FirestoreIdentificationRequestStore,
  IdentificationRuntimeError,
  PlantIdHttpClient,
  PlantIdStorageProvider,
} from "./plant-identification-runtime.js";
import { executePlantIdentification } from "./plant-identification.js";

const projectId = "demo-planterior";
const ownerUid = "handoff-owner";
const requestId = "handoff_request_12345678";
const operationId = "handoff_operation_12345678";
const reservationId = "handoff_reservation_12345678";
const photoPath = `private-media-v2/${reservationId}`;
const requestPath = `users/${ownerUid}/identificationRequests/${requestId}`;
const bucketName = `${projectId}.firebasestorage.app`;

test("approved photo handoff is retrieved from real local emulators before provider invocation", async () => {
  // Given
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.ok(process.env.STORAGE_EMULATOR_HOST);
  const app = initializeApp({ projectId, storageBucket: bucketName }, "identify-handoff-emulator");
  const firestore = getFirestore(app);
  const storage = getStorage(app);
  const file = storage.bucket().file(photoPath);
  const request = firestore.doc(requestPath);
  const reservation = firestore.doc(`privateMediaReservations/${reservationId}`);
  const bytes = Buffer.from([1, 4, 9, 16]);
  const createdAt = Timestamp.fromDate(new Date("2026-08-12T00:00:00Z"));
  const expiresAt = Timestamp.fromDate(new Date("2026-08-13T00:00:00Z"));
  let providerCalls = 0;
  const client = new PlantIdHttpClient("local-only", {
    async post(input) {
      providerCalls += 1;
      assert.equal(input.image, `data:image/webp;base64,${bytes.toString("base64")}`);
      return { status: 200, body: { result: { classification: { suggestions: [] } } } };
    },
  });

  try {
    await Promise.all([
      request.delete(),
      reservation.delete(),
      file.delete({ ignoreNotFound: true }),
    ]);

    // When
    await file.save(bytes, {
      metadata: {
        contentType: "image/webp",
        metadata: { ownerUid, reservationId },
      },
    });
    const [uploaded] = await file.getMetadata();
    await reservation.set({
      schemaVersion: 1,
      reservationId,
      ownerUid,
      mediaKind: "IDENTIFICATION_ORIGINAL",
      contentType: "image/webp",
      byteSize: bytes.length,
      objectPath: photoPath,
      idempotencyKeyHash: "a".repeat(64),
      requestHash: "b".repeat(64),
      state: "COMMITTED",
      objectGeneration: uploaded.generation,
      sealedGeneration: null,
      createdAt,
      expiresAt,
      committedAt: createdAt,
      sealedAt: null,
    });
    await request.set({
      ownerUid,
      mediaReference: { reservationId, generation: uploaded.generation },
      createdAt,
      expiresAt,
      revision: 1,
      expectedRevision: 0,
      idempotencyKey: requestId,
      updatedAt: createdAt.toDate().toISOString(),
    });
    const result = await executePlantIdentification(
      { uid: ownerUid },
      { requestId, idempotencyKey: operationId },
      new FirestoreIdentificationRequestStore(firestore),
      new PlantIdStorageProvider(client, storage),
    );

    // Then
    assert.deepEqual(result, { kind: "no_candidates" });
    assert.equal(providerCalls, 1);
    const stored = await request.get();
    assert.deepEqual(stored.get("mediaReference"), {
      reservationId,
      generation: uploaded.generation,
    });
    assert.equal(stored.get("expiresAt").toMillis() - stored.get("createdAt").toMillis(), 86_400_000);
    const [metadata] = await file.getMetadata();
    assert.equal(metadata.metadata?.ownerUid, ownerUid);
    assert.equal(metadata.metadata?.reservationId, reservationId);
  } finally {
    await Promise.all([
      request.delete(),
      reservation.delete(),
      file.delete({ ignoreNotFound: true }),
    ]);
    await deleteApp(app);
  }
});

test("missing emulator request returns not_found without provider transmission", async () => {
  // Given
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const app = initializeApp({ projectId }, "identify-missing-request-emulator");
  const firestore = getFirestore(app);
  const missingRequestId = "missing_request_12345678";
  const missingRequest = firestore.doc(
    `users/${ownerUid}/identificationRequests/${missingRequestId}`,
  );
  let providerCalls = 0;

  try {
    await missingRequest.delete();

    // When / Then
    await assert.rejects(
      executePlantIdentification(
        { uid: ownerUid },
        { requestId: missingRequestId, idempotencyKey: operationId },
        new FirestoreIdentificationRequestStore(firestore),
        {
          async identify() {
            providerCalls += 1;
            return { result: { classification: { suggestions: [] } } };
          },
        },
      ),
      (error: unknown) => error instanceof IdentificationRuntimeError
        && error.reason === "not_found",
    );
    assert.equal(providerCalls, 0);
  } finally {
    await missingRequest.delete();
    await deleteApp(app);
  }
});
