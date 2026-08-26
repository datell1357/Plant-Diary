import {
  IDENTIFICATION_CLEANUP_SCAN_LIMIT,
  effectiveRetentionExpiryMillis,
  isTerminalIdentificationStatus,
  unadmittedIdentificationOriginalExpiryMillis,
  type AuthorizedIdentificationRequest,
} from "./identification-authorization-contract.js";
import {
  isPrivateMediaSeal,
  type PrivateMediaObjectStore,
  type PrivateMediaReservation,
  type PrivateMediaReservationRepository,
} from "./private-media-contract.js";
import { sealPrivateMediaReservation } from "./private-media-seal.js";

export const IDENTIFICATION_LEGACY_ORIGINAL_PREFIX =
  "identification-originals/";

export type IdentificationCleanupCategory =
  | "RESERVED_UPLOAD"
  | "COMMITTED_ORPHANED_ORIGINAL"
  | "NONTERMINAL_HARD_DEADLINE"
  | "TERMINAL_RETENTION_DEADLINE"
  | "LEGACY_ORIGINAL";

export type IdentificationCleanupFailure = Readonly<{
  category: IdentificationCleanupCategory;
  itemId: string;
  stage: "SCAN" | "SEAL" | "PURGE" | "DELETE";
  errorName: string;
  message: string;
}>;

export type IdentificationCleanupScanResult = Readonly<{
  scanned: number;
  cleaned: number;
  deferred: number;
}>;

export type IdentificationCleanupResult = Readonly<{
  limitPerScan: number;
  reservedUploads: IdentificationCleanupScanResult;
  committedOrphanedOriginals: IdentificationCleanupScanResult;
  nonterminalDeadlines: IdentificationCleanupScanResult;
  terminalDeadlines: IdentificationCleanupScanResult;
  legacyOriginals: IdentificationCleanupScanResult;
  failures: readonly IdentificationCleanupFailure[];
}>;

export type IdentificationCleanupRequestCandidate = Readonly<{
  documentPath: string;
  request: AuthorizedIdentificationRequest;
}>;

export type IdentificationCleanupScanBatch<T> = Readonly<{
  items: readonly T[];
  failures: readonly Readonly<{ itemId: string; error: unknown }>[];
}>;

export interface IdentificationCleanupPersistence {
  scanExpiredReservedUploads(
    nowMillis: number,
    limit: number,
  ): Promise<IdentificationCleanupScanBatch<PrivateMediaReservation>>;
  scanExpiredCommittedOrphanedOriginals(
    nowMillis: number,
    limit: number,
  ): Promise<IdentificationCleanupScanBatch<PrivateMediaReservation>>;
  /** Atomically fences admission before returning an eligible orphan cleanup claim. */
  claimCommittedOrphanedOriginal(
    reservation: PrivateMediaReservation,
    nowMillis: number,
  ): Promise<PrivateMediaReservation | null>;
  scanExpiredNonterminalRequests(
    nowMillis: number,
    limit: number,
  ): Promise<
    IdentificationCleanupScanBatch<IdentificationCleanupRequestCandidate>
  >;
  scanExpiredTerminalRequests(
    nowMillis: number,
    limit: number,
  ): Promise<
    IdentificationCleanupScanBatch<IdentificationCleanupRequestCandidate>
  >;
  /** Atomically pins request eligibility and original generation before object I/O. */
  claimIdentificationRequest(
    candidate: IdentificationCleanupRequestCandidate,
    reservation: PrivateMediaReservation,
    nowMillis: number,
  ): Promise<PrivateMediaReservation | null>;
  purgeReservedUpload(
    reservation: PrivateMediaReservation,
    sealedGeneration: string,
    nowMillis: number,
  ): Promise<"purged" | "already_purged" | "deferred">;
  purgeIdentificationRequest(
    candidate: IdentificationCleanupRequestCandidate,
    reservation: PrivateMediaReservation,
    sealedGeneration: string,
    nowMillis: number,
  ): Promise<"purged" | "already_purged" | "deferred">;
  purgeCommittedOrphanedOriginal(
    reservation: PrivateMediaReservation,
    sealedGeneration: string,
    nowMillis: number,
  ): Promise<"purged" | "already_purged" | "deferred">;
}

export type LegacyIdentificationOriginal = Readonly<{
  path: string;
  generation: string;
}>;

export interface LegacyIdentificationOriginalStore {
  listLegacyIdentificationOriginals(
    limit: number,
  ): Promise<IdentificationCleanupScanBatch<LegacyIdentificationOriginal>>;
  deleteLegacyIdentificationOriginal(
    original: LegacyIdentificationOriginal,
  ): Promise<"deleted" | "absent" | "generation_changed">;
}

