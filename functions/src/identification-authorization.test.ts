import assert from "node:assert/strict";
import test from "node:test";
import {
  createIdentificationRequest,
  IDENTIFICATION_DISCLOSURE_VERSION,
  IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
  IdentificationAuthorizationError,
  effectiveRetentionExpiryMillis,
  isIdentificationOriginalReadable,
} from "./identification-authorization.js";
import {
  MemoryIdentificationAuthorizationRepository,
  MemoryIdentificationRequestAdmissionRepository,
} from "./identification-authorization-test-fixture.test.js";
import {
  reservePrivateMediaUpload,
  commitPrivateMediaReservation,
  type PrivateMediaSigner,
} from "./private-media.js";
import {
  FakePrivateMediaObjectStore,
  MemoryPrivateMediaRepository,
} from "./private-media-test-fixture.test.js";

const NOW_MILLIS = Date.parse("2026-08-24T00:00:00.000Z");
const OWNER = { uid: "user-a" };
const REQUEST_ID = "identification_request_12345678";
const signer: PrivateMediaSigner = {
  async signPut(command) {
    return { url: `https://upload.invalid/${command.reservationId}` };
  },
};

async function committedOriginal(mediaKind: "IDENTIFICATION_ORIGINAL" | "PLANT_PHOTO") {
  const media = new MemoryPrivateMediaRepository();
  const objects = new FakePrivateMediaObjectStore();
  const reservationId = `reservation_${mediaKind.toLowerCase()}_1234`;
  await reservePrivateMediaUpload(OWNER, {
    expectedOwnerUid: OWNER.uid,
    mediaKind,
    contentType: "image/webp",
    byteSize: 3,
    idempotencyKey: `identification-authorization-${mediaKind}`,
  }, {
    repository: media,
    signer,
    nowMillis: () => NOW_MILLIS,
    reservationId: () => reservationId,
  });
  objects.admitUpload({
    path: `private-media-v2/${reservationId}`,
    byteSize: 3,
    contentType: "image/webp",
    customMetadata: { ownerUid: OWNER.uid, reservationId },
  })();
  const committed = await commitPrivateMediaReservation(OWNER, {
    expectedOwnerUid: OWNER.uid,
    reservationId,
  }, { repository: media, objects, nowMillis: () => NOW_MILLIS + 1 });
  return { media, objects, reference: committed.reference };
}

async function fixture() {
  const original = await committedOriginal("IDENTIFICATION_ORIGINAL");
  const requests = new MemoryIdentificationAuthorizationRepository();
  const admissions = new MemoryIdentificationRequestAdmissionRepository(requests, original.media);
  return {
    ...original,
    requests,
    admissions,
    dependencies: {
      admissions,
      nowMillis: () => NOW_MILLIS + 2,
    },
    input: {
      expectedOwnerUid: OWNER.uid,
      requestId: REQUEST_ID,
      mediaReference: original.reference,
      disclosureVersion: IDENTIFICATION_DISCLOSURE_VERSION,
    },
  };
}

test("server acknowledges an approved request with a server-clock 24 hour hard expiry", async () => {
  // Given
  const current = await fixture();

  // When
  const created = await createIdentificationRequest(OWNER, current.input, current.dependencies);

  // Then
  assert.equal(created.requestId, REQUEST_ID);
  assert.equal(created.disclosureVersion, IDENTIFICATION_DISCLOSURE_VERSION);
  assert.equal(created.createdAtMillis, NOW_MILLIS + 2);
  assert.equal(created.acknowledgedAtMillis, NOW_MILLIS + 2);
  assert.equal(
    created.hardExpiresAtMillis - created.createdAtMillis,
    IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
  );
  const stored = await current.requests.load(OWNER.uid, REQUEST_ID);
  assert.equal(stored?.status, "APPROVED");
  assert.equal(stored?.sendState, "NOT_SENT");
  assert.equal(stored?.retentionExpiresAtMillis, null);
  assert.equal(
    (await current.media.load(current.reference.reservationId))?.identificationRequestId,
    REQUEST_ID,
  );
});

test("injected request creation failure rolls back the reservation link and retry admits exactly once", async () => {
  const current = await fixture();
  current.admissions.failNextRequestCreate = true;

  await assert.rejects(
    createIdentificationRequest(OWNER, current.input, current.dependencies),
    /Injected request create failure/,
  );
  assert.equal(current.requests.records.size, 0);
  assert.equal(
    (await current.media.load(current.reference.reservationId))?.identificationRequestId,
    null,
  );

  const retried = await createIdentificationRequest(OWNER, current.input, current.dependencies);
  assert.equal(retried.requestId, REQUEST_ID);
  assert.equal(current.requests.records.size, 1);
  assert.equal(
    (await current.media.load(current.reference.reservationId))?.identificationRequestId,
    REQUEST_ID,
  );
});

test("concurrent exact admissions converge on one linked request", async () => {
  const current = await fixture();
  const [first, second] = await Promise.all([
    createIdentificationRequest(OWNER, current.input, current.dependencies),
    createIdentificationRequest(OWNER, current.input, current.dependencies),
  ]);
  assert.deepEqual(second, first);
  assert.equal(current.requests.records.size, 1);
  assert.equal(
    (await current.media.load(current.reference.reservationId))?.identificationRequestId,
    REQUEST_ID,
  );
});

