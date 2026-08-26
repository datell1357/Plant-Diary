import { createHash } from "node:crypto";
import {
  FieldPath,
  FieldValue,
  Timestamp,
  type DocumentData,
  type DocumentReference,
  type Firestore,
  type Transaction,
} from "firebase-admin/firestore";
import {
  ACCOUNT_DELETION_TERMINAL_RETENTION_MILLIS,
  ACCOUNT_DELETION_TOMBSTONE_CLEANUP_LIMIT,
  AccountDeletionError,
  type AccountDeletionRecord,
  type AccountDeletionStatus,
  type AccountDeletionStore,
  type CancelDeletionCommand,
  type ClaimDeletionCommand,
  type FinishDeletionCommand,
  type RecordDeletionRequestAnalyticsCommand,
  type RequestDeletionCommand,
  type RetryDeletionCommand,
} from "./account-deletion-contract.js";
import {
  AccountDeletionPersistenceError,
  parseStoredAccountDeletion,
  storedReceipt,
  toStoredAccountDeletion,
} from "./account-deletion-persistence.js";
import { AccountDeletionReceipts } from "./account-deletion-receipts.js";
import type { AnalyticsEventName } from "./analytics.js";
import {
  analyticsDailyAggregateExpiresAt,
  hasActiveAnalyticsConsent,
} from "./firestore-analytics-store.js";

export { AccountDeletionPersistenceError } from "./account-deletion-persistence.js";

const TERMINAL_RECEIPT_DELETE_LIMIT = 400;
type AccountDeletionResultEvent = Extract<
  AnalyticsEventName,
  "ACCOUNT_DELETION_COMPLETED" | "ACCOUNT_DELETION_FAILED"
