import { z } from "zod"
import {
  type AuthContext,
  CancelInputSchema,
  type Clock,
  canonicalDeletionScope,
  DeletionError,
  type DeletionRequestIds,
  type DeletionStore,
  type DeletionWorkflow,
  DeletionWorkflowSchema,
  GRACE_SECONDS,
  PreviewInputSchema,
  RECENT_AUTH_SECONDS,
  RequestIdSchema,
  RequestInputSchema,
  requireOwner,
} from "./deletion-contract.js"

type RequestDependencies = Readonly<{
  store: DeletionStore
  clock: Clock
  requestIds: DeletionRequestIds
}>

type CancelDependencies = Readonly<{
  store: DeletionStore
  clock: Clock
}>

function parseBoundary<T>(schema: z.ZodType<T>, input: unknown): T {
  try {
    return schema.parse(input)
  } catch (error: unknown) {
    if (error instanceof z.ZodError) {
      throw new DeletionError("invalid-argument", "Payload does not match the contract", {
        cause: error,
      })
    }
    throw error
  }
}

export async function previewAccountDeletion(
  auth: AuthContext | null,
  input: unknown,
  store: DeletionStore,
): Promise<
  Readonly<{ scope: ReturnType<typeof canonicalDeletionScope>; workflow: DeletionWorkflow | null }>
> {
  const command = parseBoundary(PreviewInputSchema, input)
  requireOwner(auth, command.ownerID)
  return {
    scope: canonicalDeletionScope(command.ownerID),
    workflow: await store.load(command.ownerID),
  }
}

export async function requestAccountDeletion(
  auth: AuthContext | null,
  input: unknown,
  dependencies: RequestDependencies,
): Promise<Readonly<{ workflow: DeletionWorkflow }>> {
  const command = parseBoundary(RequestInputSchema, input)
  const authenticated = requireOwner(auth, command.ownerID)
  const nowSeconds = dependencies.clock.nowSeconds()
  if (
    authenticated.authTimeSeconds === null ||
    authenticated.authTimeSeconds > nowSeconds ||
    nowSeconds - authenticated.authTimeSeconds > RECENT_AUTH_SECONDS
  ) {
    throw new DeletionError("failed-precondition", "Recent authentication is required")
  }
  const scope = canonicalDeletionScope(command.ownerID)
  if (command.scopeHash !== scope.scopeHash) {
    throw new DeletionError("failed-precondition", "Deletion scope is stale")
  }
  const workflow = DeletionWorkflowSchema.parse({
    requestID: RequestIdSchema.parse(dependencies.requestIds.next()),
    ownerID: command.ownerID,
    scope,
    requestedAt: nowSeconds,
    scheduledAt: nowSeconds + GRACE_SECONDS,
    status: "RECEIVED",
    succeededCategories: [],
    failedCategories: [],
  })
  return { workflow: await dependencies.store.create({ workflow }) }
}

export async function cancelAccountDeletion(
  auth: AuthContext | null,
  input: unknown,
  dependencies: CancelDependencies,
): Promise<Readonly<{ workflow: DeletionWorkflow }>> {
  const command = parseBoundary(CancelInputSchema, input)
  requireOwner(auth, command.ownerID)
  const workflow = await dependencies.store.cancel({
    ownerID: command.ownerID,
    requestID: command.requestID,
    nowSeconds: dependencies.clock.nowSeconds(),
  })
  if (workflow === null) {
    throw new DeletionError(
      "failed-precondition",
      "Only a received deletion request can be cancelled before its deadline",
    )
  }
  return { workflow }
}
