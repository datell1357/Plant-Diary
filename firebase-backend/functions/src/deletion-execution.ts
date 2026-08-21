import {
  type AccountCleaner,
  CLEANUP_ORDER,
  type CleanupCategory,
  type Clock,
  type DeletionStore,
  type DeletionWorkflow,
  EXECUTION_LEASE_SECONDS,
} from "./deletion-contract.js"

const SCAN_LIMIT = 20

type ExecutionDependencies = Readonly<{
  store: DeletionStore
  cleaner: AccountCleaner
  clock: Clock
}>

type ExecutionSummary = Readonly<{
  claimed: number
  completed: number
  pendingRetry: number
}>

async function executeClaimed(
  workflow: DeletionWorkflow,
  dependencies: ExecutionDependencies,
): Promise<boolean> {
  const succeeded = new Set(workflow.succeededCategories)
  const failed: CleanupCategory[] = []

  for (const category of CLEANUP_ORDER) {
    if (succeeded.has(category)) continue
    if (category === "AUTH_ACCOUNT" && failed.length > 0) continue
    try {
      await dependencies.cleaner.clean(workflow.ownerID, category)
      succeeded.add(category)
    } catch (error: unknown) {
      if (!(error instanceof Error)) throw error
      failed.push(category)
    }
  }

  await dependencies.store.finish({
    ownerID: workflow.ownerID,
    requestID: workflow.requestID,
    succeededCategories: CLEANUP_ORDER.filter((category) => succeeded.has(category)),
    failedCategories: failed,
    nowSeconds: dependencies.clock.nowSeconds(),
  })
  return failed.length === 0
}

export async function runDueAccountDeletions(
  dependencies: ExecutionDependencies,
): Promise<ExecutionSummary> {
  const nowSeconds = dependencies.clock.nowSeconds()
  await dependencies.store.purgeExpiredCompleted({ nowSeconds, limit: SCAN_LIMIT })
  const claimed = await dependencies.store.claimDue({
    nowSeconds,
    leaseSeconds: EXECUTION_LEASE_SECONDS,
    limit: SCAN_LIMIT,
  })
  let completed = 0
  for (const workflow of claimed) {
    if (await executeClaimed(workflow, dependencies)) completed += 1
  }
  return {
    claimed: claimed.length,
    completed,
    pendingRetry: claimed.length - completed,
  }
}