>;

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
      const terminalTombstone = this.terminalTombstoneReference(
        command.record.ownerUid,
        "REQUEST",
        command.record.idempotencyKeyHash,
      );
      const consentReference = this.firestore.doc(
        `users/${command.record.ownerUid}/consents/analytics`,
      );
      const [receiptSnapshot, snapshot, tombstoneSnapshot, consentSnapshot] = await Promise.all([
        transaction.get(receipt),
        transaction.get(reference),
        transaction.get(terminalTombstone),
        transaction.get(consentReference),
      ]);
      if (tombstoneSnapshot.exists) {
        throw new AccountDeletionError(
          "failed-precondition",
          "This account deletion command already reached a terminal state",
        );
      }
      const replay = this.receipts.parse(
        receiptSnapshot,
        command.record.ownerUid,
        "REQUEST",
        command.record.idempotencyKeyHash,
      );
      if (replay !== null) {
        if (snapshot.exists) {
          const current = parseStoredAccountDeletion(snapshot.data());
          if (
            current.requestId === replay.requestId &&
            current.idempotencyKeyHash === replay.idempotencyKeyHash
          ) return current;
        }
        return replay;
      }
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
      const analyticsResultEligible = hasActiveAnalyticsConsent(consentSnapshot);
      const created: AccountDeletionRecord = {
        ...command.record,
        analyticsResultEligible,
        analyticsRequestOutcome: analyticsResultEligible ? "PENDING" : "CONSENT_OFF",
      };
      transaction.set(reference, toStoredAccountDeletion(created));
      this.receipts.create(
        transaction,
        receipt,
        "REQUEST",
        created,
        created.updatedAtMillis,
      );
      return created;
    });
  }

  async recordRequestAnalyticsOutcome(
    command: RecordDeletionRequestAnalyticsCommand,
  ): Promise<AccountDeletionRecord> {
    return this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.ownerUid);
      const snapshot = await transaction.get(reference);
      if (!snapshot.exists) {
        throw new AccountDeletionPersistenceError("Deletion request is missing");
      }
      const current = parseStoredAccountDeletion(snapshot.data());
      if (current.requestId !== command.requestId) {
        throw new AccountDeletionPersistenceError("Deletion request no longer matches");
      }
      if (!current.analyticsResultEligible || current.analyticsRequestOutcome !== "PENDING") {
        return current;
      }
      const recorded: AccountDeletionRecord = {
        ...current,
        analyticsRequestOutcome: command.outcome,
        updatedAtMillis: Math.max(current.updatedAtMillis, command.nowMillis),
      };
      transaction.set(reference, toStoredAccountDeletion(recorded));
      transaction.set(
        this.receipts.reference(command.ownerUid, "REQUEST", current.idempotencyKeyHash),
        storedReceipt(
          command.ownerUid,
          "REQUEST",
          current.idempotencyKeyHash,
          recorded,
          current.requestedAtMillis,
        ),
      );
      return recorded;
    });
  }

  async cancel(command: CancelDeletionCommand): Promise<AccountDeletionRecord | null> {
    return this.firestore.runTransaction(async (transaction) => {
      const reference = this.reference(command.ownerUid);
      const cancelTombstone = this.terminalTombstoneReference(
        command.ownerUid,
        "CANCEL",
        command.requestId,
      );
      const [tombstoneSnapshot, snapshot] = await Promise.all([
        transaction.get(cancelTombstone),
        transaction.get(reference),
      ]);
      if (tombstoneSnapshot.exists) {
        const terminalAtMillis = terminalTombstoneMillis(
          tombstoneSnapshot.data(),
          "CANCEL",
          command.requestId,
        );
        return cancelledReplay(command.ownerUid, command.requestId, terminalAtMillis);
      }
      if (!snapshot.exists) return null;
      const current = parseStoredAccountDeletion(snapshot.data());
      if (
        current.requestId !== command.requestId ||
        current.status !== "RECEIVED" ||
        current.nextAttemptAtMillis === null ||
        current.nextAttemptAtMillis <= command.nowMillis
      ) return null;
      const requestReceipt = this.receipts.reference(
        command.ownerUid,
        "REQUEST",
        current.idempotencyKeyHash,
      );
      const requestTombstone = this.terminalTombstoneReference(
        command.ownerUid,
        "REQUEST",
        current.idempotencyKeyHash,
      );
      const cancelled: AccountDeletionRecord = {
        ...current,
        status: "CANCELLED",
        nextAttemptAtMillis: null,
        leaseExpiresAtMillis: null,
        updatedAtMillis: command.nowMillis,
      };
      transaction.delete(requestReceipt);
      transaction.delete(reference);
      transaction.create(
        requestTombstone,
        terminalTombstone("REQUEST", current.idempotencyKeyHash, command.nowMillis),
      );
      transaction.create(
        cancelTombstone,
        terminalTombstone("CANCEL", command.requestId, command.nowMillis),
      );
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
          claimGeneration: current.claimGeneration,
          analyticsResultEligible: current.analyticsResultEligible,
          analyticsRequestOutcome: current.analyticsRequestOutcome,
          analyticsRecordedResultKeys: current.analyticsRecordedResultKeys,
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
      if (status === "COMPLETED") {
        const receipts = await transaction.get(
          this.firestore
            .collection(`accountDeletionReceipts/${command.ownerUid}/commands`)
            .orderBy(FieldPath.documentId(), "asc")
            .limit(TERMINAL_RECEIPT_DELETE_LIMIT),
        );
        for (const receipt of receipts.docs) transaction.delete(receipt.ref);
        if (receipts.size === TERMINAL_RECEIPT_DELETE_LIMIT) {
          const draining: AccountDeletionRecord = {
            ...current,
            completedScopes: command.completedScopes,
            failedScopes: [],
            nextAttemptAtMillis: command.nowMillis,
            leaseExpiresAtMillis: command.nowMillis,
            updatedAtMillis: command.nowMillis,
          };
          transaction.set(reference, toStoredAccountDeletion(draining));
          return draining;
        }
        const completed = this.recordOwnerlessResult(
          transaction,
          finished,
          "ACCOUNT_DELETION_COMPLETED",
          "ACCOUNT_DELETION_COMPLETED",
          command.nowMillis,
        );
        transaction.delete(reference);
        return completed;
      }
      const failed = this.recordOwnerlessResult(
        transaction,
        finished,
        "ACCOUNT_DELETION_FAILED",
        `ACCOUNT_DELETION_FAILED:${command.claimGeneration}`,
        command.nowMillis,
      );
      transaction.set(reference, toStoredAccountDeletion(failed));
      return failed;
    });
  }

  private recordOwnerlessResult(
    transaction: Transaction,
    record: AccountDeletionRecord,
    eventName: AccountDeletionResultEvent,
    resultKey: string,
    nowMillis: number,
  ): AccountDeletionRecord {
    if (
      !record.analyticsResultEligible ||
      record.analyticsRecordedResultKeys.includes(resultKey)
    ) return record;
    const date = utcDate(nowMillis);
    transaction.set(
      this.firestore.doc(`analyticsDailyAggregates/${date}`),
      {
        schemaVersion: 1,
        date,
        counts: { [eventName]: FieldValue.increment(1) },
        updatedAt: Timestamp.fromMillis(nowMillis),
        expiresAt: analyticsDailyAggregateExpiresAt(date),
      },
      { merge: true },
    );
    return {
      ...record,
      analyticsRecordedResultKeys: [...record.analyticsRecordedResultKeys, resultKey],
    };
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

  private terminalTombstoneReference(
    ownerUid: string,
    commandKind: "REQUEST" | "CANCEL" | "RETRY",
    commandKey: string,
  ): DocumentReference {
    return this.firestore.doc(
      `users/${ownerUid}/accountDeletionTombstones/${terminalTombstoneId(commandKind, commandKey)}`,
    );
  }

  private reference(ownerUid: string): DocumentReference {
    return this.firestore.doc(`accountDeletionRequests/${ownerUid}`);
  }
}

