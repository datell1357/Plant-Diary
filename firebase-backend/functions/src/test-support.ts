import type {
  AccountCleaner,
  CancelDeletionCommand,
  CleanupCategory,
  Clock,
  CreateDeletionCommand,
  DeletionRequestIds,
  DeletionStore,
  DeletionWorkflow,
  FinishDeletionCommand,
  OwnerId,
  PurgeDeletionReceiptsCommand,
} from "./deletion-contract.js"
import { RECEIPT_RETENTION_SECONDS } from "./deletion-contract.js"

export class FixedClock implements Clock {
  constructor(private readonly value: number) {}

  nowSeconds(): number {
    return this.value
  }
}

export class SequenceRequestIds implements DeletionRequestIds {
  private index = 0

  constructor(private readonly values: readonly string[]) {}

  next(): string {
    const value = this.values[this.index]
    if (value === undefined) throw new RangeError("No request ID fixture remains")
    this.index += 1
    return value
  }
}

/** Mutable in-memory persistence fake used to prove service state transitions. */
export class InMemoryDeletionStore implements DeletionStore {
  private readonly workflows = new Map<OwnerId, DeletionWorkflow>()
  private readonly pending = new Set<OwnerId>()
  private readonly leases = new Map<OwnerId, number>()
  private readonly receiptExpirations = new Map<OwnerId, number>()

  get requestCount(): number {
    return this.workflows.size
  }

  get pendingExecutionCount(): number {
    return this.pending.size
  }

  currentWorkflow(ownerID: OwnerId): DeletionWorkflow | null {
    return this.workflows.get(ownerID) ?? null
  }

  async load(ownerID: OwnerId): Promise<DeletionWorkflow | null> {
    return this.currentWorkflow(ownerID)
  }

  async create(command: CreateDeletionCommand): Promise<DeletionWorkflow> {
    const existing = this.workflows.get(command.workflow.ownerID)
    if (existing !== undefined && existing.status !== "CANCELLED") return existing
    this.workflows.set(command.workflow.ownerID, command.workflow)
    this.pending.add(command.workflow.ownerID)
    this.receiptExpirations.delete(command.workflow.ownerID)
    return command.workflow
  }

  async cancel(command: CancelDeletionCommand): Promise<DeletionWorkflow | null> {
    const workflow = this.workflows.get(command.ownerID)
    if (
      workflow === undefined ||
      workflow.requestID !== command.requestID ||
      workflow.status !== "RECEIVED" ||
      command.nowSeconds >= workflow.scheduledAt
    ) {
      return null
    }
    const cancelled = { ...workflow, status: "CANCELLED" as const }
    this.workflows.set(command.ownerID, cancelled)
    this.pending.delete(command.ownerID)
    return cancelled
  }

  async claimDue(command: {
    readonly nowSeconds: number
    readonly leaseSeconds: number
    readonly limit: number
  }): Promise<readonly DeletionWorkflow[]> {
    const claimed: DeletionWorkflow[] = []
    for (const ownerID of this.pending) {
      if (claimed.length >= command.limit) break
      const workflow = this.workflows.get(ownerID)
      const leaseUntil = this.leases.get(ownerID) ?? 0
      if (
        workflow === undefined ||
        workflow.scheduledAt > command.nowSeconds ||
        (workflow.status === "PROCESSING" && leaseUntil > command.nowSeconds)
      ) {
        continue
      }
      const processing = { ...workflow, status: "PROCESSING" as const }
      this.workflows.set(ownerID, processing)
      this.leases.set(ownerID, command.nowSeconds + command.leaseSeconds)
      claimed.push(processing)
    }
    return claimed
  }

  async finish(command: FinishDeletionCommand): Promise<DeletionWorkflow> {
    const current = this.workflows.get(command.ownerID)
    if (current === undefined || current.requestID !== command.requestID) {
      throw new Error("Missing claimed request")
    }
    const status =
      command.failedCategories.length === 0
        ? "COMPLETED"
        : command.succeededCategories.length === 0
          ? "FAILED"
          : "PARTIALLY_FAILED"
    const workflow: DeletionWorkflow = {
      ...current,
      status,
      succeededCategories: command.succeededCategories,
      failedCategories: command.failedCategories,
    }
    this.workflows.set(command.ownerID, workflow)
    this.leases.delete(command.ownerID)
    if (status === "COMPLETED") {
      this.pending.delete(command.ownerID)
      this.receiptExpirations.set(command.ownerID, command.nowSeconds + RECEIPT_RETENTION_SECONDS)
    }
    return workflow
  }

  async purgeExpiredCompleted(command: PurgeDeletionReceiptsCommand): Promise<number> {
    let purged = 0
    for (const [ownerID, expiresAt] of this.receiptExpirations) {
      if (purged >= command.limit) break
      if (expiresAt > command.nowSeconds) continue
      this.receiptExpirations.delete(ownerID)
      this.workflows.delete(ownerID)
      this.leases.delete(ownerID)
      this.pending.delete(ownerID)
      purged += 1
    }
    return purged
  }
}

/** Mutable cleanup fake; mutation records the observable contract order. */
export class InMemoryAccountCleaner implements AccountCleaner {
  readonly calls: CleanupCategory[] = []

  constructor(private readonly failures: ReadonlySet<CleanupCategory> = new Set()) {}

  async clean(ownerID: OwnerId, category: CleanupCategory): Promise<void> {
    void ownerID
    this.calls.push(category)
    if (this.failures.has(category)) throw new Error(`Fixture failure: ${category}`)
  }
}
