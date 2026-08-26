import assert from "node:assert/strict";
import test from "node:test";
import {
  handlePrivateMediaFinalized,
  sealPrivateMediaReservation,
} from "./private-media-seal.js";
import {
  reservePrivateMediaUpload,
  type MarkPrivateMediaSealedCommand,
  type PrivateMediaObject,
  type PrivateMediaSigner,
} from "./private-media.js";
import {
  FakePrivateMediaObjectStore,
  MemoryPrivateMediaRepository,
} from "./private-media-test-fixture.test.js";

const NOW_MILLIS = Date.parse("2026-08-24T00:00:00.000Z");
const signer: PrivateMediaSigner = {
  async signPut(command) {
    return { url: `https://upload.invalid/${command.reservationId}` };
  },
};

function eventBarrier() {
  let arrivedResolve: (() => void) | undefined;
  let releaseResolve: (() => void) | undefined;
  const arrived = new Promise<void>((resolve) => { arrivedResolve = resolve; });
  const released = new Promise<void>((resolve) => { releaseResolve = resolve; });
  return {
    arrive: () => arrivedResolve?.(),
    release: () => releaseResolve?.(),
    waitForArrival: () => arrived,
    waitForRelease: () => released,
  };
}

class PausedMarkRepository extends MemoryPrivateMediaRepository {
  constructor(private readonly markBarrier: ReturnType<typeof eventBarrier>) {
    super();
  }

  override async markSealed(command: MarkPrivateMediaSealedCommand): Promise<void> {
    this.markBarrier.arrive();
    await this.markBarrier.waitForRelease();
    await super.markSealed(command);
  }
}

async function fixture(repository: MemoryPrivateMediaRepository = new MemoryPrivateMediaRepository()) {
  const objects = new FakePrivateMediaObjectStore();
  const response = await reservePrivateMediaUpload(
    { uid: "user-a" },
    {
      expectedOwnerUid: "user-a",
      mediaKind: "PLANT_PHOTO",
      contentType: "image/webp",
      byteSize: 3,
      idempotencyKey: "media-finalize-operation-0001",
    },
    {
      repository,
      signer,
      nowMillis: () => NOW_MILLIS,
      reservationId: () => "reservation_finalize_12345678",
    },
  );
  const reservation = await repository.load(response.reservationId);
  assert.ok(reservation !== null);
  return { repository, objects, reservation };
}

test("finalize preserves the seal created before markSealed completes", async () => {
  // Given
  const mark = eventBarrier();
  const current = await fixture(new PausedMarkRepository(mark));
  const sealing = sealPrivateMediaReservation(current.reservation, {
    repository: current.repository,
    objects: current.objects,
    nowMillis: () => NOW_MILLIS,
  });
  await mark.waitForArrival();
  const created = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(created !== null);
  assert.equal(created.byteSize, 0);
  assert.equal(created.customMetadata.privateMediaSeal, "true");

  // When
  await handlePrivateMediaFinalized({
    object: created,
    repository: current.repository,
    objects: current.objects,
  });

  // Then
  assert.equal((await current.objects.inspect(created.path))?.generation, created.generation);
  mark.release();
  const sealed = await sealing;
  assert.equal(sealed.sealedGeneration, created.generation);
});

function uploadObject(path: string, reservationId: string): Omit<PrivateMediaObject, "generation"> {
  return {
    path,
    byteSize: 3,
    contentType: "image/webp",
    customMetadata: { ownerUid: "user-a", reservationId },
  };
}

test("finalize deletes every malformed RESERVED object", async () => {
  for (const overrides of [
    { byteSize: 4 },
    { contentType: "image/png" },
    { customMetadata: { ownerUid: "user-b", reservationId: "reservation_finalize_12345678" } },
    { customMetadata: { ownerUid: "user-a", reservationId: "reservation_finalize_12345678", extra: "forged" } },
  ]) {
    // Given
    const current = await fixture();
    assert.equal(current.objects.admitUpload({
      ...uploadObject(current.reservation.objectPath, current.reservation.reservationId),
      ...overrides,
    })(), "created");
    const finalized = await current.objects.inspect(current.reservation.objectPath);
    assert.ok(finalized !== null);

    // When
    await handlePrivateMediaFinalized({ object: finalized, repository: current.repository, objects: current.objects });

    // Then
    assert.equal(await current.objects.inspect(current.reservation.objectPath), null);
  }
});