export type AccountDeletionTombstoneCleanupFailure = Readonly<{
  path: string;
  error: unknown;
}>;

export type AccountDeletionTombstoneCleanupResult = Readonly<{
  scanned: number;
  deleted: number;
  failures: readonly AccountDeletionTombstoneCleanupFailure[];
}>;

export async function cleanupExpiredAccountDeletionTombstones(
  firestore: Firestore,
  now: Timestamp = Timestamp.now(),
  limit = ACCOUNT_DELETION_TOMBSTONE_CLEANUP_LIMIT,
): Promise<AccountDeletionTombstoneCleanupResult> {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 500) {
    throw new TypeError("Account deletion tombstone cleanup limit is invalid");
  }
  const expired = await firestore
    .collectionGroup("accountDeletionTombstones")
    .where("expiresAt", "<=", now)
    .orderBy("expiresAt", "asc")
    .orderBy(FieldPath.documentId(), "asc")
    .limit(limit)
    .get();
  const outcomes = await Promise.allSettled(
    expired.docs.map((document) => document.ref.delete()),
  );
  const failures: AccountDeletionTombstoneCleanupFailure[] = [];
  let deleted = 0;
  outcomes.forEach((outcome, index) => {
    if (outcome.status === "fulfilled") {
      deleted += 1;
    } else {
      failures.push({ path: expired.docs[index]!.ref.path, error: outcome.reason });
    }
  });
  return { scanned: expired.size, deleted, failures };
}

function terminalTombstone(
  commandKind: "REQUEST" | "CANCEL" | "RETRY",
  commandKey: string,
  terminalAtMillis: number,
): Readonly<Record<string, unknown>> {
  return {
    schemaVersion: 1,
    commandKind,
    commandKeyHash: commandKeyHash(commandKind, commandKey),
    terminalStatus: "CANCELLED",
    terminalAt: Timestamp.fromMillis(terminalAtMillis),
    expiresAt: Timestamp.fromMillis(
      terminalAtMillis + ACCOUNT_DELETION_TERMINAL_RETENTION_MILLIS,
    ),
  };
}

function terminalTombstoneMillis(
  value: DocumentData | undefined,
  commandKind: "REQUEST" | "CANCEL" | "RETRY",
  commandKey: string,
): number {
  const terminalAt = value?.terminalAt;
  const expiresAt = value?.expiresAt;
  if (
    value?.schemaVersion !== 1 ||
    value.commandKind !== commandKind ||
    value.commandKeyHash !== commandKeyHash(commandKind, commandKey) ||
    value.terminalStatus !== "CANCELLED" ||
    !(terminalAt instanceof Timestamp) ||
    !(expiresAt instanceof Timestamp) ||
    expiresAt.toMillis() !== terminalAt.toMillis() + ACCOUNT_DELETION_TERMINAL_RETENTION_MILLIS
  ) {
    throw new AccountDeletionPersistenceError("Deletion tombstone is malformed");
  }
  return terminalAt.toMillis();
}

function terminalTombstoneId(
  commandKind: "REQUEST" | "CANCEL" | "RETRY",
  commandKey: string,
): string {
  return commandKeyHash(commandKind, commandKey);
}

function commandKeyHash(commandKind: string, commandKey: string): string {
  return createHash("sha256")
    .update(commandKind, "utf8")
    .update("\0", "utf8")
    .update(commandKey, "utf8")
    .digest("hex");
}

function utcDate(nowMillis: number): string {
  return new Date(nowMillis).toISOString().slice(0, 10);
}

function cancelledReplay(
  ownerUid: string,
  requestId: string,
  terminalAtMillis: number,
): AccountDeletionRecord {
  return {
    schemaVersion: 1,
    ownerUid,
    requestId,
    idempotencyKeyHash: "0".repeat(64),
    status: "CANCELLED",
    requestedAtMillis: terminalAtMillis,
    scheduledForMillis: terminalAtMillis,
    nextAttemptAtMillis: null,
    leaseExpiresAtMillis: null,
    completedAtMillis: null,
    completedScopes: [],
    failedScopes: [],
    claimGeneration: 0,
    analyticsResultEligible: false,
    analyticsRequestOutcome: "CONSENT_OFF",
    analyticsRecordedResultKeys: [],
    updatedAtMillis: terminalAtMillis,
  };
}
