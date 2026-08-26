import {
  FieldPath,
  Timestamp,
  type DocumentSnapshot,
  type Firestore,
  type QueryDocumentSnapshot,
} from "firebase-admin/firestore";
import {
  isAccountMutationLocked,
  runAccountMutationTransaction,
} from "./account-mutation-lock.js";
import {
  ANALYTICS_EVENT_TTL_MILLIS,
  AnalyticsError,
  type AnalyticsConsentMutation,
  type AnalyticsConsentMutationResult,
  type AnalyticsConsentView,
  type AnalyticsEventBatchResult,
  type AnalyticsEventBatchWrite,
  type AnalyticsEventInput,
  type AnalyticsEventWriteResult,
  type AnalyticsStore,
  parseServerAnalyticsEvent,
} from "./analytics.js";

export const ANALYTICS_REVOKE_PURGE_LIMIT = 200;
export const ANALYTICS_CLEANUP_LIMIT = 200;
export const ANALYTICS_AGGREGATE_RETENTION_MILLIS = ANALYTICS_EVENT_TTL_MILLIS;
export const ANALYTICS_REVOKE_PURGE_DEADLINE_MILLIS = 45_000;

export type FirestoreAnalyticsStoreHooks = Readonly<{
  beforeConsentMutation?: (command: AnalyticsConsentMutation) => Promise<void>;
  afterConsentStateRead?: (command: AnalyticsConsentMutation) => Promise<void>;
  beforeDeletionRevokeTransaction?: () => Promise<void>;
  afterEventConsentRead?: () => Promise<void>;
}>;

export type ServerAnalyticsEventCommand = AnalyticsEventInput &
  Readonly<{ ownerUid: string }>;

type StoredConsent = Readonly<{
  schemaVersion: 1;
  granted: boolean;
  commandGeneration: number;
  operationId: string;
  grantedAt: Timestamp | null;
  revokedAt: Timestamp | null;
  updatedAt: Timestamp;
}>;

type StoredConsentReceipt = Readonly<{
  schemaVersion: 1;
  ownerUid: string;
  granted: boolean;
  commandGeneration: number;
  operationId: string;
  grantedAt: Timestamp | null;
  revokedAt: Timestamp | null;
  createdAt: Timestamp;
}>;

type StoredEvent = Readonly<{
  schemaVersion: 1;
  eventName: AnalyticsEventInput["eventName"];
  consentRevision: number;
  occurredAt: Timestamp;
  expiresAt: Timestamp;
}>;

type BatchPersistenceResult = Readonly<{
  response: AnalyticsEventBatchResult;
  storedEvents: ReadonlyMap<string, StoredEvent>;
}>;

export class FirestoreAnalyticsStore implements AnalyticsStore {
  constructor(
    private readonly firestore: Firestore,
    private readonly now: () => Timestamp = Timestamp.now,
    private readonly hooks: FirestoreAnalyticsStoreHooks = {},
    private readonly wallClockMillis: () => number = Date.now,
  ) {}

  async getConsent(ownerUid: string): Promise<AnalyticsConsentView> {
    const snapshot = await this.firestore.doc(consentPath(ownerUid)).get();
    return analyticsConsentFromSnapshot(snapshot);
  }

