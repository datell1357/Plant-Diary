import assert from "node:assert/strict"
import test from "node:test"
import {
  type AuthContext,
  canonicalDeletionScope,
  DeletionError,
  OwnerIdSchema,
} from "./deletion-contract.js"
import { recoverAccountDeletion, requestAccountDeletion } from "./deletion-service.js"
import { FixedClock, InMemoryDeletionStore, SequenceRequestIds } from "./test-support.js"

const NOW = 1_800_000_000
const OWNER = OwnerIdSchema.parse("owner-a")
const AUTH: AuthContext = { uid: OWNER, authTimeSeconds: NOW }

async function completedStore() {
  const store = new InMemoryDeletionStore()
  const scope = canonicalDeletionScope(OWNER)
  const requested = await requestAccountDeletion(
    AUTH,
    { ownerID: OWNER, scopeHash: scope.scopeHash },
    {
      store,
      clock: new FixedClock(NOW),
      requestIds: new SequenceRequestIds(["deletion-request-0001"]),
    },
  )
  await store.claimDue({ nowSeconds: NOW + 7 * 24 * 60 * 60, leaseSeconds: 600, limit: 1 })
  await store.finish({
    ownerID: OWNER,
    requestID: requested.workflow.requestID,
    succeededCategories: requested.workflow.scope.categories,
    failedCategories: [],
    nowSeconds: NOW + 7 * 24 * 60 * 60,
  })
  return { store, requestID: requested.workflow.requestID }
}

test("opaque request capability recovers completion without auth", async () => {
  // Given
  const { store, requestID } = await completedStore()

  // When
  const result = await recoverAccountDeletion({ ownerID: OWNER, requestID }, store)

  // Then
  assert.equal(result.workflow.status, "COMPLETED")
})

test("opaque recovery rejects a mismatched request capability", async () => {
  // Given
  const store = new InMemoryDeletionStore()

  // When
  const action = recoverAccountDeletion(
    { ownerID: OWNER, requestID: "foreign-request-0001" },
    store,
  )

  // Then
  await assert.rejects(
    action,
    (error: unknown) => error instanceof DeletionError && error.code === "permission-denied",
  )
})