test("admission at an unlinked original's exact 24-hour boundary is fenced before cleanup", async () => {
  const current = await fixture();
  await assert.rejects(
    createIdentificationRequest(OWNER, current.input, {
      ...current.dependencies,
      nowMillis: () => NOW_MILLIS + 1 + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
    }),
    (error: unknown) => error instanceof IdentificationAuthorizationError
      && error.code === "failed-precondition",
  );
  assert.equal(current.requests.records.size, 0);
  assert.equal(
    (await current.media.load(current.reference.reservationId))?.identificationRequestId,
    null,
  );
});

test("retrying the exact request id is idempotent and never doubles the authorization", async () => {
  // Given
  const current = await fixture();

  // When
  const first = await createIdentificationRequest(OWNER, current.input, current.dependencies);
  const retried = await createIdentificationRequest(OWNER, current.input, {
    ...current.dependencies,
    nowMillis: () => NOW_MILLIS + 60_000,
  });

  // Then
  assert.deepEqual(retried, first);
  assert.equal(current.requests.records.size, 1);
});

test("unauthenticated, cross-owner, unapproved disclosure and uncommitted media are denied", async () => {
  // Given
  const current = await fixture();

  // When / Then
  await assert.rejects(
    createIdentificationRequest(null, current.input, current.dependencies),
    (error: unknown) => error instanceof IdentificationAuthorizationError
      && error.code === "unauthenticated",
  );
  await assert.rejects(
    createIdentificationRequest({ uid: "user-b" }, current.input, current.dependencies),
    (error: unknown) => error instanceof IdentificationAuthorizationError
      && error.code === "permission-denied",
  );
  await assert.rejects(
    createIdentificationRequest(
      OWNER,
      { ...current.input, disclosureVersion: IDENTIFICATION_DISCLOSURE_VERSION + 1 },
      current.dependencies,
    ),
    (error: unknown) => error instanceof IdentificationAuthorizationError
      && error.code === "invalid-argument",
  );
  await assert.rejects(
    createIdentificationRequest(
      OWNER,
      { ...current.input, unexpected: true },
      current.dependencies,
    ),
    (error: unknown) => error instanceof IdentificationAuthorizationError
      && error.code === "invalid-argument",
  );
  await assert.rejects(
    createIdentificationRequest(
      OWNER,
      {
        ...current.input,
        mediaReference: { ...current.reference, generation: "999" },
      },
      current.dependencies,
    ),
    (error: unknown) => error instanceof IdentificationAuthorizationError
      && error.code === "failed-precondition",
  );
  assert.equal(current.requests.records.size, 0);
});

test("reusing an id for a different committed original is denied rather than replayed", async () => {
  const current = await fixture();
  await createIdentificationRequest(OWNER, current.input, current.dependencies);
  await assert.rejects(
    createIdentificationRequest(
      OWNER,
      { ...current.input, mediaReference: { ...current.reference, generation: "8" } },
      current.dependencies,
    ),
    (error: unknown) => error instanceof IdentificationAuthorizationError
      && error.code === "failed-precondition",
  );
});

test("a separately committed PLANT_PHOTO can never be admitted as an identification original", async () => {
  // Given
  const photo = await committedOriginal("PLANT_PHOTO");
  const requests = new MemoryIdentificationAuthorizationRepository();
  const admissions = new MemoryIdentificationRequestAdmissionRepository(requests, photo.media);

  // When / Then
  await assert.rejects(
    createIdentificationRequest(OWNER, {
      expectedOwnerUid: OWNER.uid,
      requestId: REQUEST_ID,
      mediaReference: photo.reference,
      disclosureVersion: IDENTIFICATION_DISCLOSURE_VERSION,
    }, { admissions, nowMillis: () => NOW_MILLIS + 2 }),
    (error: unknown) => error instanceof IdentificationAuthorizationError
      && error.code === "failed-precondition",
  );
  assert.equal(requests.records.size, 0);
});

test("effective retention is terminal plus 24 hours and abandoned stays created plus 24 hours", () => {
  // Given
  const abandoned = {
    createdAtMillis: NOW_MILLIS,
    hardExpiresAtMillis: NOW_MILLIS + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
    retentionExpiresAtMillis: null,
  } as const;
  const terminalAtMillis = NOW_MILLIS + 5 * 60 * 1_000;
  const terminal = {
    ...abandoned,
    retentionExpiresAtMillis: terminalAtMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
  } as const;

  // When / Then
  assert.equal(effectiveRetentionExpiryMillis(abandoned), abandoned.hardExpiresAtMillis);
  assert.equal(
    effectiveRetentionExpiryMillis(terminal),
    terminalAtMillis + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
  );
  assert.equal(
    isIdentificationOriginalReadable(terminal, effectiveRetentionExpiryMillis(terminal) - 60_000),
    true,
  );
  assert.equal(
    isIdentificationOriginalReadable(terminal, effectiveRetentionExpiryMillis(terminal)),
    false,
  );
  assert.equal(
    isIdentificationOriginalReadable(abandoned, abandoned.hardExpiresAtMillis - 1),
    true,
  );
  assert.equal(isIdentificationOriginalReadable(abandoned, abandoned.hardExpiresAtMillis), false);
});