  async setConsent(
    command: AnalyticsConsentMutation,
  ): Promise<AnalyticsConsentMutationResult> {
    await this.hooks.beforeConsentMutation?.(command);
    const transactionResult = await runAccountMutationTransaction(
      this.firestore,
      command.ownerUid,
      async (transaction) => {
        const consentReference = this.firestore.doc(
          consentPath(command.ownerUid),
        );
        const receiptReference = this.firestore.doc(
          `users/${command.ownerUid}/analyticsConsentOperations/${command.operationId}`,
        );
        const [receiptSnapshot, consentSnapshot] = await Promise.all([
          transaction.get(receiptReference),
          transaction.get(consentReference),
        ]);
        await this.hooks.afterConsentStateRead?.(command);
        if (receiptSnapshot.exists) {
          const receipt = parseStoredReceipt(receiptSnapshot);
          if (
            receipt.ownerUid !== command.ownerUid ||
            receipt.operationId !== command.operationId ||
            receipt.granted !== command.granted ||
            receipt.commandGeneration !== command.commandGeneration
          ) {
            throw new AnalyticsError(
              "already-exists",
              "Analytics consent operation id was already used for a different command",
            );
          }
          return { receipt, replayed: true };
        }

        const current = consentSnapshot.exists
          ? parseStoredConsent(consentSnapshot)
          : null;
        const currentGeneration = current?.commandGeneration ?? 0;
        if (
          currentGeneration >= Number.MAX_SAFE_INTEGER ||
          command.commandGeneration !== currentGeneration + 1
        ) {
          throw new AnalyticsError(
            "aborted",
            "Analytics consent generation changed; reload required",
          );
        }
        const at = this.now();
        const grantedAt = command.granted ? at : (current?.grantedAt ?? null);
        const revokedAt = command.granted ? null : at;
        const consent: StoredConsent = {
          schemaVersion: 1,
          granted: command.granted,
          commandGeneration: command.commandGeneration,
          operationId: command.operationId,
          grantedAt,
          revokedAt,
          updatedAt: at,
        };
        const receipt: StoredConsentReceipt = {
          schemaVersion: 1,
          ownerUid: command.ownerUid,
          granted: command.granted,
          commandGeneration: command.commandGeneration,
          operationId: command.operationId,
          grantedAt,
          revokedAt,
          createdAt: at,
        };
        transaction.create(receiptReference, receipt);
        transaction.set(consentReference, consent, { merge: false });
        return { receipt, replayed: false };
      },
      { maxAttempts: 5 },
    );

    const purgeDeadlineMillis =
      this.wallClockMillis() + ANALYTICS_REVOKE_PURGE_DEADLINE_MILLIS;
    const purgedEventCount = transactionResult.receipt.granted
      ? 0
      : await this.purgeRevokedEvents(
          command.ownerUid,
          transactionResult.receipt.commandGeneration,
          purgeDeadlineMillis,
        );
    return {
      ...receiptView(transactionResult.receipt),
      replayed: transactionResult.replayed,
      purgedEventCount,
    };
  }

  async recordEvents(
    command: AnalyticsEventBatchWrite,
  ): Promise<AnalyticsEventBatchResult> {
    return (await this.recordBatch(command.ownerUid, command.events)).response;
  }

  async revokeForAccountDeletion(
    ownerUid: string,
    operationId: string,
  ): Promise<Readonly<{ purgedEventCount: number }>> {
    await this.hooks.beforeDeletionRevokeTransaction?.();
    const receipt = await this.firestore.runTransaction(async (transaction) => {
      const deletionReference = this.firestore.doc(
        `accountDeletionRequests/${ownerUid}`,
      );
      const consentReference = this.firestore.doc(consentPath(ownerUid));
      const receiptReference = this.firestore.doc(
        `users/${ownerUid}/analyticsConsentOperations/${operationId}`,
      );
      const [deletionSnapshot, consentSnapshot, receiptSnapshot] =
        await Promise.all([
          transaction.get(deletionReference),
          transaction.get(consentReference),
          transaction.get(receiptReference),
        ]);
      if (
        !deletionSnapshot.exists ||
        !isAccountMutationLocked(
          deletionSnapshot.get("status"),
          deletionSnapshot.get("completedScopes"),
        )
      ) {
        throw new AnalyticsError(
          "failed-precondition",
          "Accepted account deletion is required for the system revoke",
        );
      }
      if (receiptSnapshot.exists) {
        const replay = parseStoredReceipt(receiptSnapshot);
        if (
          replay.ownerUid !== ownerUid ||
          replay.operationId !== operationId ||
          replay.granted
        ) {
          throw new AnalyticsError(
            "already-exists",
            "Analytics consent operation id was already used for a different command",
          );
        }
        return replay;
      }

      const current = consentSnapshot.exists
        ? parseStoredConsent(consentSnapshot)
        : null;
      const currentGeneration = current?.commandGeneration ?? 0;
      const commandGeneration = Math.min(
        currentGeneration + 1,
        Number.MAX_SAFE_INTEGER,
      );
      const at = this.now();
      const revoked: StoredConsent = {
        schemaVersion: 1,
        granted: false,
        commandGeneration,
        operationId,
        grantedAt: current?.grantedAt ?? null,
        revokedAt: at,
        updatedAt: at,
      };
      const created: StoredConsentReceipt = {
        schemaVersion: 1,
        ownerUid,
        granted: false,
        commandGeneration,
        operationId,
        grantedAt: revoked.grantedAt,
        revokedAt: at,
        createdAt: at,
      };
      transaction.create(receiptReference, created);
      transaction.set(consentReference, revoked, { merge: false });
      return created;
    });
    const purgedEventCount = await this.purgeRevokedEvents(
      ownerUid,
      receipt.commandGeneration,
      this.wallClockMillis() + ANALYTICS_REVOKE_PURGE_DEADLINE_MILLIS,
    );
    return { purgedEventCount };
  }

