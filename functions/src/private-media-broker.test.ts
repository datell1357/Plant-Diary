import assert from "node:assert/strict";
import test from "node:test";
import {
  commitPrivateMediaReservation,
  PrivateMediaError,
  reservePrivateMediaUpload,
  type PrivateMediaObject,
  type PrivateMediaSigner,
} from "./private-media.js";
import {
  FakePrivateMediaObjectStore,
  MemoryPrivateMediaRepository,
} from "./private-media-test-fixture.test.js";

const NOW_MILLIS = Date.parse("2026-08-24T00:00:00.000Z");
const OWNER = { uid: "user-a" };
const RESERVE_INPUT = {
  expectedOwnerUid: "user-a",
  mediaKind: "IDENTIFICATION_ORIGINAL",
  contentType: "image/webp",
  byteSize: 3,
  idempotencyKey: "media-operation-0001",
} as const;

class RecordingSigner implements PrivateMediaSigner {
  readonly calls: Parameters<PrivateMediaSigner["signPut"]>[0][] = [];

  async signPut(command: Parameters<PrivateMediaSigner["signPut"]>[0]) {
    this.calls.push(command);
    return { url: `https://upload.invalid/${command.reservationId}` };
  }
}

function dependencies() {
  const repository = new MemoryPrivateMediaRepository();
  const signer = new RecordingSigner();
  const objects = new FakePrivateMediaObjectStore();
  return {
    repository,
    signer,
    objects,
    reserve: {
      repository,
      signer,
      nowMillis: () => NOW_MILLIS,
      reservationId: () => "reservation_12345678",
    },
    commit: {
      repository,
      objects,
      nowMillis: () => NOW_MILLIS + 1,
    },
  };
}

async function reservedFixture() {
  const fixture = dependencies();
  const response = await reservePrivateMediaUpload(OWNER, RESERVE_INPUT, fixture.reserve);
  const reservation = await fixture.repository.load(response.reservationId);
  assert.ok(reservation !== null);
  return { ...fixture, response, reservation };
}

function ownerObject(
  path: string,
  reservationId: string,
  overrides: Partial<Omit<PrivateMediaObject, "path" | "generation">> = {},
): Omit<PrivateMediaObject, "generation"> {
  return {
    path,
    byteSize: 3,
    contentType: "image/webp",
    customMetadata: { ownerUid: "user-a", reservationId },
    ...overrides,
  };
}

test("reserve binds owner and idempotency while signing one conditional immutable PUT", async () => {
  // Given
  const fixture = dependencies();

  // When
  const first = await reservePrivateMediaUpload(OWNER, RESERVE_INPUT, fixture.reserve);
  const replay = await reservePrivateMediaUpload(OWNER, RESERVE_INPUT, {
    ...fixture.reserve,
    reservationId: () => "reservation_must_not_replace",
  });

  // Then
  assert.equal(replay.reservationId, first.reservationId);
  assert.equal(first.upload.method, "PUT");
  assert.equal(first.upload.expiresAtMillis, NOW_MILLIS + 10 * 60 * 1_000);
  assert.deepEqual(first.upload.requiredHeaders, {
    "content-type": "image/webp",
    "x-goog-if-generation-match": "0",
    "x-goog-meta-owner-uid": "user-a",
    "x-goog-meta-reservation-id": "reservation_12345678",
  });
  assert.equal(fixture.signer.calls.length, 2);
  assert.equal(fixture.signer.calls[0]?.objectPath, "private-media-v2/reservation_12345678");
});

test("reserve replay preserves the original upload deadline and fails after expiry", async () => {
  // Given
  const fixture = dependencies();
  let nowMillis = NOW_MILLIS;
  const reserve = { ...fixture.reserve, nowMillis: () => nowMillis };
  const first = await reservePrivateMediaUpload(OWNER, RESERVE_INPUT, reserve);

  // When
  nowMillis += 5 * 60 * 1_000;
  const replay = await reservePrivateMediaUpload(OWNER, RESERVE_INPUT, reserve);

  // Then
  assert.equal(replay.upload.expiresAtMillis, first.upload.expiresAtMillis);
  assert.equal(fixture.signer.calls[1]?.expiresAtMillis, first.upload.expiresAtMillis);
  nowMillis = first.upload.expiresAtMillis;
  await assert.rejects(
    reservePrivateMediaUpload(OWNER, RESERVE_INPUT, reserve),
    (error: unknown) => error instanceof PrivateMediaError && error.code === "failed-precondition",
  );
  assert.equal(fixture.signer.calls.length, 2);
});

