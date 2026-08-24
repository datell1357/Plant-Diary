import assert from "node:assert/strict";
import test from "node:test";
import {
  handlePrivateMediaFinalized,
  sealOwnerPrivateMedia,
} from "./private-media-seal.js";
import {
  reservePrivateMediaUpload,
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

async function fixture() {
  const repository = new MemoryPrivateMediaRepository();
  const objects = new FakePrivateMediaObjectStore();
  const response = await reservePrivateMediaUpload(
    { uid: "user-a" },
    {
      expectedOwnerUid: "user-a",
      mediaKind: "PLANT_PHOTO",
      contentType: "image/webp",
      byteSize: 3,
      idempotencyKey: "media-seal-operation-0001",
    },
    {
      repository,
      signer,
      nowMillis: () => NOW_MILLIS,
      reservationId: () => "reservation_seal_12345678",
    },
  );
  const reservation = await repository.load(response.reservationId);
  assert.ok(reservation !== null);
  return { repository, objects, reservation };
}

function uploadObject(path: string, reservationId: string): Omit<PrivateMediaObject, "generation"> {
  return {
    path,
    byteSize: 3,
    contentType: "image/webp",
    customMetadata: { ownerUid: "user-a", reservationId },
  };
}

test("admitted upload paused before object creation loses after deletion seals the path", async () => {
  // Given
  const current = await fixture();
  const releaseUpload = current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ));
  current.repository.locked = true;

  // When
  await sealOwnerPrivateMedia({
    ownerUid: "user-a",
    repository: current.repository,
    objects: current.objects,
    nowMillis: () => NOW_MILLIS + 1,
  });
  const uploadResult = releaseUpload();

  // Then
  assert.equal(uploadResult, "precondition_failed");
  const object = await current.objects.inspect(current.reservation.objectPath);
  assert.deepEqual(object?.customMetadata, { privateMediaSeal: "true" });
  assert.equal(object?.byteSize, 0);
  assert.equal("ownerUid" in (object?.customMetadata ?? {}), false);
  assert.equal((await current.repository.load(current.reservation.reservationId))?.state, "SEALED");
});

test("upload winner is generation-deleted before an ownerless seal permits completion", async () => {
  // Given
  const current = await fixture();
  const releaseUpload = current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ));
  assert.equal(releaseUpload(), "created");
  current.repository.locked = true;

  // When
  await sealOwnerPrivateMedia({
    ownerUid: "user-a",
    repository: current.repository,
    objects: current.objects,
    nowMillis: () => NOW_MILLIS + 1,
  });

  // Then
  const object = await current.objects.inspect(current.reservation.objectPath);
  assert.deepEqual(object?.customMetadata, { privateMediaSeal: "true" });
  assert.equal((await current.repository.load(current.reservation.reservationId))?.state, "SEALED");
});

test("precondition conflict and crash reclaim converge without accepting a stale finish", async () => {
  // Given
  const current = await fixture();
  const releaseWinner = current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ));
  current.objects.beforeNextSealCreate = () => {
    assert.equal(releaseWinner(), "created");
  };
  current.repository.locked = true;
  current.repository.failNextSeal = true;

  // When / Then
  await assert.rejects(sealOwnerPrivateMedia({
    ownerUid: "user-a",
    repository: current.repository,
    objects: current.objects,
    nowMillis: () => NOW_MILLIS + 1,
  }));
  assert.equal((await current.repository.load(current.reservation.reservationId))?.state, "RESERVED");
  await sealOwnerPrivateMedia({
    ownerUid: "user-a",
    repository: current.repository,
    objects: current.objects,
    nowMillis: () => NOW_MILLIS + 2,
  });
  const sealed = await current.repository.load(current.reservation.reservationId);
  const object = await current.objects.inspect(current.reservation.objectPath);
  assert.equal(sealed?.state, "SEALED");
  assert.equal(sealed?.sealedGeneration, object?.generation);
});

test("sealing fails closed while any owner reservation cannot be verified sealed", async () => {
  // Given
  const current = await fixture();
  current.repository.locked = true;
  current.repository.failNextSeal = true;

  // When / Then
  await assert.rejects(sealOwnerPrivateMedia({
    ownerUid: "user-a",
    repository: current.repository,
    objects: current.objects,
    nowMillis: () => NOW_MILLIS + 1,
    maximumAttempts: 1,
  }));
  assert.notEqual((await current.repository.load(current.reservation.reservationId))?.state, "SEALED");
});

test("duplicate and out-of-order finalize events delete only the exact finalized generation", async () => {
  // Given
  const current = await fixture();
  const releaseOld = current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ));
  assert.equal(releaseOld(), "created");
  const old = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(old !== null);
  assert.equal(await current.objects.deleteGeneration(old.path, old.generation), "deleted");
  const releaseCurrent = current.objects.admitUpload(uploadObject(
    current.reservation.objectPath,
    current.reservation.reservationId,
  ));
  assert.equal(releaseCurrent(), "created");
  const newest = await current.objects.inspect(current.reservation.objectPath);
  assert.ok(newest !== null);
  current.repository.locked = true;

  // When / Then
  await handlePrivateMediaFinalized({ object: old, repository: current.repository, objects: current.objects });
  assert.equal((await current.objects.inspect(newest.path))?.generation, newest.generation);
  await handlePrivateMediaFinalized({ object: newest, repository: current.repository, objects: current.objects });
  await handlePrivateMediaFinalized({ object: newest, repository: current.repository, objects: current.objects });
  assert.equal(await current.objects.inspect(newest.path), null);
});
