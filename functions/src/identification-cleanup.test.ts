import assert from "node:assert/strict";
import test from "node:test";
import {
  IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
  type AuthorizedIdentificationRequest,
} from "./identification-authorization-contract.js";
import {
  runIdentificationCleanup,
  type IdentificationCleanupPersistence,
  type IdentificationCleanupRequestCandidate,
  type LegacyIdentificationOriginal,
  type LegacyIdentificationOriginalStore,
} from "./identification-cleanup.js";
import {
  isPrivateMediaSeal,
  type PrivateMediaReservation,
} from "./private-media-contract.js";
import {
  FakePrivateMediaObjectStore,
  MemoryPrivateMediaRepository,
} from "./private-media-test-fixture.test.js";

const NOW = Date.parse("2026-08-24T12:00:00.000Z");

function reservation(
  id: string,
  state: "RESERVED" | "COMMITTED",
  mediaKind:
    | "IDENTIFICATION_ORIGINAL"
    | "PLANT_PHOTO" = "IDENTIFICATION_ORIGINAL",
): PrivateMediaReservation {
  return {
    schemaVersion: 1,
    reservationId: id,
    ownerUid: "user-a",
    mediaKind,
    contentType: "image/webp",
    byteSize: 3,
    objectPath: `private-media-v2/${id}`,
    identificationRequestId:
      mediaKind === "IDENTIFICATION_ORIGINAL" && state === "COMMITTED"
        ? `request_${id}`
        : null,
    idempotencyKeyHash: id.padEnd(64, "a").slice(0, 64),
    requestHash: id.padEnd(64, "b").slice(0, 64),
    state,
    objectGeneration: state === "COMMITTED" ? "1" : null,
    sealedGeneration: null,
    createdAtMillis: NOW - IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
    expiresAtMillis: NOW - 1,
    committedAtMillis: state === "COMMITTED" ? NOW - 2 : null,
    sealedAtMillis: null,
  };
}

function request(
  current: PrivateMediaReservation,
  terminal: boolean,
): IdentificationCleanupRequestCandidate {
  const requestId = current.identificationRequestId;
  assert.ok(requestId !== null);
  const value: AuthorizedIdentificationRequest = {
    schemaVersion: 1,
    requestId,
    ownerUid: current.ownerUid,
    mediaReference: {
      reservationId: current.reservationId,
      generation: current.objectGeneration ?? "1",
    },
    disclosureVersion: 1,
    status: terminal ? "FAILED" : "PENDING",
    claimGeneration: 1,
    claimOperationKey: null,
    claimExpiresAtMillis: null,
    sendState: terminal ? "SENT" : "NOT_SENT",
    acknowledgedAtMillis: NOW - IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
    createdAtMillis: NOW - IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
    hardExpiresAtMillis: NOW,
    terminalAtMillis: terminal
      ? NOW - IDENTIFICATION_ORIGINAL_RETENTION_MILLIS
      : null,
    retentionExpiresAtMillis: terminal ? NOW : null,
  };
  return {
    documentPath: `users/${value.ownerUid}/identificationRequests/${value.requestId}`,
    request: value,
  };
}

class MemoryCleanupPersistence implements IdentificationCleanupPersistence {
  readonly requests = new Map<string, IdentificationCleanupRequestCandidate>();
  readonly purgeLog: string[] = [];
  failBeforePurge = new Set<string>();
  failAfterPurge = new Set<string>();

  constructor(
    private readonly reservations: MemoryPrivateMediaRepository,
    private readonly objects: FakePrivateMediaObjectStore,
  ) {}

  async scanExpiredReservedUploads(nowMillis: number, limit: number) {
    return {
      items: [...this.reservations.records.values()]
        .filter(
          (item) =>
            item.mediaKind === "IDENTIFICATION_ORIGINAL" &&
            (item.state === "RESERVED" ||
              (item.state === "SEALED" &&
                item.cleanupClaimReason === "EXPIRED_RESERVED_UPLOAD")) &&
            item.expiresAtMillis <= nowMillis,
        )
        .sort((left, right) =>
          left.reservationId.localeCompare(right.reservationId),
        )
        .slice(0, limit),
      failures: [],
    };
  }