test("reserve rejects missing or foreign auth, malformed media, reused keys, and immutable accounts", async () => {
  // Given
  const fixture = dependencies();

  // When / Then
  await assert.rejects(
    reservePrivateMediaUpload(null, RESERVE_INPUT, fixture.reserve),
    (error: unknown) => error instanceof PrivateMediaError && error.code === "unauthenticated",
  );
  await assert.rejects(
    reservePrivateMediaUpload({ uid: "user-b" }, RESERVE_INPUT, fixture.reserve),
    (error: unknown) => error instanceof PrivateMediaError && error.code === "permission-denied",
  );
  for (const input of [
    { ...RESERVE_INPUT, contentType: "text/plain" },
    { ...RESERVE_INPUT, byteSize: 0 },
    { ...RESERVE_INPUT, byteSize: 20 * 1024 * 1024 + 1 },
  ]) {
    await assert.rejects(reservePrivateMediaUpload(OWNER, input, fixture.reserve), PrivateMediaError);
  }
  await reservePrivateMediaUpload(OWNER, RESERVE_INPUT, fixture.reserve);
  await assert.rejects(
    reservePrivateMediaUpload(OWNER, { ...RESERVE_INPUT, byteSize: 4 }, fixture.reserve),
    (error: unknown) => error instanceof PrivateMediaError && error.code === "invalid-argument",
  );
  fixture.repository.locked = true;
  await assert.rejects(
    reservePrivateMediaUpload(OWNER, { ...RESERVE_INPUT, idempotencyKey: "media-operation-0002" }, fixture.reserve),
    (error: unknown) => error instanceof PrivateMediaError && error.code === "failed-precondition",
  );
});

test("commit returns a typed reference only for the exact generation and object contract", async () => {
  // Given
  const fixture = await reservedFixture();
  const release = fixture.objects.admitUpload(ownerObject(
    fixture.reservation.objectPath,
    fixture.reservation.reservationId,
  ));
  assert.equal(release(), "created");

  // When
  const result = await commitPrivateMediaReservation(OWNER, {
    expectedOwnerUid: "user-a",
    reservationId: fixture.reservation.reservationId,
  }, fixture.commit);

  // Then
  assert.deepEqual(result.reference, {
    reservationId: fixture.reservation.reservationId,
    generation: "1",
  });
  assert.equal(result.mediaKind, "IDENTIFICATION_ORIGINAL");
  assert.equal(result.contentType, "image/webp");
  assert.equal(result.byteSize, 3);
  assert.deepEqual(await commitPrivateMediaReservation(OWNER, {
    expectedOwnerUid: "user-a",
    reservationId: fixture.reservation.reservationId,
  }, fixture.commit), result);
});

test("commit rejects foreign ownership, account lock, and every object contract mismatch", async () => {
  for (const object of [
    ownerObject("ignored", "ignored", { byteSize: 4 }),
    ownerObject("ignored", "ignored", { contentType: "image/png" }),
    ownerObject("ignored", "ignored", { customMetadata: { ownerUid: "user-b", reservationId: "ignored" } }),
    ownerObject("ignored", "ignored", { customMetadata: { ownerUid: "user-a", reservationId: "ignored", extra: "forged" } }),
  ]) {
    // Given
    const fixture = await reservedFixture();
    const upload = {
      ...object,
      path: fixture.reservation.objectPath,
      customMetadata: Object.fromEntries(Object.entries(object.customMetadata).map(([key, value]) => [
        key,
        value === "ignored" ? fixture.reservation.reservationId : value,
      ])),
    };
    assert.equal(fixture.objects.admitUpload(upload)(), "created");

    // When / Then
    await assert.rejects(
      commitPrivateMediaReservation(OWNER, {
        expectedOwnerUid: "user-a",
        reservationId: fixture.reservation.reservationId,
      }, fixture.commit),
      (error: unknown) => error instanceof PrivateMediaError && error.code === "failed-precondition",
    );
  }

  const foreign = await reservedFixture();
  await assert.rejects(
    commitPrivateMediaReservation({ uid: "user-b" }, {
      expectedOwnerUid: "user-a",
      reservationId: foreign.reservation.reservationId,
    }, foreign.commit),
    (error: unknown) => error instanceof PrivateMediaError && error.code === "permission-denied",
  );
  foreign.repository.locked = true;
  await assert.rejects(
    commitPrivateMediaReservation(OWNER, {
      expectedOwnerUid: "user-a",
      reservationId: foreign.reservation.reservationId,
    }, foreign.commit),
    (error: unknown) => error instanceof PrivateMediaError && error.code === "failed-precondition",
  );
});
