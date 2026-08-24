import {
  Timestamp,
  type DocumentReference,
  type Firestore,
} from "firebase-admin/firestore";
import {
  AccountDeletionError,
  type AccountDeletionRecord,
  type AccountDeletionStatus,
  type AccountDeletionStore,
  type CancelDeletionCommand,
  type ClaimDeletionCommand,
  type FinishDeletionCommand,
  type RequestDeletionCommand,
  type RetryDeletionCommand,
} from "./account-deletion-contract.js";
import {
  AccountDeletionPersistenceError,
  parseStoredAccountDeletion,
  toStoredAccountDeletion,
} from "./account-deletion-persistence.js";
import { AccountDeletionReceipts } from "./account-deletion-receipts.js";

export { AccountDeletionPersistenceError } from "./account-deletion-persistence.js";

function claimable(record: AccountDeletionRecord, nowMillis: number): boolean {
  if (record.status === "RECEIVED" || record.status === "PARTIALLY_FAILED") return true;
  return record.status === "PROCESSING" &&
    record.leaseExpiresAtMillis !== null &&
    record.leaseExpiresAtMillis <= nowMillis;
}

export class FirestoreAccountDeletionStore implements AccountDeletionStore {
  private readonly receipts: AccountDeletionReceipts;

  constructor(private readonly firestore: Firestore) {
    this.receipts = new AccountDeletionReceipts(firestore);
  }

  async load(ownerUid: string): Promise<AccountDeletionRecord | null> {
    const snapshot = await this.reference(ownerUid).get();
    return snapshot.exists ? parseStoredAccountDeletion(snapshot.data()) : null;
  }

  async request(command: RequestDeletionCommand): Promise<AccountDeletionRecord> {
    return this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.record.ownerUid);
      const receipt = this.receipts.reference(
        command.record.ownerUid,
        "REQUEST",
        command.record.idempotencyKeyHash,
      );
      const [receiptSnapshot, snapshot] = await Promise.all([
        transaction.get(receipt),
        transaction.get(reference),
      ]);
      const replay = this.receipts.parse(
        receiptSnapshot,
        command.record.ownerUid,
        "REQUEST",
        command.record.idempotencyKeyHash,
      );
      if (replay !== null) return replay;
      if (snapshot.exists) {
        const current = parseStoredAccountDeletion(snapshot.data());
        if (current.status !== "FAILED" && current.status !== "CANCELLED") {
          if (current.idempotencyKeyHash !== command.record.idempotencyKeyHash) {
            throw new AccountDeletionError(
              "failed-precondition",
              "An account deletion request is already active",
            );
          }
          return current;
        }
      }
      transaction.set(reference, toStoredAccountDeletion(command.record));
      this.receipts.create(
        transaction,
        receipt,
        "REQUEST",
        command.record,
        command.record.updatedAtMillis,
      );
      return command.record;
    });
  }

  async cancel(command: CancelDeletionCommand): Promise<AccountDeletionRecord | null> {
    return this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.ownerUid);
      const receipt = this.receipts.reference(command.ownerUid, "CANCEL", command.requestId);
      const [receiptSnapshot, snapshot] = await Promise.all([
        transaction.get(receipt),
        transaction.get(reference),
      ]);
      const replay = this.receipts.parse(
        receiptSnapshot,
        command.ownerUid,
        "CANCEL",
        command.requestId,
      );
      if (replay !== null) return replay;
      if (!snapshot.exists) return null;
      const current = parseStoredAccountDeletion(snapshot.data());
      if (
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
      transaction.set(reference, toStoredAccountDeletion(cancelled));
      this.receipts.create(transaction, receipt, "CANCEL", cancelled, command.nowMillis);
      return cancelled;
    });
  }

  async retry(command: RetryDeletionCommand): Promise<AccountDeletionRecord | null> {
    return this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.ownerUid);
      const receipt = this.receipts.reference(
        command.ownerUid,
        "RETRY",
        command.idempotencyKeyHash,
      );
      const [receiptSnapshot, snapshot] = await Promise.all([
        transaction.get(receipt),
        transaction.get(reference),
      ]);
      const replay = this.receipts.parse(
        receiptSnapshot,
        command.ownerUid,
        "RETRY",
        command.idempotencyKeyHash,
      );
      if (replay !== null) return replay;
      if (!snapshot.exists) return null;
      const current = parseStoredAccountDeletion(snapshot.data());
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
      transaction.set(reference, toStoredAccountDeletion(retrying));
      this.receipts.create(transaction, receipt, "RETRY", retrying, command.nowMillis);
      return retrying;
    });
  }

  async claimDue(command: ClaimDeletionCommand): Promise<readonly AccountDeletionRecord[]> {
    const due = await this.firestore
      .collection("accountDeletionRequests")
      .where("nextAttemptAt", "<=", Timestamp.fromMillis(command.nowMillis))
      .limit(command.limit)
      .get();
    const claims = await Promise.all(
      due.docs.map((document) => this.claim(document.ref, command)),
    );
    return claims.filter((record): record is AccountDeletionRecord => record !== null);
  }

  async finish(command: FinishDeletionCommand): Promise<AccountDeletionRecord> {
    return this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.ownerUid);
      const snapshot = await transaction.get(reference);
      if (!snapshot.exists) throw new AccountDeletionPersistenceError("Deletion claim is missing");
      const current = parseStoredAccountDeletion(snapshot.data());
      if (
        current.status !== "PROCESSING" ||
        current.requestId !== command.requestId ||
        current.claimGeneration !== command.claimGeneration
      ) throw new AccountDeletionPersistenceError("Deletion claim no longer matches");
      const status: AccountDeletionStatus =
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
      transaction.set(reference, toStoredAccountDeletion(finished));
      return finished;
    });
  }

  private async claim(
    reference: DocumentReference,
    command: ClaimDeletionCommand,
  ): Promise<AccountDeletionRecord | null> {
    return this.firestore.runTransaction(async (transaction) => {
      const snapshot = await transaction.get(reference);
      if (!snapshot.exists) return null;
      const current = parseStoredAccountDeletion(snapshot.data());
      if (
        current.nextAttemptAtMillis === null ||
        current.nextAttemptAtMillis > command.nowMillis ||
        !claimable(current, command.nowMillis)
      ) return null;
      const processing: AccountDeletionRecord = {
        ...current,
        status: "PROCESSING",
        nextAttemptAtMillis: command.leaseExpiresAtMillis,
        leaseExpiresAtMillis: command.leaseExpiresAtMillis,
        failedScopes: [],
        claimGeneration: current.claimGeneration + 1,
        updatedAtMillis: command.nowMillis,
      };
      transaction.set(reference, toStoredAccountDeletion(processing));
      return processing;
    });
  }

  private reference(ownerUid: string): DocumentReference {
    return this.firestore.doc(`accountDeletionRequests/${ownerUid}`);
  }

}