  async recordServerEvent(
    command: ServerAnalyticsEventCommand,
  ): Promise<AnalyticsEventWriteResult> {
    const result = await this.recordBatch(command.ownerUid, [command]);
    const response = result.response.results[0];
    const stored = result.storedEvents.get(command.eventId);
    if (response === undefined || stored === undefined) {
      throw new Error("Analytics single-event transaction returned no result");
    }
    return {
      eventId: command.eventId,
      accepted: true,
      replayed: response.duplicate,
      occurredAtEpochMillis: stored.occurredAt.toMillis(),
      expiresAtEpochMillis: stored.expiresAt.toMillis(),
    };
  }

  private async recordBatch(
    ownerUid: string,
    events: readonly AnalyticsEventInput[],
  ): Promise<BatchPersistenceResult> {
    const uniqueEvents = uniqueBatchEvents(events);
    return runAccountMutationTransaction(
      this.firestore,
      ownerUid,
      async (transaction) => {
        const consentReference = this.firestore.doc(consentPath(ownerUid));
        const eventReferences = uniqueEvents.map((event) =>
          this.firestore.doc(
            `users/${ownerUid}/analyticsEvents/${event.eventId}`,
          ),
        );
        const snapshots = await transaction.getAll(
          consentReference,
          ...eventReferences,
        );
        const consentSnapshot = snapshots[0];
        if (consentSnapshot === undefined) {
          throw new Error("Analytics consent transaction read was unavailable");
        }
        await this.hooks.afterEventConsentRead?.();
        if (!consentSnapshot.exists) {
          throw new AnalyticsError(
            "failed-precondition",
            "Analytics consent is not granted",
          );
        }
        const consent = parseStoredConsent(consentSnapshot);
        if (!consent.granted) {
          throw new AnalyticsError(
            "failed-precondition",
            "Analytics consent is not granted",
          );
        }
        if (
          events.some(
            (event) => event.consentRevision !== consent.commandGeneration,
          )
        ) {
          throw new AnalyticsError(
            "failed-precondition",
            "Analytics consent generation is stale",
          );
        }

        const existingIds = new Set<string>();
        const storedEvents = new Map<string, StoredEvent>();
        for (const [index, reference] of eventReferences.entries()) {
          const snapshot = snapshots[index + 1];
          if (snapshot === undefined) {
            throw new Error("Analytics event transaction read was unavailable");
          }
          const requested = uniqueEvents[index];
          if (requested === undefined) {
            throw new Error(
              "Analytics event transaction request was unavailable",
            );
          }
          if (!snapshot.exists) continue;
          const existing = parseStoredEvent(snapshot);
          if (!sameEvent(existing, requested)) {
            throw new AnalyticsError(
              "already-exists",
              "Analytics event id was already used for a different event",
            );
          }
          existingIds.add(requested.eventId);
          storedEvents.set(requested.eventId, existing);
        }

        const occurredAt = this.now();
        for (const [index, event] of uniqueEvents.entries()) {
          if (existingIds.has(event.eventId)) continue;
          const reference = eventReferences[index];
          if (reference === undefined) {
            throw new Error(
              "Analytics event transaction reference was unavailable",
            );
          }
          const stored = storedEvent(event, occurredAt);
          transaction.create(reference, stored);
          storedEvents.set(event.eventId, stored);
        }

        const seenInRequest = new Set<string>();
        return {
          response: {
            results: events.map((event) => {
              const duplicate =
                existingIds.has(event.eventId) ||
                seenInRequest.has(event.eventId);
              seenInRequest.add(event.eventId);
              return { eventId: event.eventId, accepted: true, duplicate };
            }),
          },
          storedEvents,
        };
      },
      { maxAttempts: 5 },
    );
  }