type CleanupDependencies = Readonly<{
  persistence: IdentificationCleanupPersistence;
  reservations: PrivateMediaReservationRepository;
  objects: PrivateMediaObjectStore;
  legacyObjects: LegacyIdentificationOriginalStore;
  nowMillis: () => number;
  limitPerScan?: number;
}>;

type MutableScan = { scanned: number; cleaned: number; deferred: number };

export class IdentificationCleanupRunError extends Error {
  override readonly name = "IdentificationCleanupRunError";

  constructor(readonly result: IdentificationCleanupResult) {
    super(
      `Identification cleanup completed with ${result.failures.length} item failure(s)`,
    );
  }
}

/**
 * Runs five independent bounded scans. Each item converges through Todo16's generation-safe seal
 * before its owner-linked metadata is purged. Item failures are retained in the typed result and
 * never stop unrelated candidates in this invocation.
 */
export async function runIdentificationCleanup(
  dependencies: CleanupDependencies,
): Promise<IdentificationCleanupResult> {
  const nowMillis = dependencies.nowMillis();
  const limit = dependencies.limitPerScan ?? IDENTIFICATION_CLEANUP_SCAN_LIMIT;
  if (!Number.isSafeInteger(nowMillis))
    throw new TypeError("Cleanup clock is invalid");
  if (
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    limit > IDENTIFICATION_CLEANUP_SCAN_LIMIT
  ) {
    throw new TypeError("Cleanup scan limit is invalid");
  }

  const failures: IdentificationCleanupFailure[] = [];
  const reservedUploads = mutableScan();
  const committedOrphanedOriginals = mutableScan();
  const nonterminalDeadlines = mutableScan();
  const terminalDeadlines = mutableScan();
  const legacyOriginals = mutableScan();

  await runReservedScan(
    dependencies,
    nowMillis,
    limit,
    reservedUploads,
    failures,
  );
  await runCommittedOrphanedScan(
    dependencies,
    nowMillis,
    limit,
    committedOrphanedOriginals,
    failures,
  );
  await runRequestScan(
    "NONTERMINAL_HARD_DEADLINE",
    () =>
      dependencies.persistence.scanExpiredNonterminalRequests(nowMillis, limit),
    dependencies,
    nowMillis,
    nonterminalDeadlines,
    failures,
  );
  await runRequestScan(
    "TERMINAL_RETENTION_DEADLINE",
    () =>
      dependencies.persistence.scanExpiredTerminalRequests(nowMillis, limit),
    dependencies,
    nowMillis,
    terminalDeadlines,
    failures,
  );
  await runLegacyScan(dependencies, limit, legacyOriginals, failures);

  return {
    limitPerScan: limit,
    reservedUploads,
    committedOrphanedOriginals,
    nonterminalDeadlines,
    terminalDeadlines,
    legacyOriginals,
    failures,
  };
}

export function throwIfIdentificationCleanupFailed(
  result: IdentificationCleanupResult,
): void {
  if (result.failures.length > 0)
    throw new IdentificationCleanupRunError(result);
}

async function runReservedScan(
  dependencies: CleanupDependencies,
  nowMillis: number,
  limit: number,
  result: MutableScan,
  failures: IdentificationCleanupFailure[],
): Promise<void> {
  let batch: IdentificationCleanupScanBatch<PrivateMediaReservation>;
  try {
    batch = await dependencies.persistence.scanExpiredReservedUploads(
      nowMillis,
      limit,
    );
  } catch (error: unknown) {
    failures.push(failure("RESERVED_UPLOAD", "scan", "SCAN", error));
    return;
  }
  appendParseFailures("RESERVED_UPLOAD", batch, failures);
  result.scanned += batch.items.length + batch.failures.length;
  for (const reservation of batch.items) {
    const itemId = reservation.reservationId;
    if (
      reservation.mediaKind !== "IDENTIFICATION_ORIGINAL" ||
      (reservation.state !== "RESERVED" &&
        !(reservation.state === "SEALED" &&
          reservation.cleanupClaimReason === "EXPIRED_RESERVED_UPLOAD")) ||
      reservation.expiresAtMillis > nowMillis
    ) {
      result.deferred += 1;
      continue;
    }
    let claimed: PrivateMediaReservation | null;
    try {
      claimed = await dependencies.reservations.claimExpiredReservedUpload({
        reservation,
        nowMillis,
      });
    } catch (error: unknown) {
      failures.push(failure("RESERVED_UPLOAD", itemId, "SEAL", error));
      continue;
    }
    if (claimed === null) {
      result.deferred += 1;
      continue;
    }
    let sealedGeneration: string;
    try {
      ({ sealedGeneration } = await sealPrivateMediaReservation(claimed, {
        repository: dependencies.reservations,
        objects: dependencies.objects,
        nowMillis: dependencies.nowMillis,
      }));
    } catch (error: unknown) {
      failures.push(failure("RESERVED_UPLOAD", itemId, "SEAL", error));
      continue;
    }
    try {
      const outcome = await dependencies.persistence.purgeReservedUpload(
        claimed,
        sealedGeneration,
        nowMillis,
      );
      outcome === "deferred" ? (result.deferred += 1) : (result.cleaned += 1);
    } catch (error: unknown) {
      failures.push(failure("RESERVED_UPLOAD", itemId, "PURGE", error));
    }
  }
}

