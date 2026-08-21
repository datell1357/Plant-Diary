import { createHash } from "node:crypto"
import { z } from "zod"

export const SCOPE_VERSION = "planterior-account-deletion/v1" as const
export const GRACE_SECONDS = 7 * 24 * 60 * 60
export const RECENT_AUTH_SECONDS = 5 * 60
export const EXECUTION_LEASE_SECONDS = 10 * 60

export const CLEANUP_ORDER = [
  "FIRESTORE_ACCOUNT_DATA",
  "NOTIFICATION_LINKS",
  "PUBLIC_SHARES",
  "IDENTIFICATION_MEDIA",
  "ACCOUNT_MEDIA",
  "AUTH_ACCOUNT",
] as const

export const CleanupCategorySchema = z.enum(CLEANUP_ORDER)
export type CleanupCategory = z.infer<typeof CleanupCategorySchema>

export const OwnerIdSchema = z
  .string()
  .min(1)
  .max(128)
  .refine((value) => value !== "." && value !== ".." && !value.includes("/"))
  .brand("OwnerId")
export type OwnerId = z.infer<typeof OwnerIdSchema>

export const RequestIdSchema = z
  .string()
  .regex(/^[A-Za-z0-9_-]{8,128}$/)
  .brand("RequestId")
export type RequestId = z.infer<typeof RequestIdSchema>

const ScopeHashSchema = z
  .string()
  .regex(/^[a-f0-9]{64}$/)
  .brand("ScopeHash")
export type ScopeHash = z.infer<typeof ScopeHashSchema>

const DeletionStatusSchema = z.enum([
  "RECEIVED",
  "PROCESSING",
  "COMPLETED",
  "FAILED",
  "PARTIALLY_FAILED",
  "CANCELLED",
])
export type DeletionStatus = z.infer<typeof DeletionStatusSchema>

export const DeletionScopeSchema = z
  .object({
    ownerID: OwnerIdSchema,
    categories: z.array(CleanupCategorySchema).length(CLEANUP_ORDER.length).readonly(),
    scopeHash: ScopeHashSchema,
  })
  .strict()
  .readonly()
export type DeletionScope = z.infer<typeof DeletionScopeSchema>

export const DeletionWorkflowSchema = z
  .object({
    requestID: RequestIdSchema,
    ownerID: OwnerIdSchema,
    scope: DeletionScopeSchema,
    requestedAt: z.number().int().nonnegative(),
    scheduledAt: z.number().int().nonnegative(),
    status: DeletionStatusSchema,
    succeededCategories: z.array(CleanupCategorySchema).readonly(),
    failedCategories: z.array(CleanupCategorySchema).readonly(),
  })
  .strict()
  .readonly()
export type DeletionWorkflow = z.infer<typeof DeletionWorkflowSchema>

export const PreviewInputSchema = z.object({ ownerID: OwnerIdSchema }).strict().readonly()
export const RequestInputSchema = z
  .object({
    ownerID: OwnerIdSchema,
    scopeHash: ScopeHashSchema,
  })
  .strict()
  .readonly()
export const CancelInputSchema = z
  .object({
    ownerID: OwnerIdSchema,
    requestID: RequestIdSchema,
  })
  .strict()
  .readonly()

export type AuthContext = Readonly<{
  uid: OwnerId
  authTimeSeconds: number | null
}>

export type CreateDeletionCommand = Readonly<{
  workflow: DeletionWorkflow
}>

export type CancelDeletionCommand = Readonly<{
  ownerID: OwnerId
  requestID: RequestId
  nowSeconds: number
}>

export type ClaimDueCommand = Readonly<{
  nowSeconds: number
  leaseSeconds: number
  limit: number
}>

export type FinishDeletionCommand = Readonly<{
  ownerID: OwnerId
  requestID: RequestId
  succeededCategories: readonly CleanupCategory[]
  failedCategories: readonly CleanupCategory[]
}>

export interface Clock {
  nowSeconds(): number
}

export interface DeletionRequestIds {
  next(): string
}

export interface DeletionStore {
  load(ownerID: OwnerId): Promise<DeletionWorkflow | null>
  create(command: CreateDeletionCommand): Promise<DeletionWorkflow>
  cancel(command: CancelDeletionCommand): Promise<DeletionWorkflow | null>
  claimDue(command: ClaimDueCommand): Promise<readonly DeletionWorkflow[]>
  finish(command: FinishDeletionCommand): Promise<DeletionWorkflow>
}

export interface AccountCleaner {
  clean(ownerID: OwnerId, category: CleanupCategory): Promise<void>
}

export type DeletionErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition"

export class DeletionError extends Error {
  override readonly name = "DeletionError"

  constructor(
    readonly code: DeletionErrorCode,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options)
  }
}

export function canonicalDeletionScope(ownerID: OwnerId): DeletionScope {
  const canonical = JSON.stringify({
    categories: CLEANUP_ORDER,
    ownerID,
    version: SCOPE_VERSION,
  })
  const scopeHash = ScopeHashSchema.parse(
    createHash("sha256").update(canonical, "utf8").digest("hex"),
  )
  return DeletionScopeSchema.parse({ ownerID, categories: CLEANUP_ORDER, scopeHash })
}

export function requireOwner(auth: AuthContext | null, ownerID: OwnerId): AuthContext {
  if (auth === null) throw new DeletionError("unauthenticated", "Sign-in is required")
  if (auth.uid !== ownerID) {
    throw new DeletionError("permission-denied", "Account deletion owner does not match auth")
  }
  return auth
}