  async scanExpiredCommittedOrphanedOriginals(nowMillis: number, limit: number) {
    return {
      items: [...this.reservations.records.values()]
        .filter(
          (item) =>
            item.mediaKind === "IDENTIFICATION_ORIGINAL" &&
            (item.state === "COMMITTED" || item.state === "SEALED") &&
            (item.committedAtMillis ?? item.createdAtMillis) +
              IDENTIFICATION_ORIGINAL_RETENTION_MILLIS <= nowMillis,
        )
        .sort((left, right) => left.reservationId.localeCompare(right.reservationId))
        .slice(0, limit),
      failures: [],
    };
  }

  async claimCommittedOrphanedOriginal(
    current: PrivateMediaReservation,
    nowMillis: number,
  ): Promise<PrivateMediaReservation | null> {
    const persisted = this.reservations.records.get(current.reservationId);
    if (
      persisted === undefined ||
      (persisted.committedAtMillis ?? persisted.createdAtMillis) +
        IDENTIFICATION_ORIGINAL_RETENTION_MILLIS > nowMillis
    ) return null;
    if (
      persisted.state === "SEALED" &&
      persisted.cleanupClaimReason === "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL" &&
      persisted.cleanupClaimGeneration !== null
    ) return persisted;
    if (
      persisted.state !== "COMMITTED" ||
      persisted.objectGeneration !== current.objectGeneration ||
      (persisted.identificationRequestId !== null && this.requests.has(persisted.identificationRequestId))
    ) return null;
    const claimed = {
      ...persisted,
      state: "SEALED" as const,
      objectGeneration: null,
      sealedGeneration: null,
      sealedAtMillis: nowMillis,
      cleanupClaimGeneration: persisted.objectGeneration,
      cleanupClaimReason: "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL" as const,
    };
    this.reservations.records.set(claimed.reservationId, claimed);
    return claimed;
  }

  async scanExpiredNonterminalRequests(_nowMillis: number, limit: number) {
    return {
      items: [...this.requests.values()]
        .filter((item) => item.request.retentionExpiresAtMillis === null)
        .sort(byRequestId)
        .slice(0, limit),
      failures: [],
    };
  }

  async scanExpiredTerminalRequests(_nowMillis: number, limit: number) {
    return {
      items: [...this.requests.values()]
        .filter((item) => item.request.retentionExpiresAtMillis !== null)
        .sort(byRequestId)
        .slice(0, limit),
      failures: [],
    };
  }

  async claimIdentificationRequest(
    candidate: IdentificationCleanupRequestCandidate,
    current: PrivateMediaReservation,
    nowMillis: number,
  ): Promise<PrivateMediaReservation | null> {
    const requestCandidate = this.requests.get(candidate.request.requestId);
    const persisted = this.reservations.records.get(current.reservationId);
    if (
      requestCandidate === undefined
      || persisted === undefined
      || requestCandidate.request.mediaReference.generation !==
        candidate.request.mediaReference.generation
      || (requestCandidate.request.retentionExpiresAtMillis
        ?? requestCandidate.request.hardExpiresAtMillis) > nowMillis
      || persisted.identificationRequestId !== candidate.request.requestId
    ) return null;
    if (
      persisted.cleanupClaimReason === "IDENTIFICATION_REQUEST_RETENTION"
      && persisted.cleanupClaimGeneration === candidate.request.mediaReference.generation
    ) return persisted;
    if (persisted.cleanupClaimReason !== null && persisted.cleanupClaimReason !== undefined) {
      return null;
    }
    if (
      requestCandidate.request.sendState === "SENDING"
      && (
        requestCandidate.request.claimExpiresAtMillis === null
        || nowMillis < requestCandidate.request.claimExpiresAtMillis
      )
    ) return null;
    const generationMatches = persisted.state === "COMMITTED"
      && persisted.objectGeneration === candidate.request.mediaReference.generation;
    const alreadySealed = persisted.state === "SEALED"
      && persisted.objectGeneration === null
      && persisted.sealedGeneration !== null;
    if (!generationMatches && !alreadySealed) return null;
    const claimed = {
      ...persisted,
      cleanupClaimGeneration: candidate.request.mediaReference.generation,
      cleanupClaimReason: "IDENTIFICATION_REQUEST_RETENTION" as const,
    };
    this.reservations.records.set(claimed.reservationId, claimed);
    return claimed;
  }

