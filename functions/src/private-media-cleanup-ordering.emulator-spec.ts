import assert from "node:assert/strict";
import test from "node:test";
import { FirestoreIdentificationCleanupPersistence } from "./firestore-identification-cleanup.js";
import { FirestorePrivateMediaReservationRepository } from "./firestore-private-media.js";
import { runIdentificationCleanup } from "./identification-cleanup.js";
import {
  barrier,
  CLEANUP_RACE_NOW,
  CLEANUP_RACE_OWNER,
  cleanupDependencies,
  disposeExpiredReservedFixture,
  expiredReservedFixture,
} from "./private-media-cleanup-race-test-fixture.js";
import {
  commitPrivateMediaReservation,
  isPrivateMediaSeal,
  type PrivateMediaObjectStore,
} from "./private-media.js";

test("commit transaction winning before cleanup claim preserves its generation and defers cleanup", async () => {
  // Given
  const fixture = await expiredReservedFixture(
    "private-media-cleanup-race-commit-first",
    "cleanup_race_commit_first",
  );
  const claim = barrier();
  const cleanupRepository = new FirestorePrivateMediaReservationRepository(
    fixture.firestore,
    {
      beforeExpiredReservedClaim: async () => {
        claim.arrive();
        await claim.waitForRelease();
      },
    },
  );

  try {
    // When
    const cleanup = runIdentificationCleanup(
      cleanupDependencies(fixture, cleanupRepository),
    );
    await claim.waitForArrival();
    const committed = await commitPrivateMediaReservation(
      { uid: CLEANUP_RACE_OWNER },
      {
        expectedOwnerUid: CLEANUP_RACE_OWNER,
        reservationId: "cleanup_race_commit_first",
      },
      {
        repository: fixture.repository,
        objects: fixture.objects,
        nowMillis: () => CLEANUP_RACE_NOW,
      },
    );
    claim.release();
    const result = await cleanup;

    // Then
    assert.equal(committed.reference.generation, fixture.original.generation);
    assert.deepEqual(result.reservedUploads, {
      scanned: 1,
      cleaned: 0,
      deferred: 1,
    });
    assert.equal(
      (await fixture.objects.inspect(fixture.path))?.generation,
      fixture.original.generation,
    );
    assert.equal((await fixture.repository.load("cleanup_race_commit_first"))?.state, "COMMITTED");
  } finally {
    claim.release();
    await disposeExpiredReservedFixture(fixture);
  }
});

test("a stale RESERVED scan cannot claim, seal, or purge after commit", async () => {
  // Given
  const fixture = await expiredReservedFixture(
    "private-media-cleanup-race-stale-scan",
    "cleanup_race_stale_scan",
  );
  const persistence = new FirestoreIdentificationCleanupPersistence(fixture.firestore);

  try {
    const batch = await persistence.scanExpiredReservedUploads(CLEANUP_RACE_NOW, 1);
    const stale = batch.items[0];
    assert.ok(stale !== undefined);

    // When
    const committed = await commitPrivateMediaReservation(
      { uid: CLEANUP_RACE_OWNER },
      {
        expectedOwnerUid: CLEANUP_RACE_OWNER,
        reservationId: "cleanup_race_stale_scan",
      },
      {
        repository: fixture.repository,
        objects: fixture.objects,
        nowMillis: () => CLEANUP_RACE_NOW,
      },
    );
    const claimed = await fixture.repository.claimExpiredReservedUpload({
      reservation: stale,
      nowMillis: CLEANUP_RACE_NOW,
    });

    // Then
    assert.equal(claimed, null);
    await assert.rejects(
      fixture.repository.markSealed({
        expectedReservation: stale,
        sealedGeneration: fixture.original.generation,
        sealedAtMillis: CLEANUP_RACE_NOW,
      }),
      /changed before seal metadata was persisted/,
    );
    await assert.rejects(
      persistence.purgeReservedUpload(
        stale,
        fixture.original.generation,
        CLEANUP_RACE_NOW,
      ),
      /metadata no longer matches/,
    );
    assert.equal(committed.reference.generation, fixture.original.generation);
    assert.equal(
      (await fixture.objects.inspect(fixture.path))?.generation,
      fixture.original.generation,
    );
    assert.equal((await fixture.repository.load("cleanup_race_stale_scan"))?.state, "COMMITTED");
  } finally {
    await disposeExpiredReservedFixture(fixture);
  }
});

test("a newer object generation survives the stale delete and blocks seal metadata", async () => {
  // Given
  const fixture = await expiredReservedFixture(
    "private-media-cleanup-race-generation-change",
    "cleanup_race_generation_change",
  );
  let replacementWritten = false;
  const changingObjects: PrivateMediaObjectStore = {
    inspect: (path) => fixture.objects.inspect(path),
    async deleteGeneration(path, generation) {
      await fixture.file.save(Buffer.from([4, 5, 6]), {
        resumable: false,
        metadata: {
          contentType: "image/webp",
          metadata: {
            ownerUid: CLEANUP_RACE_OWNER,
            reservationId: "cleanup_race_generation_change",
          },
        },
      });
      replacementWritten = true;
      assert.equal(generation, fixture.original.generation);
      return "generation_changed";
    },
    createSeal: (path) => fixture.objects.createSeal(path),
  };

  try {
    // When
    const result = await runIdentificationCleanup(
      cleanupDependencies(fixture, fixture.repository, changingObjects),
    );

    // Then
    const object = await fixture.objects.inspect(fixture.path);
    assert.equal(replacementWritten, true);
    assert.notEqual(object?.generation, fixture.original.generation);
    assert.equal(isPrivateMediaSeal(object ?? fixture.original), false);
    assert.deepEqual(result.reservedUploads, {
      scanned: 1,
      cleaned: 0,
      deferred: 0,
    });
    assert.deepEqual(
      result.failures.map(({ category, stage, message }) => ({ category, stage, message })),
      [{
        category: "RESERVED_UPLOAD",
        stage: "SEAL",
        message: "Private media generation changed while sealing",
      }],
    );
    const stored = await fixture.repository.load("cleanup_race_generation_change");
    assert.equal(stored?.state, "RESERVED");
    assert.equal(stored?.cleanupClaimReason, "EXPIRED_RESERVED_UPLOAD");
  } finally {
    await disposeExpiredReservedFixture(fixture);
  }
});