test("finalize keeps the exact RESERVED object", async () => {
  // Given
  const current = await fixture();
  assert.equal(current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ))(), "created");
  const finalized = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(finalized !== null);

  // When
  await handlePrivateMediaFinalized({ object: finalized, repository: current.repository, objects: current.objects });

  // Then
  assert.equal((await current.objects.inspect(current.reservation.objectPath))?.generation, finalized.generation);
});

test("finalize deletes a stale canonical SEALED generation", async () => {
  // Given
  const current = await fixture();
  const created = await current.objects.createSeal(current.reservation.objectPath);
  assert.equal(created.kind, "created");
  const finalized = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(finalized !== null);
  current.repository.records.set(current.reservation.reservationId, {
    ...current.reservation,
    state: "SEALED",
    sealedGeneration: "99",
    sealedAtMillis: NOW_MILLIS + 1,
  });

  // When
  await handlePrivateMediaFinalized({ object: finalized, repository: current.repository, objects: current.objects });

  // Then
  assert.equal(await current.objects.inspect(current.reservation.objectPath), null);
});

test("finalize deletes a COMMITTED generation mismatch", async () => {
  // Given
  const current = await fixture();
  assert.equal(current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ))(), "created");
  const finalized = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(finalized !== null);
  current.repository.records.set(current.reservation.reservationId, {
    ...current.reservation,
    state: "COMMITTED",
    objectGeneration: "99",
    committedAtMillis: NOW_MILLIS + 1,
  });

  // When
  await handlePrivateMediaFinalized({ object: finalized, repository: current.repository, objects: current.objects });

  // Then
  assert.equal(await current.objects.inspect(current.reservation.objectPath), null);
});

test("finalize keeps the exact COMMITTED object", async () => {
  // Given
  const current = await fixture();
  assert.equal(current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ))(), "created");
  const finalized = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(finalized !== null);
  current.repository.records.set(current.reservation.reservationId, {
    ...current.reservation,
    state: "COMMITTED",
    objectGeneration: finalized.generation,
    committedAtMillis: NOW_MILLIS + 1,
  });

  // When
  await handlePrivateMediaFinalized({ object: finalized, repository: current.repository, objects: current.objects });

  // Then
  assert.equal((await current.objects.inspect(current.reservation.objectPath))?.generation, finalized.generation);
});

test("finalize deletes unowned media after reservation metadata purge", async () => {
  // Given
  const repository = new MemoryPrivateMediaRepository();
  const objects = new FakePrivateMediaObjectStore();
  const path = "private-media-v2/purged_reservation_12345678";
  assert.equal(objects.admitUpload(uploadObject(path, "purged_reservation_12345678"))(), "created");
  const finalized = await objects.inspect(path);
  assert.ok(finalized !== null);

  // When
  await handlePrivateMediaFinalized({ object: finalized, repository, objects });

  // Then
  assert.equal(await objects.inspect(path), null);
});

test("finalize retains the ownerless seal after reservation metadata purge", async () => {
  // Given
  const repository = new MemoryPrivateMediaRepository();
  const objects = new FakePrivateMediaObjectStore();
  const path = "private-media-v2/purged_reservation_12345678";
  const created = await objects.createSeal(path);
  assert.equal(created.kind, "created");
  const finalized = await objects.inspect(path);
  assert.ok(finalized !== null);

  // When
  await handlePrivateMediaFinalized({ object: finalized, repository, objects });

  // Then
  assert.equal((await objects.inspect(path))?.generation, finalized.generation);
});

test("stale finalize deletion never deletes a newer generation", async () => {
  // Given
  const current = await fixture();
  assert.equal(current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ))(), "created");
  const stale = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(stale !== null);
  assert.equal(await current.objects.deleteGeneration(stale.path, stale.generation), "deleted");
  assert.equal(current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ))(), "created");
  const currentGeneration = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(currentGeneration !== null);
  current.repository.locked = true;

  // When
  await handlePrivateMediaFinalized({ object: stale, repository: current.repository, objects: current.objects });

  // Then
  assert.equal(
    (await current.objects.inspect(currentGeneration.path))?.generation,
    currentGeneration.generation,
  );
});

test("deletion lock removes the exact finalized generation", async () => {
  // Given
  const current = await fixture();
  assert.equal(current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ))(), "created");
  const finalized = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(finalized !== null);
  current.repository.locked = true;

  // When
  await handlePrivateMediaFinalized({ object: finalized, repository: current.repository, objects: current.objects });

  // Then
  assert.equal(await current.objects.inspect(current.reservation.objectPath), null);
});
