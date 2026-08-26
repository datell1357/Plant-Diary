import assert from "node:assert/strict";
import test from "node:test";
import type { Firestore } from "firebase-admin/firestore";
import { FirestorePrivateMediaReservationRepository } from "./firestore-private-media.js";
import { runIdentificationCleanup } from "./identification-cleanup.js";
import {
  barrier,
  CLEANUP_RACE_NOW,
  CLEANUP_RACE_OWNER,
  cleanupDependencies,
  disposeExpiredReservedFixture,
  expiredReservedFixture,
  type Barrier,
} from "./private-media-cleanup-race-test-fixture.js";
import {
  commitPrivateMediaReservation,
  type MarkPrivateMediaSealedCommand,
  type PrivateMediaObjectStore,
} from "./private-media.js";

class PausedMarkRepository extends FirestorePrivateMediaReservationRepository {
  constructor(firestore: Firestore, private readonly markBarrier: Barrier) {
    super(firestore);
  }

  override async markSealed(command: MarkPrivateMediaSealedCommand): Promise<void> {
    this.markBarrier.arrive();
    await this.markBarrier.waitForRelease();
    await super.markSealed(command);
  }
}

test("cleanup claiming after commit inspection cannot let commit return a deleted generation", async () => {
  // Given
  const fixture = await expiredReservedFixture(
    "private-media-cleanup-race-commit-inspected",
    "cleanup_race_commit_inspected",
  );
  const commitInspection = barrier();
  const mark = barrier();
  let inspected = false;
  const commitObjects: PrivateMediaObjectStore = {
    async inspect(path) {
      const object = await fixture.objects.inspect(path);
      if (!inspected) {
        inspected = true;
        commitInspection.arrive();
        await commitInspection.waitForRelease();
      }
      return object;
    },
    deleteGeneration: (path, generation) =>
      fixture.objects.deleteGeneration(path, generation),
    createSeal: (path) => fixture.objects.createSeal(path),
  };

  try {
    // When
    const commit = commitPrivateMediaReservation({ uid: CLEANUP_RACE_OWNER }, {
      expectedOwnerUid: CLEANUP_RACE_OWNER,
      reservationId: "cleanup_race_commit_inspected",
    }, {
      repository: fixture.repository,
      objects: commitObjects,
      nowMillis: () => CLEANUP_RACE_NOW,
    });
    await commitInspection.waitForArrival();
    const cleanupRepository = new PausedMarkRepository(fixture.firestore, mark);
    const cleanup = runIdentificationCleanup(
      cleanupDependencies(fixture, cleanupRepository),
    );
    await mark.waitForArrival();
    commitInspection.release();

    // Then
    await assert.rejects(commit, /cleanup was already claimed/);
    mark.release();
    const result = await cleanup;
    assert.deepEqual(result.reservedUploads, {
      scanned: 1,
      cleaned: 1,
      deferred: 0,
    });
    assert.equal(await fixture.repository.resolve({
      ownerUid: CLEANUP_RACE_OWNER,
      reference: {
        reservationId: "cleanup_race_commit_inspected",
        generation: fixture.original.generation,
      },
      mediaKind: "IDENTIFICATION_ORIGINAL",
    }), null);
  } finally {
    commitInspection.release();
    mark.release();
    await disposeExpiredReservedFixture(fixture);
  }
});