  async purgeReservedUpload(
    current: PrivateMediaReservation,
    sealedGeneration: string,
  ): Promise<"purged"> {
    await this.assertSealThenMaybeFail(current, sealedGeneration);
    this.reservations.records.delete(current.reservationId);
    this.purgeLog.push(current.reservationId);
    this.maybeLoseResponse(current.reservationId);
    return "purged";
  }

  async purgeCommittedOrphanedOriginal(
    current: PrivateMediaReservation,
    sealedGeneration: string,
    nowMillis: number,
  ): Promise<"purged" | "deferred"> {
    const persisted = this.reservations.records.get(current.reservationId);
    if (
      persisted === undefined ||
      persisted.state !== "SEALED" ||
      persisted.cleanupClaimReason !== "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL" ||
      persisted.cleanupClaimGeneration !== sealedGeneration ||
      (persisted.committedAtMillis ?? persisted.createdAtMillis) +
        IDENTIFICATION_ORIGINAL_RETENTION_MILLIS > nowMillis ||
      (persisted.identificationRequestId !== null &&
        this.requests.has(persisted.identificationRequestId))
    ) return "deferred";
    await this.assertSealThenMaybeFail(current, sealedGeneration);
    this.reservations.records.delete(current.reservationId);
    this.purgeLog.push(current.reservationId);
    this.maybeLoseResponse(current.reservationId);
    return "purged";
  }

  async purgeIdentificationRequest(
    candidate: IdentificationCleanupRequestCandidate,
    current: PrivateMediaReservation,
    sealedGeneration: string,
  ): Promise<"purged"> {
    const persisted = this.reservations.records.get(current.reservationId);
    assert.equal(persisted?.cleanupClaimReason, "IDENTIFICATION_REQUEST_RETENTION");
    assert.equal(
      persisted?.cleanupClaimGeneration,
      candidate.request.mediaReference.generation,
    );
    await this.assertSealThenMaybeFail(current, sealedGeneration);
    this.requests.delete(candidate.request.requestId);
    this.reservations.records.delete(current.reservationId);
    this.purgeLog.push(current.reservationId);
    this.maybeLoseResponse(current.reservationId);
    return "purged";
  }

  private async assertSealThenMaybeFail(
    current: PrivateMediaReservation,
    sealedGeneration: string,
  ): Promise<void> {
    const persisted = this.reservations.records.get(current.reservationId);
    const object = await this.objects.inspect(current.objectPath);
    assert.equal(persisted?.state, "SEALED");
    if (persisted?.cleanupClaimReason === "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL") {
      assert.equal(persisted.cleanupClaimGeneration, sealedGeneration);
      assert.equal(persisted.sealedGeneration, object?.generation);
      assert.equal(object !== null && isPrivateMediaSeal(object), true);
    } else {
      assert.equal(persisted?.sealedGeneration, sealedGeneration);
      assert.equal(object?.generation, sealedGeneration);
      assert.equal(object !== null && isPrivateMediaSeal(object), true);
    }
    if (this.failBeforePurge.delete(current.reservationId)) {
      throw new Error("injected crash after seal before metadata purge");
    }
  }

  private maybeLoseResponse(reservationId: string): void {
    if (this.failAfterPurge.delete(reservationId)) {
      throw new Error("injected response loss after metadata purge");
    }
  }
}

class MemoryLegacyStore implements LegacyIdentificationOriginalStore {
  readonly originals = new Map<string, string>();
  fail = new Set<string>();

  async listLegacyIdentificationOriginals(limit: number) {
    return {
      items: [...this.originals]
        .sort(([left], [right]) => left.localeCompare(right))
        .slice(0, limit)
        .map(([path, generation]) => ({ path, generation })),
      failures: [],
    };
  }

