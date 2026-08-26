import assert from "node:assert/strict";
import test from "node:test";
import { FirestoreIdentificationCleanupPersistence } from "./firestore-identification-cleanup.js";
import {
  cleanupDependencies,
  DAY,
  deferred,
  disposeRaceFixture,
  NOW,
  OWNER_UID,
  raceFixture,
} from "./identification-cleanup-race-test-fixture.js";
import { runIdentificationCleanup } from "./identification-cleanup.js";
import { isPrivateMediaSeal } from "./private-media-contract.js";

test("finalization winning after an expired SENDING scan preserves the original and defers cleanup", async () => {
  const fixture = await raceFixture({
    appName: "identification-cleanup-finalization-race",
    reservationId: "cleanup_finalization_race_original",
    requestId: "cleanup_finalization_race_request",
  });
  const operationKey = "cleanup_finalization_race_operation";
  const claimAttempted = deferred();
  const releaseClaim = deferred();
  const persistence = new FirestoreIdentificationCleanupPersistence(
    fixture.firestore,
    {
      beforeRequestCleanupClaim: async () => {
        claimAttempted.resolve();
        await releaseClaim.promise;
      },
    },
  );

  try {
    const claim = await fixture.authorization.claim({
      ownerUid: OWNER_UID,
      requestId: fixture.config.requestId,
      operationKey,
      nowMillis: NOW - 2,
    });
    assert.equal(claim.kind, "start");
    if (claim.kind !== "start") return;
    await fixture.authorization.markSending({
      ownerUid: OWNER_UID,
      requestId: fixture.config.requestId,
      operationKey,
      claimGeneration: claim.request.claimGeneration,
      nowMillis: NOW - 1,
    });
    const activePersistence = new FirestoreIdentificationCleanupPersistence(
      fixture.firestore,
    );
    const activeCandidates = await activePersistence
      .scanExpiredNonterminalRequests(NOW, 1);
    const currentReservation = await fixture.reservations.load(
      fixture.config.reservationId,
    );
    assert.equal(activeCandidates.items.length, 1);
    const activeCandidate = activeCandidates.items[0];
    assert.ok(activeCandidate !== undefined);
    assert.ok(currentReservation !== null);
    assert.equal(
      await activePersistence.claimIdentificationRequest(
        activeCandidate,
        currentReservation,
        NOW,
      ),
      null,
    );

    const cleanup = runIdentificationCleanup(
      cleanupDependencies(fixture, persistence),
    );
    await claimAttempted.promise;
    const finalized = await fixture.authorization.finalize({
      ownerUid: OWNER_UID,
      requestId: fixture.config.requestId,
      operationKey,
      claimGeneration: claim.request.claimGeneration,
      status: "NO_CANDIDATES",
      result: { kind: "no_candidates" },
      nowMillis: NOW,
    });
    releaseClaim.resolve();
    const result = await cleanup;

    const [after, storedRequest, storedReservation] = await Promise.all([
      fixture.objects.inspect(fixture.path),
      fixture.request.get(),
      fixture.reservation.get(),
    ]);
    assert.equal(finalized.retentionExpiresAtMillis, NOW + DAY);
    assert.deepEqual(result.nonterminalDeadlines, {
      scanned: 1,
      cleaned: 0,
      deferred: 1,
    });
    assert.equal(storedRequest.get("retentionExpiresAt").toMillis(), NOW + DAY);
    assert.equal(storedReservation.get("state"), "COMMITTED");
    assert.equal(storedReservation.get("cleanupClaimGeneration"), null);
    assert.equal(after?.generation, fixture.original.generation);
    assert.equal(isPrivateMediaSeal(after ?? fixture.original), false);
  } finally {
    releaseClaim.resolve();
    await disposeRaceFixture(fixture);
  }
});

test("request cleanup claim winning rejects stale send and finalization before sealing a changed generation", async () => {
  const fixture = await raceFixture({
    appName: "identification-cleanup-claim-race",
    reservationId: "cleanup_claim_race_original",
    requestId: "cleanup_claim_race_request",
  });
  const operationKey = "cleanup_claim_race_operation";
  const claimPersisted = deferred();
  const releaseCleanup = deferred();
  const persistence = new FirestoreIdentificationCleanupPersistence(
    fixture.firestore,
    {
      afterRequestCleanupClaim: async () => {
        claimPersisted.resolve();
        await releaseCleanup.promise;
      },
    },
  );

  try {
    const claim = await fixture.authorization.claim({
      ownerUid: OWNER_UID,
      requestId: fixture.config.requestId,
      operationKey,
      nowMillis: NOW - 2,
    });
    assert.equal(claim.kind, "start");
    if (claim.kind !== "start") return;

    const cleanup = runIdentificationCleanup(
      cleanupDependencies(fixture, persistence),
    );
    await claimPersisted.promise;
    assert.equal(
      (await fixture.reservation.get()).get("cleanupClaimGeneration"),
      fixture.original.generation,
    );
    await assert.rejects(
      fixture.authorization.markSending({
        ownerUid: OWNER_UID,
        requestId: fixture.config.requestId,
        operationKey,
        claimGeneration: claim.request.claimGeneration,
        nowMillis: NOW - 1,
      }),
      /cleanup was already claimed/,
    );
    await assert.rejects(
      fixture.authorization.cancel({
        ownerUid: OWNER_UID,
        requestId: fixture.config.requestId,
        nowMillis: NOW,
      }),
      /cleanup was already claimed/,
    );
    await assert.rejects(
      fixture.authorization.finalize({
        ownerUid: OWNER_UID,
        requestId: fixture.config.requestId,
        operationKey,
        claimGeneration: claim.request.claimGeneration,
        status: "NO_CANDIDATES",
        result: { kind: "no_candidates" },
        nowMillis: NOW,
      }),
      /cleanup was already claimed/,
    );
    await fixture.file.save(Buffer.from([4, 5, 6]), {
      resumable: false,
      metadata: {
        contentType: "image/webp",
        metadata: {
          ownerUid: OWNER_UID,
          reservationId: fixture.config.reservationId,
        },
      },
    });
    const changed = await fixture.objects.inspect(fixture.path);
    assert.ok(changed !== null);
    assert.notEqual(changed.generation, fixture.original.generation);

    releaseCleanup.resolve();
    const result = await cleanup;

    const sealed = await fixture.objects.inspect(fixture.path);
    assert.deepEqual(result.nonterminalDeadlines, {
      scanned: 1,
      cleaned: 1,
      deferred: 0,
    });
    assert.equal(isPrivateMediaSeal(sealed ?? fixture.original), true);
    assert.equal((await fixture.request.get()).exists, false);
    assert.equal((await fixture.reservation.get()).exists, false);
  } finally {
    releaseCleanup.resolve();
    await disposeRaceFixture(fixture);
  }
});