async function runCommittedOrphanedScan(
  dependencies: CleanupDependencies,
  nowMillis: number,
  limit: number,
  result: MutableScan,
  failures: IdentificationCleanupFailure[],
): Promise<void> {
  let batch: IdentificationCleanupScanBatch<PrivateMediaReservation>;
  try {
    batch = await dependencies.persistence.scanExpiredCommittedOrphanedOriginals(nowMillis, limit);
  } catch (error: unknown) {
    failures.push(failure("COMMITTED_ORPHANED_ORIGINAL", "scan", "SCAN", error));
    return;
  }
  appendParseFailures("COMMITTED_ORPHANED_ORIGINAL", batch, failures);
  result.scanned += batch.items.length + batch.failures.length;
  for (const reservation of batch.items) {
    const itemId = reservation.reservationId;
    if (
      reservation.mediaKind !== "IDENTIFICATION_ORIGINAL"
      || (reservation.state !== "COMMITTED" && reservation.state !== "SEALED")
      || (reservation.state === "COMMITTED" && reservation.objectGeneration === null)
      || unadmittedIdentificationOriginalExpiryMillis(reservation) > nowMillis
    ) {
      result.deferred += 1;
      continue;
    }
    let claimed: PrivateMediaReservation | null;
    try {
      claimed = await dependencies.persistence.claimCommittedOrphanedOriginal(
        reservation,
        nowMillis,
      );
    } catch (error: unknown) {
      failures.push(failure("COMMITTED_ORPHANED_ORIGINAL", itemId, "SEAL", error));
      continue;
    }
    if (claimed === null) {
      result.deferred += 1;
      continue;
    }
    if (claimed.cleanupClaimReason !== "COMMITTED_ORPHANED_IDENTIFICATION_ORIGINAL" || claimed.cleanupClaimGeneration == null) {
      failures.push(failure("COMMITTED_ORPHANED_ORIGINAL", itemId, "SEAL", new Error("Committed orphan cleanup claim is malformed")));
      continue;
    }
    const claimedGeneration = claimed.cleanupClaimGeneration;
    try {
      const sealGeneration = await convergeClaimedOrphanSeal(
        dependencies.objects,
        claimed.objectPath,
        claimedGeneration,
      );
      if (sealGeneration === null) {
        result.deferred += 1;
        continue;
      }
      await dependencies.reservations.markSealed({
        expectedReservation: claimed,
        sealedGeneration: sealGeneration,
        sealedAtMillis: dependencies.nowMillis(),
      });
    } catch (error: unknown) {
      failures.push(failure("COMMITTED_ORPHANED_ORIGINAL", itemId, "SEAL", error));
      continue;
    }
    try {
      const outcome = await dependencies.persistence.purgeCommittedOrphanedOriginal(
        claimed,
        claimedGeneration,
        nowMillis,
      );
      outcome === "deferred" ? (result.deferred += 1) : (result.cleaned += 1);
    } catch (error: unknown) {
      failures.push(failure("COMMITTED_ORPHANED_ORIGINAL", itemId, "PURGE", error));
    }
  }
}

async function convergeClaimedOrphanSeal(
  objects: PrivateMediaObjectStore,
  path: string,
  generation: string,
): Promise<string | null> {
  const current = await objects.inspect(path);
  if (current !== null && current.generation !== generation) {
    return isPrivateMediaSeal(current) ? current.generation : null;
  }
  if (current !== null) {
    const deleted = await objects.deleteGeneration(path, generation);
    if (deleted === "generation_changed") return null;
  }
  const afterDelete = await objects.inspect(path);
  if (afterDelete !== null) return isPrivateMediaSeal(afterDelete) ? afterDelete.generation : null;
  const created = await objects.createSeal(path);
  if (created.kind === "created") return created.generation;
  const occupied = await objects.inspect(path);
  return occupied !== null && isPrivateMediaSeal(occupied) ? occupied.generation : null;
}