  async deleteLegacyIdentificationOriginal(
    original: LegacyIdentificationOriginal,
  ) {
    if (this.fail.delete(original.path))
      throw new Error("injected legacy delete failure");
    const generation = this.originals.get(original.path);
    if (generation === undefined) return "absent" as const;
    if (generation !== original.generation)
      return "generation_changed" as const;
    this.originals.delete(original.path);
    return "deleted" as const;
  }
}

function byRequestId(
  left: IdentificationCleanupRequestCandidate,
  right: IdentificationCleanupRequestCandidate,
): number {
  return left.request.requestId.localeCompare(right.request.requestId);
}

function dependencies() {
  const reservations = new MemoryPrivateMediaRepository();
  const objects = new FakePrivateMediaObjectStore();
  const persistence = new MemoryCleanupPersistence(reservations, objects);
  const legacyObjects = new MemoryLegacyStore();
  return {
    reservations,
    objects,
    persistence,
    legacyObjects,
    nowMillis: () => NOW,
  };
}

async function upload(
  objects: FakePrivateMediaObjectStore,
  current: PrivateMediaReservation,
): Promise<PrivateMediaReservation> {
  const outcome = objects.admitUpload({
    path: current.objectPath,
    byteSize: current.byteSize,
    contentType: current.contentType,
    customMetadata: {
      ownerUid: current.ownerUid,
      reservationId: current.reservationId,
    },
  })();
  assert.equal(outcome, "created");
  const object = await objects.inspect(current.objectPath);
  assert.ok(object !== null);
  return { ...current, objectGeneration: object.generation };
}

test("five bounded scans seal originals, purge metadata only after verification, and preserve PLANT_PHOTO", async () => {
  const current = dependencies();
  const expiredReserved = reservation("reserved_original_0001", "RESERVED");
  const expiredNonterminal = await upload(
    current.objects,
    reservation("nonterminal_original_0001", "COMMITTED"),
  );
  const expiredTerminal = reservation("terminal_original_0001", "COMMITTED");
  const representative = await upload(
    current.objects,
    reservation("representative_photo_0001", "COMMITTED", "PLANT_PHOTO"),
  );
  for (const item of [
    expiredReserved,
    expiredNonterminal,
    expiredTerminal,
    representative,
  ]) {
    current.reservations.records.set(item.reservationId, item);
  }
  current.persistence.requests.set(
    expiredNonterminal.identificationRequestId!,
    request(expiredNonterminal, false),
  );
  current.persistence.requests.set(
    expiredTerminal.identificationRequestId!,
    request(expiredTerminal, true),
  );
  current.legacyObjects.originals.set(
    "identification-originals/user-a/request-a/original.webp",
    "7",
  );

  const result = await runIdentificationCleanup(current);

  assert.equal(result.failures.length, 0);
  assert.deepEqual(result.reservedUploads, {
    scanned: 1,
    cleaned: 1,
    deferred: 0,
  });
  assert.deepEqual(result.committedOrphanedOriginals, {
    scanned: 0,
    cleaned: 0,
    deferred: 0,
  });
  assert.deepEqual(result.nonterminalDeadlines, {
    scanned: 1,
    cleaned: 1,
    deferred: 0,
  });
  assert.deepEqual(result.terminalDeadlines, {
    scanned: 1,
    cleaned: 1,
    deferred: 0,
  });
  assert.deepEqual(result.legacyOriginals, {
    scanned: 1,
    cleaned: 1,
    deferred: 0,
  });
  assert.deepEqual(
    current.persistence.purgeLog.sort(),
    [
      expiredNonterminal.reservationId,
      expiredReserved.reservationId,
      expiredTerminal.reservationId,
    ].sort(),
  );
  assert.equal(
    current.reservations.records.has(representative.reservationId),
    true,
  );
  assert.equal(
    (await current.objects.inspect(representative.objectPath))?.generation,
    representative.objectGeneration,
  );
});

