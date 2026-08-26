import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getStorage } from "firebase-admin/storage";
import type { GetSignedUrlConfig } from "@google-cloud/storage";
import { FirebasePrivateMediaSigner } from "./firebase-private-media.js";

const PROJECT_ID = "demo-planterior";
const OBJECT_PATH = "private-media-v2/reservation_12345678";

test("Firebase V4 signer includes exact content length in canonical extension headers", async (context) => {
  // Given
  const app = initializeApp(
    { projectId: PROJECT_ID, storageBucket: `${PROJECT_ID}.firebasestorage.app` },
    "private-media-signer-options",
  );
  const storage = getStorage(app);
  const bucket = storage.bucket();
  const file = bucket.file(OBJECT_PATH);
  let captured: GetSignedUrlConfig | undefined;
  context.mock.method(storage, "bucket", () => bucket);
  context.mock.method(bucket, "file", (path: string) => {
    assert.equal(path, OBJECT_PATH);
    return file;
  });
  context.mock.method(file, "getSignedUrl", async (config: GetSignedUrlConfig) => {
    captured = config;
    return ["https://upload.invalid/signed"];
  });

  try {
    // When
    const result = await new FirebasePrivateMediaSigner(storage).signPut({
      reservationId: "reservation_12345678",
      objectPath: OBJECT_PATH,
      contentType: "image/webp",
      expiresAtMillis: Date.parse("2026-08-26T00:10:00.000Z"),
      requiredHeaders: {
        "content-length": "3",
        "content-type": "image/webp",
        "x-goog-if-generation-match": "0",
        "x-goog-meta-owner-uid": "owner-a",
        "x-goog-meta-reservation-id": "reservation_12345678",
      },
    });

    // Then
    assert.equal(result.url, "https://upload.invalid/signed");
    assert.deepEqual(captured?.extensionHeaders, {
      "content-length": "3",
      "x-goog-if-generation-match": "0",
      "x-goog-meta-owner-uid": "owner-a",
      "x-goog-meta-reservation-id": "reservation_12345678",
    });
    assert.equal(captured?.contentType, "image/webp");
  } finally {
    await deleteApp(app);
  }
});