async function runRequestScan(
  category: "NONTERMINAL_HARD_DEADLINE" | "TERMINAL_RETENTION_DEADLINE",
  scan: () => Promise<
    IdentificationCleanupScanBatch<IdentificationCleanupRequestCandidate>
  >,
  dependencies: CleanupDependencies,
  nowMillis: number,
  result: MutableScan,
  failures: IdentificationCleanupFailure[],
): Promise<void> {
  let batch: IdentificationCleanupScanBatch<IdentificationCleanupRequestCandidate>;
  try {
    batch = await scan();
  } catch (error: unknown) {
    failures.push(failure(category, "scan", "SCAN", error));
    return;
  }
  appendParseFailures(category, batch, failures);
  result.scanned += batch.items.length + batch.failures.length;
  for (const candidate of batch.items) {
    const request = candidate.request;
    const terminal = isTerminalIdentificationStatus(request.status);
    if (
      (category === "TERMINAL_RETENTION_DEADLINE") !== terminal ||
      effectiveRetentionExpiryMillis(request) > nowMillis
    ) {
      result.deferred += 1;
      continue;
    }
    const reservation = await loadReservation(
      candidate,
      dependencies,
      category,
      failures,
    );
    if (reservation === null) continue;
    let claimed: PrivateMediaReservation | null;
    try {
      claimed = await dependencies.persistence.claimIdentificationRequest(
        candidate,
        reservation,
        nowMillis,
      );
    } catch (error: unknown) {
      failures.push(failure(category, request.requestId, "SEAL", error));
      continue;
    }
    if (claimed === null) {
      result.deferred += 1;
      continue;
    }
    let sealedGeneration: string;
    try {
      ({ sealedGeneration } = await sealPrivateMediaReservation(claimed, {
        repository: dependencies.reservations,
        objects: dependencies.objects,
        nowMillis: dependencies.nowMillis,
      }));
    } catch (error: unknown) {
      failures.push(failure(category, request.requestId, "SEAL", error));
      continue;
    }
    try {
      const outcome = await dependencies.persistence.purgeIdentificationRequest(
        candidate,
        claimed,
        sealedGeneration,
        nowMillis,
      );
      outcome === "deferred" ? (result.deferred += 1) : (result.cleaned += 1);
    } catch (error: unknown) {
      failures.push(failure(category, request.requestId, "PURGE", error));
    }
  }
}

async function loadReservation(
  candidate: IdentificationCleanupRequestCandidate,
  dependencies: CleanupDependencies,
  category: IdentificationCleanupCategory,
  failures: IdentificationCleanupFailure[],
): Promise<PrivateMediaReservation | null> {
  const request = candidate.request;
  try {
    const reservation = await dependencies.reservations.load(
      request.mediaReference.reservationId,
    );
    if (
      reservation === null ||
      reservation.ownerUid !== request.ownerUid ||
      reservation.mediaKind !== "IDENTIFICATION_ORIGINAL" ||
      reservation.identificationRequestId !== request.requestId ||
      (reservation.state === "COMMITTED" &&
        reservation.objectGeneration !== request.mediaReference.generation)
    ) {
      throw new Error("Identification cleanup reservation link is unavailable");
    }
    return reservation;
  } catch (error: unknown) {
    failures.push(failure(category, request.requestId, "SEAL", error));
    return null;
  }
}

async function runLegacyScan(
  dependencies: CleanupDependencies,
  limit: number,
  result: MutableScan,
  failures: IdentificationCleanupFailure[],
): Promise<void> {
  let batch: IdentificationCleanupScanBatch<LegacyIdentificationOriginal>;
  try {
    batch =
      await dependencies.legacyObjects.listLegacyIdentificationOriginals(limit);
  } catch (error: unknown) {
    failures.push(failure("LEGACY_ORIGINAL", "scan", "SCAN", error));
    return;
  }
  appendParseFailures("LEGACY_ORIGINAL", batch, failures);
  result.scanned = batch.items.length + batch.failures.length;
  for (const original of batch.items) {
    try {
      const outcome =
        await dependencies.legacyObjects.deleteLegacyIdentificationOriginal(
          original,
        );
      outcome === "generation_changed"
        ? (result.deferred += 1)
        : (result.cleaned += 1);
    } catch (error: unknown) {
      failures.push(failure("LEGACY_ORIGINAL", original.path, "DELETE", error));
    }
  }
}

function appendParseFailures<T>(
  category: IdentificationCleanupCategory,
  batch: IdentificationCleanupScanBatch<T>,
  failures: IdentificationCleanupFailure[],
): void {
  for (const item of batch.failures) {
    failures.push(failure(category, item.itemId, "SCAN", item.error));
  }
}

function mutableScan(): MutableScan {
  return { scanned: 0, cleaned: 0, deferred: 0 };
}

function failure(
  category: IdentificationCleanupCategory,
  itemId: string,
  stage: IdentificationCleanupFailure["stage"],
  error: unknown,
): IdentificationCleanupFailure {
  return {
    category,
    itemId,
    stage,
    errorName: error instanceof Error ? error.name : "UnknownError",
    message: error instanceof Error ? error.message : String(error),
  };
}
