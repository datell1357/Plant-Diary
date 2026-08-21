import assert from "node:assert/strict"
import test from "node:test"
import {
  type AuthContext,
  CLEANUP_ORDER,
  type CleanupCategory,
  canonicalDeletionScope,
  OwnerIdSchema,
} from "./deletion-contract.js"
import { runDueAccountDeletions } from "./deletion-execution.js"
import { requestAccountDeletion } from "./deletion-service.js"
import {
  FixedClock,
  InMemoryAccountCleaner,
  InMemoryDeletionStore,
  SequenceRequestIds,
} from "./test-support.js"

const NOW = 1_800_000_000
const DUE = NOW + 7 * 24 * 60 * 60
const OWNER = OwnerIdSchema.parse("owner-a")
const AUTH: AuthContext = { uid: OWNER, authTimeSeconds: NOW }

async function pendingStore(): Promise<InMemoryDeletionStore> {
  const store = new InMemoryDeletionStore()
  const scope = canonicalDeletionScope(OWNER)
  await requestAccountDeletion(
    AUTH,
    { ownerID: OWNER, scopeHash: scope.scopeHash },
    {
      store,
      clock: new FixedClock(NOW),
      requestIds: new SequenceRequestIds(["deletion-request-0001"]),
    },
  )
  return store
}

test("scheduled scan does not claim a request before seven days", async () => {
  // Given
  const store = await pendingStore()
  const cleaner = new InMemoryAccountCleaner()

  // When
  const result = await runDueAccountDeletions({
    store,
    cleaner,
    clock: new FixedClock(DUE - 1),
  })

  // Then
  assert.deepEqual(result, { claimed: 0, completed: 0, pendingRetry: 0 })
  assert.deepEqual(cleaner.calls, [])
})

test("scheduled scan performs all cleanup contracts in safe order at seven days", async () => {
  // Given
  const store = await pendingStore()
  const cleaner = new InMemoryAccountCleaner()

  // When
  const result = await runDueAccountDeletions({
    store,
    cleaner,
    clock: new FixedClock(DUE),
  })

  // Then
  assert.deepEqual(result, { claimed: 1, completed: 1, pendingRetry: 0 })
  assert.deepEqual(cleaner.calls, CLEANUP_ORDER)
  assert.equal(store.currentWorkflow(OWNER)?.status, "COMPLETED")
})

test("scheduled scan records partial failure and never deletes auth after data failure", async () => {
  // Given
  const store = await pendingStore()
  const failedCategory: CleanupCategory = "FIRESTORE_ACCOUNT_DATA"
  const cleaner = new InMemoryAccountCleaner(new Set([failedCategory]))

  // When
  const result = await runDueAccountDeletions({
    store,
    cleaner,
    clock: new FixedClock(DUE),
  })

  // Then
  assert.deepEqual(result, { claimed: 1, completed: 0, pendingRetry: 1 })
  assert.equal(cleaner.calls.includes("AUTH_ACCOUNT"), false)
  assert.equal(store.currentWorkflow(OWNER)?.status, "PARTIALLY_FAILED")
  assert.deepEqual(store.currentWorkflow(OWNER)?.failedCategories, [failedCategory])
})

test("scheduled retry skips completed cleanup and finishes the previously failed category", async () => {
  // Given
  const store = await pendingStore()
  await runDueAccountDeletions({
    store,
    cleaner: new InMemoryAccountCleaner(new Set(["ACCOUNT_MEDIA"])),
    clock: new FixedClock(DUE),
  })
  const cleaner = new InMemoryAccountCleaner()

  // When
  const result = await runDueAccountDeletions({
    store,
    cleaner,
    clock: new FixedClock(DUE + 15 * 60),
  })

  // Then
  assert.deepEqual(result, { claimed: 1, completed: 1, pendingRetry: 0 })
  assert.deepEqual(cleaner.calls, ["ACCOUNT_MEDIA", "AUTH_ACCOUNT"])
  assert.equal(store.currentWorkflow(OWNER)?.status, "COMPLETED")
})

test("scheduled retry reclaims an expired processing lease after an interrupted run", async () => {
  // Given
  const store = await pendingStore()
  await store.claimDue({ nowSeconds: DUE, leaseSeconds: 10 * 60, limit: 20 })
  const cleaner = new InMemoryAccountCleaner()

  // When
  const result = await runDueAccountDeletions({
    store,
    cleaner,
    clock: new FixedClock(DUE + 10 * 60),
  })

  // Then
  assert.deepEqual(result, { claimed: 1, completed: 1, pendingRetry: 0 })
  assert.equal(store.currentWorkflow(OWNER)?.status, "COMPLETED")
})
