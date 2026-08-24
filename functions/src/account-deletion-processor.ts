import {
  ACCOUNT_DELETION_LEASE_MILLIS,
  ACCOUNT_DELETION_SCAN_LIMIT,
  ACCOUNT_DELETION_SCOPES,
  type AccountDeletionCleaner,
  AccountDeletionCleanupError,
  type AccountDeletionRecord,
  type AccountDeletionScope,
  type AccountDeletionStore,
} from "./account-deletion-contract.js";

type ProcessorDependencies = Readonly<{
  store: AccountDeletionStore;
  cleaner: AccountDeletionCleaner;
  nowMillis: () => number;
}>;

type ProcessorSummary = Readonly<{
  claimed: number;
  completed: number;
  failed: number;
  partiallyFailed: number;
}>;

async function processClaim(
  record: AccountDeletionRecord,
  dependencies: ProcessorDependencies,
): Promise<AccountDeletionRecord> {
  const completed = new Set(record.completedScopes);
  const failed: AccountDeletionScope[] = [];
  for (const scope of ACCOUNT_DELETION_SCOPES) {
    if (completed.has(scope)) continue;
    if (
      (scope === "USER_DOCUMENTS" &&
        (failed.includes("PUBLIC_SHARES") ||
          failed.includes("NOTIFICATION_ENDPOINT_OWNERS"))) ||
      (scope === "AUTH_ACCOUNT" && failed.length > 0)
    ) {
      failed.push(scope);
      continue;
    }
    try {
      await dependencies.cleaner.clean(record.ownerUid, scope);
      completed.add(scope);
    } catch (error: unknown) {
      if (!(error instanceof AccountDeletionCleanupError)) throw error;
      failed.push(scope);
    }
  }
  return dependencies.store.finish({
    ownerUid: record.ownerUid,
    requestId: record.requestId,
    claimGeneration: record.claimGeneration,
    completedScopes: ACCOUNT_DELETION_SCOPES.filter((scope) => completed.has(scope)),
    failedScopes: failed,
    nowMillis: dependencies.nowMillis(),
  });
}

export async function runAccountDeletionScan(
  dependencies: ProcessorDependencies,
): Promise<ProcessorSummary> {
  const nowMillis = dependencies.nowMillis();
  const claimed = await dependencies.store.claimDue({
    nowMillis,
    leaseExpiresAtMillis: nowMillis + ACCOUNT_DELETION_LEASE_MILLIS,
    limit: ACCOUNT_DELETION_SCAN_LIMIT,
  });
  const finished: AccountDeletionRecord[] = [];
  for (const record of claimed) finished.push(await processClaim(record, dependencies));
  return {
    claimed: claimed.length,
    completed: finished.filter((record) => record.status === "COMPLETED").length,
    failed: finished.filter((record) => record.status === "FAILED").length,
    partiallyFailed: finished.filter((record) => record.status === "PARTIALLY_FAILED").length,
  };
}