  private async purgeRevokedEvents(
    ownerUid: string,
    revokedGeneration: number,
    deadlineMillis: number,
  ): Promise<number> {
    let deletedCount = 0;
    while (true) {
      if (this.wallClockMillis() >= deadlineMillis) {
        throw new AnalyticsError(
          "deadline-exceeded",
          "Analytics event purge is incomplete; retry the exact revoke command",
        );
      }
      const snapshot = await this.firestore
        .collection(`users/${ownerUid}/analyticsEvents`)
        .where("consentRevision", "<=", revokedGeneration)
        .orderBy("consentRevision", "asc")
        .orderBy(FieldPath.documentId(), "asc")
        .limit(ANALYTICS_REVOKE_PURGE_LIMIT)
        .get();
      if (snapshot.empty) return deletedCount;
      const batch = this.firestore.batch();
      for (const document of snapshot.docs) batch.delete(document.ref);
      await batch.commit();
      deletedCount += snapshot.size;
    }
  }
}

export async function recordServerAnalyticsEvent(
  firestore: Firestore,
  command: ServerAnalyticsEventCommand,
  now: () => Timestamp = Timestamp.now,
): Promise<AnalyticsEventWriteResult> {
  if (
    typeof command.ownerUid !== "string" ||
    command.ownerUid.length < 1 ||
    command.ownerUid.length > 128
  ) {
    throw new AnalyticsError(
      "invalid-argument",
      "Analytics event owner is invalid",
    );
  }
  const event = parseServerAnalyticsEvent({
    schemaVersion: command.schemaVersion,
    eventId: command.eventId,
    eventName: command.eventName,
    consentRevision: command.consentRevision,
  });
  return new FirestoreAnalyticsStore(firestore, now).recordServerEvent({
    ownerUid: command.ownerUid,
    ...event,
  });
}

export type AnalyticsAggregateCleanupResult = Readonly<{
  scanned: number;
  deleted: number;
  skippedMalformed: number;
}>;

export type AnalyticsRetentionCleanupResult = Readonly<{
  eventsDeleted: number;
  aggregates: AnalyticsAggregateCleanupResult;
}>;

export function analyticsDailyAggregateExpiresAt(date: string): Timestamp {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    throw new TypeError("Analytics aggregate UTC bucket date is invalid");
  }
  const bucketStartMillis = Date.parse(`${date}T00:00:00.000Z`);
  if (
    !Number.isFinite(bucketStartMillis) ||
    new Date(bucketStartMillis).toISOString().slice(0, 10) !== date
  ) {
    throw new TypeError("Analytics aggregate UTC bucket date is invalid");
  }
  return Timestamp.fromMillis(
    bucketStartMillis + ANALYTICS_AGGREGATE_RETENTION_MILLIS,
  );
}

export async function cleanupExpiredAnalyticsDailyAggregates(
  firestore: Firestore,
  now: Timestamp = Timestamp.now(),
): Promise<AnalyticsAggregateCleanupResult> {
  const expired = await firestore
    .collection("analyticsDailyAggregates")
    .where("expiresAt", "<=", now)
    .orderBy("expiresAt", "asc")
    .orderBy(FieldPath.documentId(), "asc")
    .limit(ANALYTICS_CLEANUP_LIMIT)
    .get();
  const deletable = expired.docs.filter(validAnalyticsDailyAggregate);
  if (deletable.length > 0) {
    const batch = firestore.batch();
    for (const document of deletable) batch.delete(document.ref);
    await batch.commit();
  }
  return {
    scanned: expired.size,
    deleted: deletable.length,
    skippedMalformed: expired.size - deletable.length,
  };
}

export async function cleanupExpiredAnalyticsRetention(
  firestore: Firestore,
  now: Timestamp = Timestamp.now(),
): Promise<AnalyticsRetentionCleanupResult> {
  const [eventsDeleted, aggregates] = await Promise.all([
    cleanupExpiredAnalyticsEvents(firestore, now),
    cleanupExpiredAnalyticsDailyAggregates(firestore, now),
  ]);
  return { eventsDeleted, aggregates };
}

export async function cleanupExpiredAnalyticsEvents(
  firestore: Firestore,
  now: Timestamp = Timestamp.now(),
): Promise<number> {
  const expired = await firestore
    .collectionGroup("analyticsEvents")
    .where("expiresAt", "<=", now)
    .orderBy("expiresAt", "asc")
    .orderBy(FieldPath.documentId(), "asc")
    .limit(ANALYTICS_CLEANUP_LIMIT)
    .get();
  if (expired.empty) return 0;
  const batch = firestore.batch();
  for (const document of expired.docs) batch.delete(document.ref);
  await batch.commit();
  return expired.size;
}