test("expired reserved cleanup retries after sealing before metadata purge", async () => {
  // Given
  const current = dependencies();
  const expired = reservation("reserved_retry_original_0001", "RESERVED");
  current.reservations.records.set(expired.reservationId, expired);
  current.persistence.failBeforePurge.add(expired.reservationId);

  // When
  const failed = await runIdentificationCleanup(current);
  const retried = await runIdentificationCleanup(current);

  // Then
  assert.equal(failed.failures.length, 1);
  assert.equal(retried.failures.length, 0);
  assert.deepEqual(retried.reservedUploads, {
    scanned: 1,
    cleaned: 1,
    deferred: 0,
  });
  assert.equal(current.reservations.records.has(expired.reservationId), false);
});

test("committed unlinked and legacy-missing originals expire exactly at 24h while young valid and plant media remain", async () => {
  const current = dependencies();
  const unlinked = await upload(
    current.objects,
    reservation("orphan_unlinked_0001", "COMMITTED"),
  );
  const legacyMissing = await upload(
    current.objects,
    reservation("orphan_legacy_missing_0001", "COMMITTED"),
  );
  const young = await upload(
    current.objects,
    reservation("orphan_young_0001", "COMMITTED"),
  );
  const valid = await upload(
    current.objects,
    reservation("orphan_valid_request_0001", "COMMITTED"),
  );
  const plant = await upload(
    current.objects,
    reservation("orphan_plant_photo_0001", "COMMITTED", "PLANT_PHOTO"),
  );
  const expiredAtBoundary = NOW - IDENTIFICATION_ORIGINAL_RETENTION_MILLIS;
  current.reservations.records.set(unlinked.reservationId, {
    ...unlinked,
    identificationRequestId: null,
    committedAtMillis: expiredAtBoundary,
  });
  current.reservations.records.set(legacyMissing.reservationId, {
    ...legacyMissing,
    committedAtMillis: expiredAtBoundary,
  });
  current.reservations.records.set(young.reservationId, {
    ...young,
    identificationRequestId: null,
    committedAtMillis: expiredAtBoundary + 1,
  });
  current.reservations.records.set(valid.reservationId, {
    ...valid,
    committedAtMillis: expiredAtBoundary,
  });
  current.reservations.records.set(plant.reservationId, {
    ...plant,
    committedAtMillis: expiredAtBoundary,
  });
  const validRequest = request(valid, false);
  current.persistence.requests.set(validRequest.request.requestId, {
    ...validRequest,
    request: { ...validRequest.request, hardExpiresAtMillis: NOW + 1 },
  });

  const first = await runIdentificationCleanup(current);
  assert.equal(first.failures.length, 0);
  assert.deepEqual(first.committedOrphanedOriginals, {
    scanned: 3,
    cleaned: 2,
    deferred: 1,
  });
  assert.equal(current.reservations.records.has(unlinked.reservationId), false);
  assert.equal(current.reservations.records.has(legacyMissing.reservationId), false);
  assert.equal(current.reservations.records.has(young.reservationId), true);
  assert.equal(current.reservations.records.has(valid.reservationId), true);
  assert.equal(current.reservations.records.has(plant.reservationId), true);

  // A crash after sealing remains in the bounded scan and converges on the next run.
  const crash = await upload(
    current.objects,
    reservation("orphan_crash_retry_0001", "COMMITTED"),
  );
  current.reservations.records.set(crash.reservationId, {
    ...crash,
    identificationRequestId: null,
    committedAtMillis: expiredAtBoundary,
  });
  current.persistence.failBeforePurge.add(crash.reservationId);
  const crashed = await runIdentificationCleanup(current);
  assert.equal(crashed.failures.length, 1);
  assert.equal(current.reservations.records.get(crash.reservationId)?.state, "SEALED");
  const retried = await runIdentificationCleanup(current);
  assert.equal(retried.failures.length, 0);
  assert.equal(current.reservations.records.has(crash.reservationId), false);
});

