import assert from "node:assert/strict"
import test from "node:test"
import {
  type AuthContext,
  canonicalDeletionScope,
  DeletionError,
  OwnerIdSchema,
} from "./deletion-contract.js"
import {
  cancelAccountDeletion,
  previewAccountDeletion,
  requestAccountDeletion,
} from "./deletion-service.js"
import { FixedClock, InMemoryDeletionStore, SequenceRequestIds } from "./test-support.js"

const NOW = 1_800_000_000
const OWNER = OwnerIdSchema.parse("owner-a")
const FOREIGN_OWNER = OwnerIdSchema.parse("owner-b")

function auth(uid = OWNER, authTimeSeconds = NOW): AuthContext {
  return { uid, authTimeSeconds }
}

function requestDependencies(store: InMemoryDeletionStore, now = NOW) {
  return {
    store,
    clock: new FixedClock(now),
    requestIds: new SequenceRequestIds(["deletion-request-0001", "deletion-request-0002"]),
  }
}

test("preview returns the canonical owner-bound scope when authenticated", async () => {
  // Given
  const store = new InMemoryDeletionStore()

  // When
  const result = await previewAccountDeletion(auth(), { ownerID: OWNER }, store)

  // Then
  assert.deepEqual(result, { scope: canonicalDeletionScope(OWNER), workflow: null })
  assert.equal(
    result.scope.scopeHash,
    "5a6497a609f7c7a67f376563a28157c1444b0e316e8882641d4a18e43249935a",
  )
})

test("preview rejects a foreign owner when auth uid differs", async () => {
  // Given
  const store = new InMemoryDeletionStore()

  // When
  const action = previewAccountDeletion(auth(), { ownerID: FOREIGN_OWNER }, store)

  // Then
  await assert.rejects(
    action,
    (error: unknown) => error instanceof DeletionError && error.code === "permission-denied",
  )
})

test("preview rejects an unauthenticated caller", async () => {
  // Given
  const store = new InMemoryDeletionStore()

  // When
  const action = previewAccountDeletion(null, { ownerID: OWNER }, store)

  // Then
  await assert.rejects(
    action,
    (error: unknown) => error instanceof DeletionError && error.code === "unauthenticated",
  )
})

test("request persists one workflow with an exact seven-day grace period", async () => {
  // Given
  const store = new InMemoryDeletionStore()
  const scope = canonicalDeletionScope(OWNER)

  // When
  const result = await requestAccountDeletion(
    auth(),
    { ownerID: OWNER, scopeHash: scope.scopeHash },
    requestDependencies(store),
  )

  // Then
  assert.equal(result.workflow.requestedAt, NOW)
  assert.equal(result.workflow.scheduledAt, NOW + 7 * 24 * 60 * 60)
  assert.equal(result.workflow.status, "RECEIVED")
  assert.equal(store.requestCount, 1)
})

test("duplicate request returns the original workflow without moving its schedule", async () => {
  // Given
  const store = new InMemoryDeletionStore()
  const scope = canonicalDeletionScope(OWNER)
  const dependencies = requestDependencies(store)
  const first = await requestAccountDeletion(
    auth(),
    { ownerID: OWNER, scopeHash: scope.scopeHash },
    dependencies,
  )

  // When
  const duplicate = await requestAccountDeletion(
    auth(),
    { ownerID: OWNER, scopeHash: scope.scopeHash },
    dependencies,
  )

  // Then
  assert.deepEqual(duplicate, first)
  assert.equal(store.requestCount, 1)
})

test("request rejects a stale scope hash", async () => {
  // Given
  const store = new InMemoryDeletionStore()

  // When
  const action = requestAccountDeletion(
    auth(),
    { ownerID: OWNER, scopeHash: "0".repeat(64) },
    requestDependencies(store),
  )

  // Then
  await assert.rejects(
    action,
    (error: unknown) => error instanceof DeletionError && error.code === "failed-precondition",
  )
})

test("request rejects authentication older than five minutes", async () => {
  // Given
  const store = new InMemoryDeletionStore()
  const scope = canonicalDeletionScope(OWNER)

  // When
  const action = requestAccountDeletion(
    auth(OWNER, NOW - 301),
    { ownerID: OWNER, scopeHash: scope.scopeHash },
    requestDependencies(store),
  )

  // Then
  await assert.rejects(
    action,
    (error: unknown) => error instanceof DeletionError && error.code === "failed-precondition",
  )
})

test("cancel marks a received request cancelled before the grace deadline", async () => {
  // Given
  const store = new InMemoryDeletionStore()
  const scope = canonicalDeletionScope(OWNER)
  const requested = await requestAccountDeletion(
    auth(),
    { ownerID: OWNER, scopeHash: scope.scopeHash },
    requestDependencies(store),
  )

  // When
  const result = await cancelAccountDeletion(
    auth(),
    { ownerID: OWNER, requestID: requested.workflow.requestID },
    { store, clock: new FixedClock(NOW + 1) },
  )

  // Then
  assert.equal(result.workflow.status, "CANCELLED")
  assert.equal(store.pendingExecutionCount, 0)
})

test("cancel rejects a request at the scheduled deadline", async () => {
  // Given
  const store = new InMemoryDeletionStore()
  const scope = canonicalDeletionScope(OWNER)
  const requested = await requestAccountDeletion(
    auth(),
    { ownerID: OWNER, scopeHash: scope.scopeHash },
    requestDependencies(store),
  )

  // When
  const action = cancelAccountDeletion(
    auth(),
    { ownerID: OWNER, requestID: requested.workflow.requestID },
    { store, clock: new FixedClock(requested.workflow.scheduledAt) },
  )

  // Then
  await assert.rejects(
    action,
    (error: unknown) => error instanceof DeletionError && error.code === "failed-precondition",
  )
})