function validAnalyticsDailyAggregate(
  document: QueryDocumentSnapshot,
): boolean {
  const value = document.data();
  if (
    !hasExactKeys(value, [
      "schemaVersion",
      "date",
      "counts",
      "updatedAt",
      "expiresAt",
    ]) ||
    value.schemaVersion !== 1 ||
    typeof value.date !== "string" ||
    value.date !== document.id ||
    !(value.updatedAt instanceof Timestamp) ||
    !(value.expiresAt instanceof Timestamp) ||
    !validAggregateCounts(value.counts)
  ) {
    return false;
  }
  try {
    return (
      value.expiresAt.toMillis() ===
      analyticsDailyAggregateExpiresAt(value.date).toMillis()
    );
  } catch (error) {
    if (error instanceof TypeError) return false;
    throw error;
  }
}

function validAggregateCounts(value: unknown): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const counts = value as Readonly<Record<string, unknown>>;
  const keys = Object.keys(counts);
  return (
    keys.length > 0 &&
    keys.every(
      (key) =>
        (key === "ACCOUNT_DELETION_COMPLETED" ||
          key === "ACCOUNT_DELETION_FAILED") &&
        typeof counts[key] === "number" &&
        Number.isSafeInteger(counts[key]) &&
        counts[key] > 0,
    )
  );
}

function uniqueBatchEvents(
  events: readonly AnalyticsEventInput[],
): readonly AnalyticsEventInput[] {
  const unique = new Map<string, AnalyticsEventInput>();
  for (const event of events) {
    const prior = unique.get(event.eventId);
    if (prior !== undefined && !sameEvent(prior, event)) {
      throw new AnalyticsError(
        "already-exists",
        "Analytics event id was reused with a different event in one batch",
      );
    }
    if (prior === undefined) unique.set(event.eventId, event);
  }
  return [...unique.values()];
}

function sameEvent(
  stored: Pick<StoredEvent, "schemaVersion" | "eventName" | "consentRevision">,
  requested: AnalyticsEventInput,
): boolean {
  return (
    stored.schemaVersion === requested.schemaVersion &&
    stored.eventName === requested.eventName &&
    stored.consentRevision === requested.consentRevision
  );
}

function storedEvent(
  event: AnalyticsEventInput,
  occurredAt: Timestamp,
): StoredEvent {
  return {
    schemaVersion: 1,
    eventName: event.eventName,
    consentRevision: event.consentRevision,
    occurredAt,
    expiresAt: Timestamp.fromMillis(
      occurredAt.toMillis() + ANALYTICS_EVENT_TTL_MILLIS,
    ),
  };
}

function consentPath(ownerUid: string): string {
  return `users/${ownerUid}/consents/analytics`;
}

export function hasActiveAnalyticsConsent(snapshot: DocumentSnapshot): boolean {
  try {
    return analyticsConsentFromSnapshot(snapshot).granted;
  } catch (error) {
    if (error instanceof AnalyticsError) return false;
    throw error;
  }
}

function analyticsConsentFromSnapshot(snapshot: DocumentSnapshot): AnalyticsConsentView {
  return snapshot.exists ? consentView(parseStoredConsent(snapshot)) : defaultConsent();
}

function defaultConsent(): AnalyticsConsentView {
  return {
    schemaVersion: 1,
    granted: false,
    commandGeneration: 0,
    grantedAtEpochMillis: null,
    revokedAtEpochMillis: null,
  };
}

function consentView(consent: StoredConsent): AnalyticsConsentView {
  return {
    schemaVersion: 1,
    granted: consent.granted,
    commandGeneration: consent.commandGeneration,
    grantedAtEpochMillis: consent.grantedAt?.toMillis() ?? null,
    revokedAtEpochMillis: consent.revokedAt?.toMillis() ?? null,
  };
}

function receiptView(receipt: StoredConsentReceipt): AnalyticsConsentView {
  return {
    schemaVersion: 1,
    granted: receipt.granted,
    commandGeneration: receipt.commandGeneration,
    grantedAtEpochMillis: receipt.grantedAt?.toMillis() ?? null,
    revokedAtEpochMillis: receipt.revokedAt?.toMillis() ?? null,
  };
}

