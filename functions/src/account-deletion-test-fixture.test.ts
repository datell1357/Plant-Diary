import { AccountDeletionError } from "./account-deletion-contract.js";
import type {
  AccountDeletionRecord,
  AccountDeletionStore,
  CancelDeletionCommand,
  ClaimDeletionCommand,
  FinishDeletionCommand,
  RequestDeletionCommand,
  RetryDeletionCommand,
} from "./account-deletion-contract.js";

/** Mutable transactional fake; mutation is the persistence behavior under test. */
export class MemoryAccountDeletionStore implements AccountDeletionStore {
  private readonly records = new Map<string, AccountDeletionRecord>();
  private readonly receipts = new Map<string, AccountDeletionRecord>();

  get size(): number {
    return this.records.size;
  }

  async load(ownerUid: string): Promise<AccountDeletionRecord | null> {
    return this.records.get(ownerUid) ?? null;
  }

  async request(command: RequestDeletionCommand): Promise<AccountDeletionRecord> {
    const receiptKey = this.receiptKey(
      command.record.ownerUid,
      "REQUEST",
      command.record.idempotencyKeyHash,
    );
    const receipt = this.receipts.get(receiptKey);
    if (receipt !== undefined) return receipt;
    const current = this.records.get(command.record.ownerUid);
    if (current !== undefined && !["FAILED", "CANCELLED"].includes(current.status)) {
      if (current.idempotencyKeyHash !== command.record.idempotencyKeyHash) {
        throw new AccountDeletionError(
          "failed-precondition",
          "An account deletion request is already active",
        );
      }
      return current;
    }
    this.records.set(command.record.ownerUid, command.record);
    this.receipts.set(receiptKey, command.record);
    return command.record;
  }

  async cancel(command: CancelDeletionCommand): Promise<AccountDeletionRecord | null> {
    const receiptKey = this.receiptKey(command.ownerUid, "CANCEL", command.requestId);
    const receipt = this.receipts.get(receiptKey);
    if (receipt !== undefined) return receipt;
    const current = this.records.get(command.ownerUid);
    if (
      current === undefined ||
      current.requestId !== command.requestId ||
      current.status !== "RECEIVED" ||
      current.nextAttemptAtMillis === null ||
      current.nextAttemptAtMillis <= command.nowMillis
    ) return null;
    const cancelled: AccountDeletionRecord = {
      ...current,
      status: "CANCELLED",
      nextAttemptAtMillis: null,
      leaseExpiresAtMillis: null,
      updatedAtMillis: command.nowMillis,
    };
    this.records.set(command.ownerUid, cancelled);
    this.receipts.set(receiptKey, cancelled);
    return cancelled;
  }

  async retry(command: RetryDeletionCommand): Promise<AccountDeletionRecord | null> {
    const receiptKey = this.receiptKey(
      command.ownerUid,
      "RETRY",
      command.idempotencyKeyHash,
    );
    const receipt = this.receipts.get(receiptKey);
    if (receipt !== undefined) return receipt;
    const current = this.records.get(command.ownerUid);
    if (current === undefined) return null;
    let retrying: AccountDeletionRecord;
    if (current.status === "FAILED") {
      retrying = {
        schemaVersion: 1,
        ownerUid: command.ownerUid,
        requestId: command.requestId,
        idempotencyKeyHash: command.idempotencyKeyHash,
        status: "RECEIVED",
        requestedAtMillis: command.nowMillis,
        scheduledForMillis: command.scheduledForMillis,
        nextAttemptAtMillis: command.scheduledForMillis,
        leaseExpiresAtMillis: null,
        completedAtMillis: null,
        completedScopes: [],
        failedScopes: [],
        claimGeneration: 0,
        updatedAtMillis: command.nowMillis,
      };
    } else if (current.status === "PARTIALLY_FAILED") {
      if (current.nextAttemptAtMillis !== null) {
        if (current.idempotencyKeyHash !== command.idempotencyKeyHash) {
          throw new AccountDeletionError(
            "failed-precondition",
            "A deletion retry is already active",
          );
        }
        return current;
      }
      retrying = {
        ...current,
        idempotencyKeyHash: command.idempotencyKeyHash,
        nextAttemptAtMillis: command.nowMillis,
        updatedAtMillis: command.nowMillis,
      };
    } else {
      return null;
    }
    this.records.set(command.ownerUid, retrying);
    this.receipts.set(receiptKey, retrying);
    return retrying;
  }

  async claimDue(command: ClaimDeletionCommand): Promise<readonly AccountDeletionRecord[]> {
    const claimed: AccountDeletionRecord[] = [];
    for (const [ownerUid, current] of this.records) {
      if (claimed.length >= command.limit) break;
      if (current.nextAttemptAtMillis === null || current.nextAttemptAtMillis > command.nowMillis) {
        continue;
      }
      const claimable =
        current.status === "RECEIVED" ||
        current.status === "PARTIALLY_FAILED" ||
        (current.status === "PROCESSING" &&
          current.leaseExpiresAtMillis !== null &&
          current.leaseExpiresAtMillis <= command.nowMillis);
      if (!claimable) continue;
      const processing: AccountDeletionRecord = {
        ...current,
        status: "PROCESSING",
        nextAttemptAtMillis: command.leaseExpiresAtMillis,
        leaseExpiresAtMillis: command.leaseExpiresAtMillis,
        failedScopes: [],
        claimGeneration: current.claimGeneration + 1,
        updatedAtMillis: command.nowMillis,
      };
      this.records.set(ownerUid, processing);
      claimed.push(processing);
    }
    return claimed;
  }

  async finish(command: FinishDeletionCommand): Promise<AccountDeletionRecord> {
    const current = this.records.get(command.ownerUid);
    if (
      current === undefined ||
      current.status !== "PROCESSING" ||
      current.requestId !== command.requestId ||
      current.claimGeneration !== command.claimGeneration
    ) throw new Error("Missing claimed deletion fixture");
    const status =
      command.failedScopes.length === 0
        ? "COMPLETED"
        : command.completedScopes.length === 0
          ? "FAILED"
          : "PARTIALLY_FAILED";
    const finished: AccountDeletionRecord = {
      ...current,
      status,
      completedScopes: command.completedScopes,
      failedScopes: command.failedScopes,
      nextAttemptAtMillis: null,
      leaseExpiresAtMillis: null,
      completedAtMillis: status === "COMPLETED" ? command.nowMillis : null,
      updatedAtMillis: command.nowMillis,
    };
    this.records.set(command.ownerUid, finished);
    return finished;
  }

  private receiptKey(ownerUid: string, kind: string, key: string): string {
    return `${ownerUid}:${kind}:${key}`;
  }
}