test("committed orphan scan converges records beyond its bounded limit", async () => {
  const current = dependencies();
  const expiredAt = NOW - IDENTIFICATION_ORIGINAL_RETENTION_MILLIS;
  const items = await Promise.all(
    ["orphan_limit_0001", "orphan_limit_0002", "orphan_limit_0003"].map(async (id) => {
      const uploaded = await upload(current.objects, reservation(id, "COMMITTED"));
      return {
        ...uploaded,
        identificationRequestId: null,
        committedAtMillis: expiredAt,
      };
    }),
  );
  for (const item of items) current.reservations.records.set(item.reservationId, item);

  const first = await runIdentificationCleanup({ ...current, limitPerScan: 2 });
  assert.deepEqual(first.committedOrphanedOriginals, {
    scanned: 2,
    cleaned: 2,
    deferred: 0,
  });
  const second = await runIdentificationCleanup({ ...current, limitPerScan: 2 });
  assert.deepEqual(second.committedOrphanedOriginals, {
    scanned: 1,
    cleaned: 1,
    deferred: 0,
  });
  assert.equal(current.reservations.records.size, 0);
});

test("cleanup resumes after object deletion, after sealing, and after a lost purge response", async () => {
  const current = dependencies();
  const afterDelete = await upload(
    current.objects,
    reservation("after_delete_crash_0001", "COMMITTED"),
  );
  const afterSeal = await upload(
    current.objects,
    reservation("after_seal_crash_0001", "COMMITTED"),
  );
  const responseLoss = await upload(
    current.objects,
    reservation("response_loss_0001", "COMMITTED"),
  );
  for (const item of [afterDelete, afterSeal, responseLoss]) {
    current.reservations.records.set(item.reservationId, item);
    current.persistence.requests.set(
      item.identificationRequestId!,
      request(item, true),
    );
  }
  current.objects.beforeNextSealCreate = () => {
    throw new Error("injected crash after exact-generation delete");
  };
  current.persistence.failBeforePurge.add(afterSeal.reservationId);
  current.persistence.failAfterPurge.add(responseLoss.reservationId);

  const first = await runIdentificationCleanup(current);
  assert.equal(first.failures.length, 3);
  assert.equal(await current.objects.inspect(afterDelete.objectPath), null);
  assert.equal(
    isPrivateMediaSeal((await current.objects.inspect(afterSeal.objectPath))!),
    true,
  );
  assert.equal(
    current.persistence.requests.has(responseLoss.identificationRequestId!),
    false,
  );

  const second = await runIdentificationCleanup(current);
  assert.equal(second.failures.length, 0);
  assert.equal(current.persistence.requests.size, 0);
  assert.equal(current.reservations.records.size, 0);
});

test("one item failure is typed, unrelated items continue, and each scan obeys its limit", async () => {
  const current = dependencies();
  const items = [1, 2, 3].map((index) =>
    reservation(`limited_original_000${index}`, "COMMITTED"),
  );
  for (const item of items) {
    current.reservations.records.set(item.reservationId, item);
    current.persistence.requests.set(
      item.identificationRequestId!,
      request(item, false),
    );
  }
  current.persistence.failBeforePurge.add(items[0]!.reservationId);
  current.legacyObjects.originals.set("identification-originals/a", "1");
  current.legacyObjects.originals.set("identification-originals/b", "2");
  current.legacyObjects.fail.add("identification-originals/a");

  const result = await runIdentificationCleanup({
    ...current,
    limitPerScan: 2,
  });

  assert.equal(result.nonterminalDeadlines.scanned, 2);
  assert.equal(result.nonterminalDeadlines.cleaned, 1);
  assert.equal(result.legacyOriginals.scanned, 2);
  assert.equal(result.legacyOriginals.cleaned, 1);
  assert.deepEqual(
    result.failures.map(({ category, itemId, stage }) => ({
      category,
      itemId,
      stage,
    })),
    [
      {
        category: "NONTERMINAL_HARD_DEADLINE",
        itemId: items[0]!.identificationRequestId,
        stage: "PURGE",
      },
      {
        category: "LEGACY_ORIGINAL",
        itemId: "identification-originals/a",
        stage: "DELETE",
      },
    ],
  );
  assert.equal(
    current.persistence.requests.has(items[1]!.identificationRequestId!),
    false,
  );
  assert.equal(
    current.persistence.requests.has(items[2]!.identificationRequestId!),
    true,
  );
});