function parseStoredConsent(snapshot: DocumentSnapshot): StoredConsent {
  const value = snapshot.data();
  if (
    value === undefined ||
    !hasExactKeys(value, [
      "schemaVersion",
      "granted",
      "commandGeneration",
      "operationId",
      "grantedAt",
      "revokedAt",
      "updatedAt",
    ])
  )
    throw malformed("Stored analytics consent is malformed");
  if (
    value.schemaVersion !== 1 ||
    typeof value.granted !== "boolean" ||
    !isPositiveSafeInteger(value.commandGeneration) ||
    typeof value.operationId !== "string" ||
    !/^[A-Za-z0-9_-]{8,128}$/.test(value.operationId) ||
    !nullableTimestamp(value.grantedAt) ||
    !nullableTimestamp(value.revokedAt) ||
    !(value.updatedAt instanceof Timestamp) ||
    (value.granted && (value.grantedAt === null || value.revokedAt !== null)) ||
    (!value.granted && value.revokedAt === null)
  )
    throw malformed("Stored analytics consent is malformed");
  return {
    schemaVersion: 1,
    granted: value.granted,
    commandGeneration: value.commandGeneration,
    operationId: value.operationId,
    grantedAt: value.grantedAt,
    revokedAt: value.revokedAt,
    updatedAt: value.updatedAt,
  };
}

function parseStoredReceipt(snapshot: DocumentSnapshot): StoredConsentReceipt {
  const value = snapshot.data();
  if (
    value === undefined ||
    !hasExactKeys(value, [
      "schemaVersion",
      "ownerUid",
      "granted",
      "commandGeneration",
      "operationId",
      "grantedAt",
      "revokedAt",
      "createdAt",
    ])
  )
    throw malformed("Stored analytics consent operation is malformed");
  if (
    value.schemaVersion !== 1 ||
    typeof value.ownerUid !== "string" ||
    typeof value.granted !== "boolean" ||
    !isPositiveSafeInteger(value.commandGeneration) ||
    typeof value.operationId !== "string" ||
    !nullableTimestamp(value.grantedAt) ||
    !nullableTimestamp(value.revokedAt) ||
    !(value.createdAt instanceof Timestamp)
  )
    throw malformed("Stored analytics consent operation is malformed");
  return {
    schemaVersion: 1,
    ownerUid: value.ownerUid,
    granted: value.granted,
    commandGeneration: value.commandGeneration,
    operationId: value.operationId,
    grantedAt: value.grantedAt,
    revokedAt: value.revokedAt,
    createdAt: value.createdAt,
  };
}

function parseStoredEvent(snapshot: DocumentSnapshot): StoredEvent {
  const value = snapshot.data();
  if (
    value === undefined ||
    !hasExactKeys(value, [
      "schemaVersion",
      "eventName",
      "consentRevision",
      "occurredAt",
      "expiresAt",
    ])
  )
    throw malformed("Stored analytics event is malformed");
  const parsedEvent = parseServerAnalyticsEvent({
    schemaVersion: value.schemaVersion,
    eventId: snapshot.id,
    eventName: value.eventName,
    consentRevision: value.consentRevision,
  });
  if (
    !(value.occurredAt instanceof Timestamp) ||
    !(value.expiresAt instanceof Timestamp) ||
    value.expiresAt.toMillis() !==
      value.occurredAt.toMillis() + ANALYTICS_EVENT_TTL_MILLIS
  )
    throw malformed("Stored analytics event is malformed");
  return {
    schemaVersion: 1,
    eventName: parsedEvent.eventName,
    consentRevision: parsedEvent.consentRevision,
    occurredAt: value.occurredAt,
    expiresAt: value.expiresAt,
  };
}

function hasExactKeys(
  value: Readonly<Record<string, unknown>>,
  expected: readonly string[],
): boolean {
  const actual = Object.keys(value).sort();
  const sortedExpected = [...expected].sort();
  return (
    actual.length === sortedExpected.length &&
    actual.every((key, index) => key === sortedExpected[index])
  );
}

function isPositiveSafeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function nullableTimestamp(value: unknown): value is Timestamp | null {
  return value === null || value instanceof Timestamp;
}

function malformed(message: string): AnalyticsError {
  return new AnalyticsError("failed-precondition", message);
}
