import type { DocumentReference, Firestore } from "firebase-admin/firestore"
import { z } from "zod"
import { isClaimable } from "./deletion-claim-policy.js"
import {
  type CancelDeletionCommand,
  type ClaimDueCommand,
  type CreateDeletionCommand,
  type DeletionStore,
  type DeletionWorkflow,
  DeletionWorkflowSchema,
  type FinishDeletionCommand,
  type OwnerId,
  type PurgeDeletionReceiptsCommand,
  RECEIPT_RETENTION_SECONDS,
} from "./deletion-contract.js"

const StoredDeletionSchema = z
  .object({
    workflow: DeletionWorkflowSchema,
    nextAttemptAt: z.number().int().nonnegative().nullable(),
    receiptExpiresAt: z.number().int().nonnegative().nullable().optional(),
  })
  .strict()
  .readonly()
type StoredDeletion = z.infer<typeof StoredDeletionSchema>

export class DeletionPersistenceError extends Error {
  override readonly name = "DeletionPersistenceError"
}

function parseStored(value: unknown): StoredDeletion {
  const parsed = StoredDeletionSchema.safeParse(value)
  if (!parsed.success) {
    throw new DeletionPersistenceError("Stored account deletion request is malformed", {
      cause: parsed.error,
    })
  }
  return parsed.data
}

export class FirestoreDeletionStore implements DeletionStore {
  constructor(private readonly firestore: Firestore) {}

  async load(ownerID: OwnerId): Promise<DeletionWorkflow | null> {
    const snapshot = await this.reference(ownerID).get()
    return snapshot.exists ? parseStored(snapshot.data()).workflow : null
  }

  async create(command: CreateDeletionCommand): Promise<DeletionWorkflow> {
    return this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.workflow.ownerID)
      const snapshot = await transaction.get(reference)
      if (snapshot.exists) {
        const existing = parseStored(snapshot.data())
        if (existing.workflow.status !== "CANCELLED") return existing.workflow
      }
      const stored = StoredDeletionSchema.parse({
        workflow: command.workflow,
        nextAttemptAt: command.workflow.scheduledAt,
        receiptExpiresAt: null,
      })
      transaction.set(reference, stored)
      return command.workflow
    })
  }

  async cancel(command: CancelDeletionCommand): Promise<DeletionWorkflow | null> {
    return this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.ownerID)
      const snapshot = await transaction.get(reference)
      if (!snapshot.exists) return null
      const stored = parseStored(snapshot.data())
      if (
        stored.workflow.requestID !== command.requestID ||
        stored.workflow.status !== "RECEIVED" ||
        command.nowSeconds >= stored.workflow.scheduledAt
      ) {
        return null
      }
      const workflow = DeletionWorkflowSchema.parse({
        ...stored.workflow,
        status: "CANCELLED",
      })
      transaction.set(reference, { workflow, nextAttemptAt: null, receiptExpiresAt: null })
      return workflow
    })
  }

  async claimDue(command: ClaimDueCommand): Promise<readonly DeletionWorkflow[]> {
    const due = await this.firestore
      .collection("accountDeletionRequests")
      .where("nextAttemptAt", "<=", command.nowSeconds)
      .limit(command.limit)
      .get()
    const claims = await Promise.all(due.docs.map((snapshot) => this.claim(snapshot.ref, command)))
    return claims.filter((workflow): workflow is DeletionWorkflow => workflow !== null)
  }

  async finish(command: FinishDeletionCommand): Promise<DeletionWorkflow> {
    return this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.ownerID)
      const snapshot = await transaction.get(reference)
      if (!snapshot.exists) {
        throw new DeletionPersistenceError("Claimed account deletion request is missing")
      }
      const stored = parseStored(snapshot.data())
      if (
        stored.workflow.requestID !== command.requestID ||
        stored.workflow.status !== "PROCESSING"
      ) {
        throw new DeletionPersistenceError("Account deletion claim no longer matches")
      }
      const status =
        command.failedCategories.length === 0
          ? "COMPLETED"
          : command.succeededCategories.length === 0
            ? "FAILED"
            : "PARTIALLY_FAILED"
      const workflow = DeletionWorkflowSchema.parse({
        ...stored.workflow,
        status,
        succeededCategories: command.succeededCategories,
        failedCategories: command.failedCategories,
      })
      transaction.set(reference, {
        workflow,
        nextAttemptAt: status === "COMPLETED" ? null : stored.nextAttemptAt,
        receiptExpiresAt:
          status === "COMPLETED"
            ? command.nowSeconds + RECEIPT_RETENTION_SECONDS
            : (stored.receiptExpiresAt ?? null),
      })
      return workflow
    })
  }

  async purgeExpiredCompleted(command: PurgeDeletionReceiptsCommand): Promise<number> {
    const expired = await this.firestore
      .collection("accountDeletionRequests")
      .where("receiptExpiresAt", "<=", command.nowSeconds)
      .limit(command.limit)
      .get()
    const purged = await Promise.all(
      expired.docs.map((document) =>
        this.firestore.runTransaction(async (transaction) => {
          const snapshot = await transaction.get(document.ref)
          if (!snapshot.exists) return false
          const stored = parseStored(snapshot.data())
          if (
            stored.workflow.status !== "COMPLETED" ||
            stored.receiptExpiresAt === undefined ||
            stored.receiptExpiresAt === null ||
            stored.receiptExpiresAt > command.nowSeconds
          ) {
            return false
          }
          transaction.delete(document.ref)
          return true
        }),
      ),
    )
    return purged.filter(Boolean).length
  }

  private reference(ownerID: OwnerId): DocumentReference {
    return this.firestore.doc(`accountDeletionRequests/${ownerID}`)
  }

  private async claim(
    reference: DocumentReference,
    command: ClaimDueCommand,
  ): Promise<DeletionWorkflow | null> {
    return this.firestore.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(reference)
      if (!snapshot.exists) return null
      const stored = parseStored(snapshot.data())
      if (
        stored.nextAttemptAt === null ||
        stored.nextAttemptAt > command.nowSeconds ||
        !isClaimable(stored.workflow.status, stored.nextAttemptAt <= command.nowSeconds)
      ) {
        return null
      }
      const workflow = DeletionWorkflowSchema.parse({
        ...stored.workflow,
        status: "PROCESSING",
        failedCategories: [],
      })
      transaction.set(reference, {
        workflow,
        nextAttemptAt: command.nowSeconds + command.leaseSeconds,
        receiptExpiresAt: stored.receiptExpiresAt ?? null,
      })
      return workflow
    })
  }
}
