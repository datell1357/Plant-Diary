import { createHash, randomUUID } from "node:crypto";
import {
  FieldPath,
  FieldValue,
  Timestamp,
  type DocumentReference,
  type DocumentSnapshot,
  type Firestore,
  type Transaction,
} from "firebase-admin/firestore";
import type { Messaging } from "firebase-admin/messaging";
import { runAccountMutationTransaction } from "./account-mutation-lock.js";
import { localDateTimeToInstant } from "./notification-settings.js";
import type { ServerAnalyticsRecorder } from "./server-analytics.js";

export type WateringDeliveryCandidate = Readonly<{
  ownerUid: string;
  plantId: string;
  plantName: string;
  dueDate: string;
  zoneId: string;
  globalEnabled: boolean;
  defaultTime: string;
  plantEnabled: boolean | null;
  timeOverride: string | null;
}>;

export type DueWateringAttempt = WateringDeliveryCandidate &
  Readonly<{
    attempt: 0 | 1;
    deduplicationKey: string;
    evaluatedAt: Date;
  }>;

export type WateringEndpointVersion = Readonly<{
  endpointId: string;
  generation: number;
  token: string;
}>;

export type WateringEndpointTarget = Readonly<{
  endpointIds: readonly string[];
  endpointVersions?: readonly WateringEndpointVersion[];
  token: string;
}>;

export type EndpointDeliveryResult = WateringEndpointTarget &
  Readonly<{
    success: boolean;
    permanent: boolean;
    errorCode?: string;
  }>;

export interface WateringDeliveryStore {
  recoverExpiredClaims(now: Date, limit: number): Promise<void>;
  listCandidates(now: Date, limit: number): Promise<readonly WateringDeliveryCandidate[]>;
  claim(attempt: DueWateringAttempt, expiresAt: Date): Promise<string | null>;
  eligibleEndpoints(ownerUid: string): Promise<readonly WateringEndpointTarget[]>;
  revalidateAndMarkSending(
    attempt: DueWateringAttempt,
    claimId: string,
    endpoints: readonly WateringEndpointTarget[],
  ): Promise<readonly WateringEndpointTarget[] | null>;
  markSendMayHaveOccurred(
    attempt: DueWateringAttempt,
    claimId: string,
    endpoints: readonly WateringEndpointTarget[],
  ): Promise<boolean>;
  markSendAmbiguous(attempt: DueWateringAttempt, claimId: string): Promise<void>;
  releaseSendAuthorization(
    claimId: string,
    endpoints: readonly WateringEndpointTarget[],
  ): Promise<void>;
  markFinalizationAmbiguous(
    attempt: DueWateringAttempt,
    claimId: string,
  ): Promise<void>;
  finalizeSent(
    attempt: DueWateringAttempt,
    claimId: string,
    results: readonly EndpointDeliveryResult[],
  ): Promise<void>;
  releaseClaim(
    attempt: DueWateringAttempt,
    claimId: string,
    results?: readonly EndpointDeliveryResult[],
  ): Promise<void>;
  deleteEndpoints(ownerUid: string, results: readonly EndpointDeliveryResult[]): Promise<void>;
}

export type NotificationOpenAuth = Readonly<{ uid: string }>;

export class NotificationOpenError extends Error {
  constructor(
    readonly code:
      | "unauthenticated"
      | "permission-denied"
      | "invalid-argument"
      | "not-found"
      | "failed-precondition"
      | "aborted",
    message: string,
  ) {
    super(message);
    this.name = "NotificationOpenError";
  }
}

type NotificationOpenOutcome =
  | "OPENED"
  | "NOT_FOUND"
  | "RETRYABLE"
  | "NON_OPENABLE"
  | "MALFORMED";

export async function executeConfirmNotificationOpened(
  firestore: Firestore,
  auth: NotificationOpenAuth | null,
  input: unknown,
  now = new Date(),
  analytics?: ServerAnalyticsRecorder,
): Promise<Readonly<{ opened: true }>> {
  const uid = auth?.uid;
  if (uid === undefined) throw new NotificationOpenError("unauthenticated", "Authentication is required");
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new NotificationOpenError("invalid-argument", "Payload must be an object");
  }
  const fields = Object.fromEntries(Object.entries(input));
  if (
    Object.keys(fields).length !== 2 ||
    fields.expectedOwnerUid !== uid ||
    typeof fields.deliveryId !== "string" ||
    !/^[0-9a-f-]{36}$/.test(fields.deliveryId)
  ) {
    throw new NotificationOpenError("permission-denied", "Notification confirmation is invalid");
  }
  if (Number.isNaN(now.valueOf())) {
    throw new NotificationOpenError("invalid-argument", "Confirmation time is invalid");
  }
  const deliveryId = fields.deliveryId;
  const retention = notificationTerminalRetention(now);
  const historyRef = firestore.doc(`users/${uid}/notificationHistory/${deliveryId}`);
  const claimQuery = firestore
    .collection(WATERING_DELIVERY_CLAIMS_COLLECTION)
    .where("ownerUid", "==", uid)
    .where("claimId", "==", deliveryId)
    .limit(2);
  const deliveryStore = new FirestoreWateringDeliveryStore(firestore);
  const outcome = await runAccountMutationTransaction<NotificationOpenOutcome>(
    firestore,
    uid,
    async (transaction) => {
      const history = await transaction.get(historyRef);
      if (history.exists) {
        const status = history.get("status");
        if (
          history.get("ownerUid") !== uid ||
          (status !== "SENT" && status !== "DELIVERED_AMBIGUOUS")
        ) return "NON_OPENABLE";
        if (history.get("destinationOpened") === true) return "OPENED";
        const revision = history.get("revision");
        if (
          typeof revision !== "number" ||
          !Number.isSafeInteger(revision) ||
          revision < 1
        ) return "MALFORMED";
        transaction.set(
          historyRef,
          {
            status: "SENT",
            destinationOpened: true,
            openedAt: FieldValue.serverTimestamp(),
            revision: revision + 1,
            expectedRevision: revision,
            idempotencyKey: `notification-open-${deliveryId}`,
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
        return "OPENED";
      }

      const claims = await transaction.get(claimQuery);
      if (claims.empty) return "NOT_FOUND";
      if (claims.size !== 1) return "MALFORMED";
      const claim = claims.docs[0]!;
      const identity = recoveryClaimIdentity(claim);
      const endpointIds = authorizedEndpointIds(
        claim.get("authorizedEndpointVersions"),
      );
      const ownerRefs = endpointIds.map((endpointId) =>
        firestore.doc(`notificationEndpointOwners/${endpointId}`),
      );
      if (identity === null) {
        const owners = await Promise.all(
          ownerRefs.map((ref) => transaction.get(ref)),
        );
        clearClaimLeases(transaction, ownerRefs, owners, deliveryId);
        transaction.delete(claim.ref);
        return "MALFORMED";
      }
      const state = claim.get("state");
      const expiresAt = claim.get("expiresAt");
      if (!(expiresAt instanceof Timestamp)) {
        const owners = await Promise.all(
          ownerRefs.map((ref) => transaction.get(ref)),
        );
        clearClaimLeases(transaction, ownerRefs, owners, deliveryId);
        transaction.delete(claim.ref);
        return "MALFORMED";
      }
      if (expiresAt.toDate() > now) return "RETRYABLE";
      if (state === "CLAIMED" || state === "AUTHORIZED_PRE_SEND") {
        const owners = await Promise.all(
          ownerRefs.map((ref) => transaction.get(ref)),
        );
        clearClaimLeases(transaction, ownerRefs, owners, deliveryId);
        transaction.delete(claim.ref);
        return "NON_OPENABLE";
      }
      if (
        state !== "SEND_MAY_HAVE_OCCURRED" &&
        state !== "SENDING" &&
        state !== "SEND_UNKNOWN"
      ) {
        const owners = await Promise.all(
          ownerRefs.map((ref) => transaction.get(ref)),
        );
        clearClaimLeases(transaction, ownerRefs, owners, deliveryId);
        transaction.delete(claim.ref);
        return "MALFORMED";
      }

      const accountRef = firestore.doc(`users/${uid}`);
      const scheduleRef = firestore.doc(
        `users/${uid}/wateringSchedules/${identity.plantId}`,
      );
      const settingsRef = firestore.doc(
        `users/${uid}/notificationSettings/watering`,
      );
      const preferenceRef = firestore.doc(
        `users/${uid}/notificationPlantSettings/${identity.plantId}`,
      );
      const [account, schedule, settings, preference, owners] = await Promise.all([
        transaction.get(accountRef),
        transaction.get(scheduleRef),
        transaction.get(settingsRef),
        transaction.get(preferenceRef),
        Promise.all(ownerRefs.map((ref) => transaction.get(ref))),
      ]);
      clearClaimLeases(transaction, ownerRefs, owners, deliveryId);
      if (!account.exists || account.get("ownerUid") !== uid) {
        transaction.delete(claim.ref);
        return "NOT_FOUND";
      }

      transaction.create(historyRef, {
        ownerUid: uid,
        plantId: identity.plantId,
        dueDate: identity.dueDate,
        attempt: identity.attempt,
        status: "SENT",
        failureKind: "LEASE_EXPIRED_AFTER_SEND_START",
        deliveryConfirmedAt: FieldValue.serverTimestamp(),
        ambiguousAt: FieldValue.serverTimestamp(),
        ...retention,
        destinationOpened: true,
        openedAt: FieldValue.serverTimestamp(),
        deduplicationKey: identity.deduplicationKey,
        revision: 2,
        expectedRevision: 1,
        idempotencyKey: `notification-open-${deliveryId}`,
        updatedAt: FieldValue.serverTimestamp(),
      });
      transaction.set(
        claim.ref,
        {
          state: "SEND_UNKNOWN",
          failureKind: "LEASE_EXPIRED_AFTER_SEND_START",
          scheduleFinalized: true,
          recoveredAt: FieldValue.serverTimestamp(),
          ...retention,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      deliveryStore.advanceScheduleAfterTerminalOutcome(
        transaction,
        identity.attemptValue,
        claim,
        scheduleRef,
        schedule,
        settings,
        preference,
      );
      return "OPENED";
    },
  );
  switch (outcome) {
    case "OPENED":
      if (analytics !== undefined) {
        await analytics({
          ownerUid: uid,
          eventName: "WATERING_NOTIFICATION_OPENED",
          operationIdentifier: deliveryId,
        });
      }
      return { opened: true };
    case "NOT_FOUND":
      throw new NotificationOpenError("not-found", "Notification history is unavailable");
    case "RETRYABLE":
      throw new NotificationOpenError("aborted", "Notification delivery is still being finalized");
    case "NON_OPENABLE":
      throw new NotificationOpenError("failed-precondition", "Only delivered notifications can be opened");
    case "MALFORMED":
      throw new NotificationOpenError("failed-precondition", "Notification delivery state is malformed");
  }
}

export interface WateringPushSender {
  send(
    attempt: DueWateringAttempt,
    endpoints: readonly WateringEndpointTarget[],
    deliveryId: string,
  ): Promise<readonly EndpointDeliveryResult[]>;
}

export function selectWateringAttempt(
  candidate: WateringDeliveryCandidate,
  now: Date,
): DueWateringAttempt | null {
  if (Number.isNaN(now.valueOf()) || !candidate.globalEnabled || candidate.plantEnabled === false) {
    return null;
  }
  const effectiveTime = candidate.timeOverride ?? candidate.defaultTime;
  if (!/^(0\d|1\d|2[0-3]):[0-5]\d$/.test(effectiveTime)) return null;
  const local = localDateTime(now, candidate.zoneId);
  if (local.time < effectiveTime) return null;
  const nextDay = addLocalDays(candidate.dueDate, 1);
  const attempt: 0 | 1 | null =
    local.date === candidate.dueDate ? 0 : local.date === nextDay ? 1 : null;
  if (attempt === null) return null;
  return {
    ...candidate,
    attempt,
    deduplicationKey: `${candidate.ownerUid}:${candidate.plantId}:${candidate.dueDate}:${attempt}`,
    evaluatedAt: new Date(now),
  };
}

export const WATERING_DELIVERY_LEASE_MILLIS = 10 * 60 * 1000;
export const NOTIFICATION_RETENTION_MILLIS = 35 * 24 * 60 * 60 * 1_000;
export const NOTIFICATION_RETENTION_CLEANUP_LIMIT = 100;
export const WATERING_DELIVERY_CLAIMS_COLLECTION = "notificationDeliveryClaims";
export const WATERING_CLAIM_RECOVERY_CURSOR = "notificationRuntime/wateringClaimRecovery";

export type NotificationRetentionCleanupFailure = Readonly<{
  path: string;
  error: unknown;
}>;

export type NotificationRetentionCleanupResult = Readonly<{
  scanned: number;
  deleted: number;
  failures: readonly NotificationRetentionCleanupFailure[];
}>;

export async function cleanupExpiredNotificationRecords(
  firestore: Firestore,
  now: Timestamp = Timestamp.now(),
  limit = NOTIFICATION_RETENTION_CLEANUP_LIMIT,
): Promise<NotificationRetentionCleanupResult> {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 500) {
    throw new TypeError("Notification retention cleanup limit is invalid");
  }
  const queries = [
    firestore.collectionGroup("notificationHistory")
      .where("expiresAt", "<=", now)
      .orderBy("expiresAt", "asc")
      .orderBy(FieldPath.documentId(), "asc")
      .limit(limit),
    firestore.collectionGroup("notificationDeliveries")
      .where("expiresAt", "<=", now)
      .orderBy("expiresAt", "asc")
      .orderBy(FieldPath.documentId(), "asc")
      .limit(limit),
    firestore.collection("notificationDeliveryDiagnostics")
      .where("expiresAt", "<=", now)
      .orderBy("expiresAt", "asc")
      .orderBy(FieldPath.documentId(), "asc")
      .limit(limit),
    firestore.collection(WATERING_DELIVERY_CLAIMS_COLLECTION)
      .where("state", "in", ["FAILED", "SEND_UNKNOWN"])
      .where("expiresAt", "<=", now)
      .orderBy("expiresAt", "asc")
      .orderBy(FieldPath.documentId(), "asc")
      .limit(limit),
  ] as const;
  const snapshots = await Promise.all(queries.map((query) => query.get()));
  const documents = snapshots.flatMap((snapshot) => snapshot.docs);
  const outcomes = await Promise.allSettled(
    documents.map((document) => document.ref.delete()),
  );
  const failures: NotificationRetentionCleanupFailure[] = [];
  let deleted = 0;
  outcomes.forEach((outcome, index) => {
    if (outcome.status === "fulfilled") {
      deleted += 1;
    } else {
      failures.push({ path: documents[index]!.ref.path, error: outcome.reason });
    }
  });
  return { scanned: documents.length, deleted, failures };
}

export async function runWateringDeliveryScan(
  store: WateringDeliveryStore,
  sender: WateringPushSender,
  now: Date = new Date(),
  batchSize = 100,
  analytics?: ServerAnalyticsRecorder,
): Promise<Readonly<{ sent: number; failed: number; skipped: number }>> {
  if (!Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > 500) {
    throw new Error("Watering notification batch size is invalid");
  }
  await store.recoverExpiredClaims(now, batchSize);
  const candidates = await store.listCandidates(now, batchSize);
  let sent = 0;
  let failed = 0;
  let skipped = 0;
  for (const candidate of candidates) {
    const attempt = selectWateringAttempt(candidate, now);
    if (attempt === null) {
      skipped += 1;
      continue;
    }
    const claimId = await store.claim(
      attempt,
      new Date(now.valueOf() + WATERING_DELIVERY_LEASE_MILLIS),
    );
    if (claimId === null) {
      skipped += 1;
      continue;
    }
    const lookedUpEndpoints = await store.eligibleEndpoints(attempt.ownerUid);
    const endpoints =
      lookedUpEndpoints.length === 0
        ? null
        : await store.revalidateAndMarkSending(attempt, claimId, lookedUpEndpoints);
    if (endpoints === null || endpoints.length === 0) {
      await store.releaseClaim(attempt, claimId);
      skipped += 1;
      continue;
    }
    if (!(await store.markSendMayHaveOccurred(attempt, claimId, endpoints))) {
      await store.releaseSendAuthorization(claimId, endpoints);
      skipped += 1;
      continue;
    }
    let results: readonly EndpointDeliveryResult[];
    try {
      results = await sender.send(attempt, endpoints, claimId);
    } catch {
      await store.markSendAmbiguous(attempt, claimId);
      await store.releaseSendAuthorization(claimId, endpoints);
      failed += 1;
      continue;
    }
    try {
      await store.releaseSendAuthorization(claimId, endpoints);
    } catch {
      await store.markFinalizationAmbiguous(attempt, claimId);
      failed += 1;
      continue;
    }
    const permanentlyInvalid = results.filter(
      (result) => !result.success && result.permanent,
    );
    if (permanentlyInvalid.length > 0) {
      await store.deleteEndpoints(attempt.ownerUid, permanentlyInvalid);
    }
    if (!results.some((result) => result.success)) {
      await store.releaseClaim(attempt, claimId, results);
      failed += 1;
      continue;
    }
    try {
      await store.finalizeSent(attempt, claimId, results);
      if (analytics !== undefined) {
        await analytics({
          ownerUid: attempt.ownerUid,
          eventName: "WATERING_NOTIFICATION_SENT",
          operationIdentifier: claimId,
        });
      }
      sent += 1;
    } catch {
      await store.markFinalizationAmbiguous(attempt, claimId);
      failed += 1;
    }
  }
  return { sent, failed, skipped };
}

export class FirestoreWateringDeliveryStore implements WateringDeliveryStore {
  constructor(private readonly firestore: Firestore) {}

  async recoverExpiredClaims(now: Date, limit: number): Promise<void> {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 500) {
      throw new Error("Watering claim recovery limit is invalid");
    }
    const cursorRef = this.firestore.doc(WATERING_CLAIM_RECOVERY_CURSOR);
    const cursor = await cursorRef.get();
    const cursorExpiry = cursor.get("expiresAt");
    const cursorDocumentId = cursor.get("documentId");
    let query = this.firestore
      .collection(WATERING_DELIVERY_CLAIMS_COLLECTION)
      .where("state", "in", [
        "CLAIMED",
        "AUTHORIZED_PRE_SEND",
        "SEND_MAY_HAVE_OCCURRED",
        "SENDING",
      ])
      .where("expiresAt", "<=", Timestamp.fromDate(now))
      .orderBy("expiresAt", "asc")
      .orderBy(FieldPath.documentId(), "asc");
    if (cursorExpiry instanceof Timestamp && typeof cursorDocumentId === "string") {
      query = query.startAfter(cursorExpiry, cursorDocumentId);
    }
    const claims = await query.limit(limit).get();
    if (claims.empty) {
      if (cursor.exists) await cursorRef.delete();
      return;
    }
    for (const claim of claims.docs) {
      await this.recoverExpiredClaim(claim.ref, now);
    }
    if (claims.size < limit) {
      await cursorRef.delete();
    } else {
      const last = claims.docs.at(-1)!;
      await cursorRef.set({
        expiresAt: last.get("expiresAt"),
        documentId: last.id,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
  }

  private async recoverExpiredClaim(
    claimRef: DocumentReference,
    now: Date,
  ): Promise<void> {
    const retention = notificationTerminalRetention(now);
    const ownerUid = (await claimRef.get()).get("ownerUid");
    const runTransaction =
      typeof ownerUid === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(ownerUid)
      ? <T>(operation: (transaction: Transaction) => Promise<T>) =>
          runAccountMutationTransaction(this.firestore, ownerUid, operation)
      : <T>(operation: (transaction: Transaction) => Promise<T>) =>
          this.firestore.runTransaction(operation);
    await runTransaction(async (transaction) => {
      const claim = await transaction.get(claimRef);
      if (!claim.exists) return;
      const state = claim.get("state");
      const expiresAt = claim.get("expiresAt");
      if (
        !RECOVERABLE_CLAIM_STATES.has(state) ||
        !(expiresAt instanceof Timestamp) ||
        expiresAt.toDate() > now
      ) return;
      const identity = recoveryClaimIdentity(claim);
      const endpointIds = authorizedEndpointIds(claim.get("authorizedEndpointVersions"));
      const ownerRefs = endpointIds.map((endpointId) =>
        this.firestore.doc(`notificationEndpointOwners/${endpointId}`),
      );
      if (identity === null) {
        const owners = await Promise.all(ownerRefs.map((ref) => transaction.get(ref)));
        clearClaimLeases(transaction, ownerRefs, owners, claim.get("claimId"));
        transaction.delete(claimRef);
        return;
      }
      const accountRef = this.firestore.doc(`users/${identity.ownerUid}`);
      const scheduleRef = this.firestore.doc(
        `users/${identity.ownerUid}/wateringSchedules/${identity.plantId}`,
      );
      const settingsRef = this.firestore.doc(
        `users/${identity.ownerUid}/notificationSettings/watering`,
      );
      const preferenceRef = this.firestore.doc(
        `users/${identity.ownerUid}/notificationPlantSettings/${identity.plantId}`,
      );
      const historyRef = this.firestore.doc(
        `users/${identity.ownerUid}/notificationHistory/${identity.claimId}`,
      );
      const receiptRef = this.firestore.doc(
        `users/${identity.ownerUid}/notificationDeliveries/${deliveryDocumentId(identity.deduplicationKey)}`,
      );
      const [account, schedule, settings, preference, history, receipt, owners] =
        await Promise.all([
          transaction.get(accountRef),
          transaction.get(scheduleRef),
          transaction.get(settingsRef),
          transaction.get(preferenceRef),
          transaction.get(historyRef),
          transaction.get(receiptRef),
          Promise.all(ownerRefs.map((ref) => transaction.get(ref))),
        ]);
      clearClaimLeases(transaction, ownerRefs, owners, identity.claimId);
      if (
        state === "CLAIMED" ||
        state === "AUTHORIZED_PRE_SEND" ||
        !account.exists ||
        account.get("ownerUid") !== identity.ownerUid ||
        (receipt.exists && receipt.get("status") === "SENT")
      ) {
        transaction.delete(claimRef);
        return;
      }
      if (!history.exists) {
        transaction.create(historyRef, {
          ownerUid: identity.ownerUid,
          plantId: identity.plantId,
          dueDate: identity.dueDate,
          attempt: identity.attempt,
          status: "DELIVERED_AMBIGUOUS",
          failureKind: "LEASE_EXPIRED_AFTER_SEND_START",
          deliveryConfirmedAt: FieldValue.serverTimestamp(),
          ambiguousAt: FieldValue.serverTimestamp(),
          ...retention,
          destinationOpened: false,
          deduplicationKey: identity.deduplicationKey,
          revision: 1,
          expectedRevision: 0,
          idempotencyKey: `delivery-history-${identity.claimId}`,
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
      transaction.set(
        claimRef,
        {
          state: "SEND_UNKNOWN",
          failureKind: "LEASE_EXPIRED_AFTER_SEND_START",
          scheduleFinalized: true,
          recoveredAt: FieldValue.serverTimestamp(),
          ...retention,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      this.advanceScheduleAfterTerminalOutcome(
        transaction,
        identity.attemptValue,
        claim,
        scheduleRef,
        schedule,
        settings,
        preference,
      );
    });
  }

  async listCandidates(now: Date, limit: number): Promise<readonly WateringDeliveryCandidate[]> {
    const cursorRef = this.firestore.doc("notificationRuntime/wateringScan");
    const cursor = await cursorRef.get();
    const cursorTime = cursor.get("nextNotificationAt");
    const cursorPath = cursor.get("documentPath");
    let query = this.firestore
      .collectionGroup("wateringSchedules")
      .where("notificationCandidateActive", "==", true)
      .where("nextNotificationAt", "<=", Timestamp.fromDate(now))
      .orderBy("nextNotificationAt", "asc")
      .orderBy(FieldPath.documentId(), "asc");
    if (cursorTime instanceof Timestamp && typeof cursorPath === "string") {
      query = query.startAfter(cursorTime, cursorPath);
    }
    const schedules = await query.limit(limit).get();
    if (schedules.empty) {
      if (cursor.exists) await cursorRef.delete();
      return [];
    }
    const lastSchedule = schedules.docs.at(-1)!;
    if (schedules.size < limit) {
      await cursorRef.delete();
    } else {
      await cursorRef.set({
        nextNotificationAt: lastSchedule.get("nextNotificationAt"),
        documentPath: lastSchedule.ref.path,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
    const candidates = await Promise.all(
      schedules.docs.map(async (schedule): Promise<WateringDeliveryCandidate | null> => {
        const uid = schedule.ref.parent.parent?.id;
        if (uid === undefined) return null;
        const [settings, preference, plant] = await Promise.all([
          this.firestore.doc(`users/${uid}/notificationSettings/watering`).get(),
          this.firestore.doc(`users/${uid}/notificationPlantSettings/${schedule.id}`).get(),
          this.firestore.doc(`users/${uid}/personalPlants/${schedule.id}`).get(),
        ]);
        const dueDate = schedule.get("dueDate");
        const zoneId = settings.get("zoneId");
        const defaultTime = settings.get("defaultTime");
        const plantName = plant.get("displayName");
        if (
          !settings.exists ||
          !plant.exists ||
          schedule.get("ownerUid") !== uid ||
          plant.get("ownerUid") !== uid ||
          typeof dueDate !== "string" ||
          typeof zoneId !== "string" ||
          typeof defaultTime !== "string" ||
          typeof plantName !== "string"
        ) {
          return null;
        }
        return {
          ownerUid: uid,
          plantId: schedule.id,
          plantName,
          dueDate,
          zoneId,
          globalEnabled: settings.get("wateringEnabled") === true,
          defaultTime,
          plantEnabled:
            preference.exists && typeof preference.get("enabled") === "boolean"
              ? (preference.get("enabled") as boolean)
              : null,
          timeOverride:
            preference.exists && typeof preference.get("timeOverride") === "string"
              ? (preference.get("timeOverride") as string)
              : null,
        };
      }),
    );
    return candidates.filter((candidate): candidate is WateringDeliveryCandidate => candidate !== null);
  }

  async claim(attempt: DueWateringAttempt, expiresAt: Date): Promise<string | null> {
    const retention = notificationTerminalRetention(attempt.evaluatedAt);
    return runAccountMutationTransaction(this.firestore, attempt.ownerUid, async (transaction) => {
      const claimRef = this.firestore.doc(
        `notificationDeliveryClaims/${deliveryDocumentId(attempt.deduplicationKey)}`,
      );
      const receiptRef = this.receiptRef(attempt);
      const [claim, receipt] = await Promise.all([
        transaction.get(claimRef),
        transaction.get(receiptRef),
      ]);
      if (receipt.exists && receipt.get("status") === "SENT") return null;
      const state = claim.get("state");
      const currentExpiry = claim.get("expiresAt");
      if (
        claim.exists &&
        currentExpiry instanceof Timestamp &&
        currentExpiry.toDate() > attempt.evaluatedAt
      ) {
        return null;
      }
      if (
        claim.exists &&
        (state === "SEND_MAY_HAVE_OCCURRED" ||
          state === "SENDING" ||
          state === "SEND_UNKNOWN")
      ) {
        const claimId = claim.get("claimId");
        if (
          typeof claimId !== "string" ||
          claim.get("ownerUid") !== attempt.ownerUid ||
          claim.get("plantId") !== attempt.plantId ||
          claim.get("dueDate") !== attempt.dueDate ||
          claim.get("attempt") !== attempt.attempt ||
          claim.get("deduplicationKey") !== attempt.deduplicationKey
        ) {
          return null;
        }
        const historyRef = this.firestore.doc(
          `users/${attempt.ownerUid}/notificationHistory/${claimId}`,
        );
        const scheduleRef = this.firestore.doc(
          `users/${attempt.ownerUid}/wateringSchedules/${attempt.plantId}`,
        );
        const settingsRef = this.firestore.doc(
          `users/${attempt.ownerUid}/notificationSettings/watering`,
        );
        const preferenceRef = this.firestore.doc(
          `users/${attempt.ownerUid}/notificationPlantSettings/${attempt.plantId}`,
        );
        const [history, schedule, settings, preference] = await Promise.all([
          transaction.get(historyRef),
          transaction.get(scheduleRef),
          transaction.get(settingsRef),
          transaction.get(preferenceRef),
        ]);
        if (!history.exists) {
          transaction.create(historyRef, {
            ownerUid: attempt.ownerUid,
            plantId: attempt.plantId,
            dueDate: attempt.dueDate,
            attempt: attempt.attempt,
            status: "DELIVERED_AMBIGUOUS",
            failureKind: "LEASE_EXPIRED_AFTER_SEND_START",
            deliveryConfirmedAt: FieldValue.serverTimestamp(),
            ambiguousAt: FieldValue.serverTimestamp(),
            ...retention,
            destinationOpened: false,
            deduplicationKey: attempt.deduplicationKey,
            revision: 1,
            expectedRevision: 0,
            idempotencyKey: `delivery-history-${claimId}`,
            updatedAt: FieldValue.serverTimestamp(),
          });
        }
        transaction.set(
          claimRef,
          {
            state: "SEND_UNKNOWN",
            failureKind: "LEASE_EXPIRED_AFTER_SEND_START",
            scheduleFinalized: true,
            recoveredAt: FieldValue.serverTimestamp(),
            ...retention,
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
        this.advanceScheduleAfterTerminalOutcome(
          transaction,
          attempt,
          claim,
          scheduleRef,
          schedule,
          settings,
          preference,
        );
        return null;
      }
      const claimId = randomUUID();
      transaction.set(
        claimRef,
        {
          ownerUid: attempt.ownerUid,
          plantId: attempt.plantId,
          dueDate: attempt.dueDate,
          attempt: attempt.attempt,
          deduplicationKey: attempt.deduplicationKey,
          state: "CLAIMED",
          claimId,
          claimedAt: Timestamp.fromDate(attempt.evaluatedAt),
          expiresAt: Timestamp.fromDate(expiresAt),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: false },
      );
      return claimId;
    });
  }

  async eligibleEndpoints(ownerUid: string): Promise<readonly WateringEndpointTarget[]> {
    const endpoints = await this.firestore
      .collection(`users/${ownerUid}/notificationEndpoints`)
      .where("notificationsEnabled", "==", true)
      .limit(500)
      .get();
    const grouped = new Map<string, WateringEndpointVersion[]>();
    for (const endpoint of endpoints.docs) {
      const token = endpoint.get("token");
      const generation = endpoint.get("generation");
      if (
        typeof token !== "string" ||
        token.length === 0 ||
        typeof generation !== "number" ||
        !Number.isSafeInteger(generation)
      ) continue;
      const versions = grouped.get(token) ?? [];
      versions.push({ endpointId: endpoint.id, generation, token });
      grouped.set(token, versions);
    }
    return [...grouped.entries()].map(([token, endpointVersions]) => ({
      token,
      endpointIds: endpointVersions.map((endpoint) => endpoint.endpointId),
      endpointVersions,
    }));
  }

  async revalidateAndMarkSending(
    attempt: DueWateringAttempt,
    claimId: string,
    endpoints: readonly WateringEndpointTarget[],
  ): Promise<readonly WateringEndpointTarget[] | null> {
    const requestedVersions = endpoints.flatMap((endpoint) => endpoint.endpointVersions ?? []);
    if (requestedVersions.length === 0) return null;
    return runAccountMutationTransaction(this.firestore, attempt.ownerUid, async (transaction) => {
      const claimRef = this.firestore.doc(
        `notificationDeliveryClaims/${deliveryDocumentId(attempt.deduplicationKey)}`,
      );
      const scheduleRef = this.firestore.doc(
        `users/${attempt.ownerUid}/wateringSchedules/${attempt.plantId}`,
      );
      const settingsRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationSettings/watering`,
      );
      const preferenceRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationPlantSettings/${attempt.plantId}`,
      );
      const plantRef = this.firestore.doc(
        `users/${attempt.ownerUid}/personalPlants/${attempt.plantId}`,
      );
      const endpointRefs = requestedVersions.map((endpoint) =>
        this.firestore.doc(
          `users/${attempt.ownerUid}/notificationEndpoints/${endpoint.endpointId}`,
        ),
      );
      const ownerRefs = requestedVersions.map((endpoint) =>
        this.firestore.doc(`notificationEndpointOwners/${endpoint.endpointId}`),
      );
      const [claim, receipt, schedule, settings, preference, plant, endpointDocuments, owners] =
        await Promise.all([
          transaction.get(claimRef),
          transaction.get(this.receiptRef(attempt)),
          transaction.get(scheduleRef),
          transaction.get(settingsRef),
          transaction.get(preferenceRef),
          transaction.get(plantRef),
          Promise.all(endpointRefs.map((ref) => transaction.get(ref))),
          Promise.all(ownerRefs.map((ref) => transaction.get(ref))),
        ]);
      if (
        receipt.exists ||
        !claim.exists ||
        claim.get("claimId") !== claimId ||
        claim.get("state") !== "CLAIMED" ||
        !(claim.get("expiresAt") instanceof Timestamp) ||
        claim.get("expiresAt").toDate() <= attempt.evaluatedAt ||
        !schedule.exists ||
        !settings.exists ||
        !plant.exists ||
        schedule.get("notificationCandidateActive") !== true ||
        schedule.get("dueDate") !== attempt.dueDate ||
        schedule.get("ownerUid") !== attempt.ownerUid ||
        settings.get("wateringEnabled") !== true ||
        (preference.exists && preference.get("enabled") === false)
      ) {
        return null;
      }
      const currentZone = settings.get("zoneId");
      const currentDefaultTime = settings.get("defaultTime");
      const currentOverride = preference.get("timeOverride");
      const authorizedReminderTime =
        typeof currentOverride === "string" ? currentOverride : currentDefaultTime;
      const scheduleRevision = notificationRevision(schedule);
      const settingsRevision = notificationRevision(settings);
      const preferenceRevision = preference.exists ? notificationRevision(preference) : 0;
      if (
        typeof currentZone !== "string" ||
        typeof currentDefaultTime !== "string" ||
        !/^(0\d|1\d|2[0-3]):[0-5]\d$/.test(authorizedReminderTime) ||
        scheduleRevision === null ||
        settingsRevision === null ||
        preferenceRevision === null
      ) return null;
      const current = selectWateringAttempt(
        {
          ...attempt,
          zoneId: currentZone,
          globalEnabled: true,
          defaultTime: currentDefaultTime,
          plantEnabled:
            preference.exists && typeof preference.get("enabled") === "boolean"
              ? (preference.get("enabled") as boolean)
              : null,
          timeOverride:
            preference.exists && typeof preference.get("timeOverride") === "string"
              ? (preference.get("timeOverride") as string)
              : null,
        },
        attempt.evaluatedAt,
      );
      const validSchedule =
        current !== null &&
        current.attempt === attempt.attempt &&
        current.deduplicationKey === attempt.deduplicationKey;
      const currentVersions = requestedVersions.filter((endpoint, index) => {
        const document = endpointDocuments[index]!;
        const owner = owners[index]!;
        return (
          document.exists &&
          document.get("ownerUid") === attempt.ownerUid &&
          document.get("notificationsEnabled") === true &&
          document.get("generation") === endpoint.generation &&
          document.get("token") === endpoint.token &&
          owner.exists &&
          owner.get("state") === "REGISTERED" &&
          owner.get("ownerUid") === attempt.ownerUid &&
          owner.get("notificationsEnabled") === true &&
          owner.get("generation") === endpoint.generation &&
          owner.get("token") === endpoint.token
        );
      });
      if (validSchedule && currentVersions.length > 0) {
        for (const endpoint of currentVersions) {
          const index = requestedVersions.indexOf(endpoint);
          const owner = owners[index]!;
          const leases = activeSendLeases(owner.get("activeSendLeases"));
          transaction.set(
            ownerRefs[index]!,
            {
              activeSendLeases: {
                ...leases,
                // This outlives the scheduled function's 540-second hard timeout. Once expired,
                // the authorizing process cannot resume and send after a successful revoke.
                [claimId]: Timestamp.fromMillis(
                  Date.now() + WATERING_DELIVERY_LEASE_MILLIS,
                ),
              },
              updatedAt: FieldValue.serverTimestamp(),
            },
            { merge: true },
          );
        }
        transaction.set(
          claimRef,
          {
            state: "AUTHORIZED_PRE_SEND",
            authorizedScheduleRevision: scheduleRevision,
            authorizedSettingsRevision: settingsRevision,
            authorizedPreferenceRevision: preferenceRevision,
            authorizedPreferenceExists: preference.exists,
            authorizedZoneId: currentZone,
            authorizedReminderTime,
            authorizedEndpointVersions: currentVersions.map((endpoint) => ({
              endpointId: endpoint.endpointId,
              generation: endpoint.generation,
            })),
            authorizedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
      }
      if (!validSchedule || currentVersions.length === 0) return null;
      const grouped = new Map<string, WateringEndpointVersion[]>();
      for (const endpoint of currentVersions) {
        const values = grouped.get(endpoint.token) ?? [];
        values.push(endpoint);
        grouped.set(endpoint.token, values);
      }
      return [...grouped.entries()].map(([token, endpointVersions]) => ({
        token,
        endpointIds: endpointVersions.map((endpoint) => endpoint.endpointId),
        endpointVersions,
      }));
    });
  }

  async markSendMayHaveOccurred(
    attempt: DueWateringAttempt,
    claimId: string,
    endpoints: readonly WateringEndpointTarget[],
  ): Promise<boolean> {
    const requestedVersions = endpoints.flatMap(
      (endpoint) => endpoint.endpointVersions ?? [],
    );
    const boundaryNow = new Date();
    return runAccountMutationTransaction(this.firestore, attempt.ownerUid, async (transaction) => {
      const claimRef = this.firestore.doc(
        `notificationDeliveryClaims/${deliveryDocumentId(attempt.deduplicationKey)}`,
      );
      const scheduleRef = this.firestore.doc(
        `users/${attempt.ownerUid}/wateringSchedules/${attempt.plantId}`,
      );
      const settingsRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationSettings/watering`,
      );
      const preferenceRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationPlantSettings/${attempt.plantId}`,
      );
      const endpointRefs = requestedVersions.map((endpoint) =>
        this.firestore.doc(
          `users/${attempt.ownerUid}/notificationEndpoints/${endpoint.endpointId}`,
        ),
      );
      const ownerRefs = requestedVersions.map((endpoint) =>
        this.firestore.doc(`notificationEndpointOwners/${endpoint.endpointId}`),
      );
      const [claim, schedule, settings, preference, endpointDocuments, owners] =
        await Promise.all([
          transaction.get(claimRef),
          transaction.get(scheduleRef),
          transaction.get(settingsRef),
          transaction.get(preferenceRef),
          Promise.all(endpointRefs.map((ref) => transaction.get(ref))),
          Promise.all(ownerRefs.map((ref) => transaction.get(ref))),
        ]);
      const authorizedScheduleRevision = claim.get("authorizedScheduleRevision");
      const authorizedSettingsRevision = claim.get("authorizedSettingsRevision");
      const authorizedPreferenceRevision = claim.get("authorizedPreferenceRevision");
      const authorizedPreferenceExists = claim.get("authorizedPreferenceExists");
      const authorizedZoneId = claim.get("authorizedZoneId");
      const authorizedReminderTime = claim.get("authorizedReminderTime");
      const currentOverride = preference.get("timeOverride");
      const currentReminderTime =
        typeof currentOverride === "string"
          ? currentOverride
          : settings.get("defaultTime");
      const endpointAuthorizationCurrent =
        requestedVersions.length > 0 &&
        requestedVersions.every((endpoint, index) => {
          const document = endpointDocuments[index]!;
          const owner = owners[index]!;
          const lease = activeSendLeases(owner.get("activeSendLeases"))[claimId];
          return (
            document.exists &&
            document.get("ownerUid") === attempt.ownerUid &&
            document.get("notificationsEnabled") === true &&
            document.get("generation") === endpoint.generation &&
            document.get("token") === endpoint.token &&
            owner.exists &&
            owner.get("state") === "REGISTERED" &&
            owner.get("ownerUid") === attempt.ownerUid &&
            owner.get("notificationsEnabled") === true &&
            owner.get("generation") === endpoint.generation &&
            owner.get("token") === endpoint.token &&
            lease instanceof Timestamp &&
            lease.toDate() > boundaryNow
          );
        });
      const authorizationCurrent =
        claim.exists &&
        claim.get("claimId") === claimId &&
        claim.get("state") === "AUTHORIZED_PRE_SEND" &&
        schedule.exists &&
        schedule.get("ownerUid") === attempt.ownerUid &&
        schedule.get("dueDate") === attempt.dueDate &&
        schedule.get("notificationCandidateActive") === true &&
        notificationRevision(schedule) === authorizedScheduleRevision &&
        settings.exists &&
        settings.get("wateringEnabled") === true &&
        notificationRevision(settings) === authorizedSettingsRevision &&
        preference.exists === authorizedPreferenceExists &&
        (preference.exists ? notificationRevision(preference) : 0) ===
          authorizedPreferenceRevision &&
        (!preference.exists || preference.get("enabled") !== false) &&
        settings.get("zoneId") === authorizedZoneId &&
        currentReminderTime === authorizedReminderTime &&
        endpointAuthorizationCurrent;
      if (!authorizationCurrent) {
        const ownsPreSendClaim =
          claim.exists &&
          claim.get("claimId") === claimId &&
          claim.get("state") === "AUTHORIZED_PRE_SEND";
        if (ownsPreSendClaim) {
          owners.forEach((owner, index) => {
            if (!owner.exists) return;
            const leases = activeSendLeases(owner.get("activeSendLeases"));
            if (!(claimId in leases)) return;
            delete leases[claimId];
            transaction.set(
              ownerRefs[index]!,
              {
                activeSendLeases: leases,
                updatedAt: FieldValue.serverTimestamp(),
              },
              { merge: true },
            );
          });
          transaction.delete(claimRef);
        }
        return false;
      }
      transaction.set(
        claimRef,
        {
          state: "SEND_MAY_HAVE_OCCURRED",
          sendMayHaveOccurredAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      return true;
    });
  }

  async markSendAmbiguous(attempt: DueWateringAttempt, claimId: string): Promise<void> {
    const retention = notificationTerminalRetention(attempt.evaluatedAt);
    await runAccountMutationTransaction(this.firestore, attempt.ownerUid, async (transaction) => {
      const claimRef = this.firestore.doc(
        `notificationDeliveryClaims/${deliveryDocumentId(attempt.deduplicationKey)}`,
      );
      const historyRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationHistory/${claimId}`,
      );
      const scheduleRef = this.firestore.doc(
        `users/${attempt.ownerUid}/wateringSchedules/${attempt.plantId}`,
      );
      const settingsRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationSettings/watering`,
      );
      const preferenceRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationPlantSettings/${attempt.plantId}`,
      );
      const [claim, history, schedule, settings, preference] = await Promise.all([
        transaction.get(claimRef),
        transaction.get(historyRef),
        transaction.get(scheduleRef),
        transaction.get(settingsRef),
        transaction.get(preferenceRef),
      ]);
      if (
        !claim.exists ||
        claim.get("claimId") !== claimId ||
        (claim.get("state") !== "SEND_MAY_HAVE_OCCURRED" &&
          claim.get("state") !== "SEND_UNKNOWN")
      ) return;
      transaction.set(
        claimRef,
        {
          state: "SEND_UNKNOWN",
          scheduleFinalized: true,
          ...retention,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      if (!history.exists) {
        transaction.create(historyRef, {
          ownerUid: attempt.ownerUid,
          plantId: attempt.plantId,
          dueDate: attempt.dueDate,
          attempt: attempt.attempt,
          status: "DELIVERED_AMBIGUOUS",
          failureKind: "TRANSPORT_UNKNOWN",
          deliveryConfirmedAt: FieldValue.serverTimestamp(),
          ambiguousAt: FieldValue.serverTimestamp(),
          ...retention,
          destinationOpened: false,
          deduplicationKey: attempt.deduplicationKey,
          revision: 1,
          expectedRevision: 0,
          idempotencyKey: `delivery-history-${claimId}`,
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
      this.advanceScheduleAfterTerminalOutcome(
        transaction,
        attempt,
        claim,
        scheduleRef,
        schedule,
        settings,
        preference,
      );
    });
  }

  async releaseSendAuthorization(
    claimId: string,
    endpoints: readonly WateringEndpointTarget[],
  ): Promise<void> {
    const endpointIds = [
      ...new Set(
        endpoints.flatMap((endpoint) =>
          endpoint.endpointVersions?.map((version) => version.endpointId) ?? endpoint.endpointIds,
        ),
      ),
    ];
    await this.firestore.runTransaction(async (transaction) => {
      const ownerRefs = endpointIds.map((endpointId) =>
        this.firestore.doc(`notificationEndpointOwners/${endpointId}`),
      );
      const owners = await Promise.all(ownerRefs.map((ref) => transaction.get(ref)));
      owners.forEach((owner, index) => {
        if (!owner.exists) return;
        const leases = activeSendLeases(owner.get("activeSendLeases"));
        if (!(claimId in leases)) return;
        delete leases[claimId];
        transaction.set(
          ownerRefs[index]!,
          {
            activeSendLeases: leases,
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
      });
    });
  }

  async markFinalizationAmbiguous(
    attempt: DueWateringAttempt,
    claimId: string,
  ): Promise<void> {
    const retention = notificationTerminalRetention(attempt.evaluatedAt);
    await runAccountMutationTransaction(this.firestore, attempt.ownerUid, async (transaction) => {
      const claimRef = this.firestore.doc(
        `notificationDeliveryClaims/${deliveryDocumentId(attempt.deduplicationKey)}`,
      );
      const historyRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationHistory/${claimId}`,
      );
      const scheduleRef = this.firestore.doc(
        `users/${attempt.ownerUid}/wateringSchedules/${attempt.plantId}`,
      );
      const settingsRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationSettings/watering`,
      );
      const preferenceRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationPlantSettings/${attempt.plantId}`,
      );
      const [claim, history, receipt, schedule, settings, preference] = await Promise.all([
        transaction.get(claimRef),
        transaction.get(historyRef),
        transaction.get(this.receiptRef(attempt)),
        transaction.get(scheduleRef),
        transaction.get(settingsRef),
        transaction.get(preferenceRef),
      ]);
      if (history.exists || receipt.exists) return;
      if (
        !claim.exists ||
        claim.get("claimId") !== claimId ||
        claim.get("state") !== "SEND_MAY_HAVE_OCCURRED"
      ) return;
      transaction.set(
        claimRef,
        {
          state: "SEND_UNKNOWN",
          failureKind: "FINALIZATION_UNKNOWN",
          scheduleFinalized: true,
          ...retention,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
      transaction.create(historyRef, {
        ownerUid: attempt.ownerUid,
        plantId: attempt.plantId,
        dueDate: attempt.dueDate,
        attempt: attempt.attempt,
        status: "DELIVERED_AMBIGUOUS",
        deliveryConfirmedAt: FieldValue.serverTimestamp(),
        ambiguousAt: FieldValue.serverTimestamp(),
        ...retention,
        destinationOpened: false,
        deduplicationKey: attempt.deduplicationKey,
        revision: 1,
        expectedRevision: 0,
        idempotencyKey: `delivery-history-${claimId}`,
        updatedAt: FieldValue.serverTimestamp(),
      });
      this.advanceScheduleAfterTerminalOutcome(
        transaction,
        attempt,
        claim,
        scheduleRef,
        schedule,
        settings,
        preference,
      );
    });
  }

  async finalizeSent(
    attempt: DueWateringAttempt,
    claimId: string,
    results: readonly EndpointDeliveryResult[],
  ): Promise<void> {
    const retention = notificationTerminalRetention(attempt.evaluatedAt);
    await runAccountMutationTransaction(this.firestore, attempt.ownerUid, async (transaction) => {
      const claimRef = this.firestore.doc(
        `notificationDeliveryClaims/${deliveryDocumentId(attempt.deduplicationKey)}`,
      );
      const receiptRef = this.receiptRef(attempt);
      const scheduleRef = this.firestore.doc(
        `users/${attempt.ownerUid}/wateringSchedules/${attempt.plantId}`,
      );
      const settingsRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationSettings/watering`,
      );
      const preferenceRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationPlantSettings/${attempt.plantId}`,
      );
      const [claim, receipt, schedule, settings, preference] = await Promise.all([
        transaction.get(claimRef),
        transaction.get(receiptRef),
        transaction.get(scheduleRef),
        transaction.get(settingsRef),
        transaction.get(preferenceRef),
      ]);
      if (receipt.exists && receipt.get("status") === "SENT") return;
      if (
        !claim.exists ||
        claim.get("claimId") !== claimId ||
        claim.get("state") !== "SEND_MAY_HAVE_OCCURRED"
      ) {
        throw new Error("Watering delivery claim is no longer current");
      }
      transaction.create(receiptRef, {
        ownerUid: attempt.ownerUid,
        plantId: attempt.plantId,
        dueDate: attempt.dueDate,
        attempt: attempt.attempt,
        status: "SENT",
        scheduledFor: Timestamp.fromDate(attempt.evaluatedAt),
        deliveredAt: FieldValue.serverTimestamp(),
        ...retention,
        deduplicationKey: attempt.deduplicationKey,
        revision: 1,
        expectedRevision: 0,
        idempotencyKey: `delivery-${deliveryDocumentId(attempt.deduplicationKey)}`,
        updatedAt: FieldValue.serverTimestamp(),
      });
      transaction.create(
        this.firestore.doc(`notificationDeliveryDiagnostics/${claimId}`),
        {
          ownerUid: attempt.ownerUid,
          deduplicationKey: attempt.deduplicationKey,
          endpointResults: results.map((result) => ({
            endpointIds: [...result.endpointIds],
            tokenHash: createHash("sha256").update(result.token, "utf8").digest("hex"),
            success: result.success,
            permanent: result.permanent,
            errorCode: result.errorCode ?? null,
          })),
          createdAt: FieldValue.serverTimestamp(),
          ...retention,
        },
      );
      transaction.create(
        this.firestore.doc(
          `users/${attempt.ownerUid}/notificationHistory/${claimId}`,
        ),
        {
          ownerUid: attempt.ownerUid,
          plantId: attempt.plantId,
          dueDate: attempt.dueDate,
          attempt: attempt.attempt,
          status: "SENT",
          deliveryConfirmedAt: FieldValue.serverTimestamp(),
          ...retention,
          destinationOpened: false,
          deduplicationKey: attempt.deduplicationKey,
          revision: 1,
          expectedRevision: 0,
          idempotencyKey: `delivery-history-${claimId}`,
          updatedAt: FieldValue.serverTimestamp(),
        },
      );
      this.advanceScheduleAfterTerminalOutcome(
        transaction,
        attempt,
        claim,
        scheduleRef,
        schedule,
        settings,
        preference,
      );
      transaction.delete(claimRef);
    });
  }

  async releaseClaim(
    attempt: DueWateringAttempt,
    claimId: string,
    results?: readonly EndpointDeliveryResult[],
  ): Promise<void> {
    const retention = notificationTerminalRetention(attempt.evaluatedAt);
    await runAccountMutationTransaction(this.firestore, attempt.ownerUid, async (transaction) => {
      const claimRef = this.firestore.doc(
        `notificationDeliveryClaims/${deliveryDocumentId(attempt.deduplicationKey)}`,
      );
      const scheduleRef = this.firestore.doc(
        `users/${attempt.ownerUid}/wateringSchedules/${attempt.plantId}`,
      );
      const settingsRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationSettings/watering`,
      );
      const preferenceRef = this.firestore.doc(
        `users/${attempt.ownerUid}/notificationPlantSettings/${attempt.plantId}`,
      );
      const [claim, schedule, settings, preference] = await Promise.all([
        transaction.get(claimRef),
        transaction.get(scheduleRef),
        transaction.get(settingsRef),
        transaction.get(preferenceRef),
      ]);
      if (!claim.exists || claim.get("claimId") !== claimId) return;
      if (results === undefined) {
        if (
          claim.get("state") === "CLAIMED" ||
          claim.get("state") === "AUTHORIZED_PRE_SEND"
        ) {
          transaction.delete(claimRef);
        }
      } else if (claim.get("state") === "SEND_MAY_HAVE_OCCURRED") {
        transaction.set(
          claimRef,
          {
            state: "FAILED",
            scheduleFinalized: true,
            endpointResults: results.map((result) => ({
              endpointIds: [...result.endpointIds],
              tokenHash: createHash("sha256").update(result.token, "utf8").digest("hex"),
              success: result.success,
              permanent: result.permanent,
              errorCode: result.errorCode ?? null,
            })),
            ...retention,
            updatedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
        transaction.create(
          this.firestore.doc(`users/${attempt.ownerUid}/notificationHistory/${claimId}`),
          {
            ownerUid: attempt.ownerUid,
            plantId: attempt.plantId,
            dueDate: attempt.dueDate,
            attempt: attempt.attempt,
            status: "FAILED",
            failureKind: "FCM_REJECTED",
            failedAt: FieldValue.serverTimestamp(),
            ...retention,
            destinationOpened: false,
            deduplicationKey: attempt.deduplicationKey,
            revision: 1,
            expectedRevision: 0,
            idempotencyKey: `delivery-history-${claimId}`,
            updatedAt: FieldValue.serverTimestamp(),
          },
        );
        this.advanceScheduleAfterTerminalOutcome(
          transaction,
          attempt,
          claim,
          scheduleRef,
          schedule,
          settings,
          preference,
        );
      }
    });
  }

  async deleteEndpoints(
    ownerUid: string,
    results: readonly EndpointDeliveryResult[],
  ): Promise<void> {
    const versions = new Map<string, WateringEndpointVersion>();
    for (const result of results) {
      for (const endpoint of result.endpointVersions ?? []) versions.set(endpoint.endpointId, endpoint);
    }
    await Promise.all(
      [...versions.values()].map(async (expected) => {
        await runAccountMutationTransaction(this.firestore, ownerUid, async (transaction) => {
          const endpointRef = this.firestore.doc(
            `users/${ownerUid}/notificationEndpoints/${expected.endpointId}`,
          );
          const ownerRef = this.firestore.doc(
            `notificationEndpointOwners/${expected.endpointId}`,
          );
          const [endpoint, owner] = await Promise.all([
            transaction.get(endpointRef),
            transaction.get(ownerRef),
          ]);
          if (
            !endpoint.exists ||
            endpoint.get("ownerUid") !== ownerUid ||
            endpoint.get("generation") !== expected.generation ||
            endpoint.get("token") !== expected.token
          ) return;
          transaction.delete(endpointRef);
          if (
            owner.exists &&
            owner.get("state") === "REGISTERED" &&
            owner.get("ownerUid") === ownerUid &&
            owner.get("generation") === expected.generation &&
            owner.get("token") === expected.token
          ) {
            transaction.set(
              ownerRef,
              {
                state: "INVALID",
                invalidatedAt: FieldValue.serverTimestamp(),
                updatedAt: FieldValue.serverTimestamp(),
              },
              { merge: true },
            );
          }
        });
      }),
    );
  }

  advanceScheduleAfterTerminalOutcome(
    transaction: Transaction,
    attempt: DueWateringAttempt,
    claim: DocumentSnapshot,
    scheduleRef: DocumentReference,
    schedule: DocumentSnapshot,
    settings: DocumentSnapshot,
    preference: DocumentSnapshot,
  ): void {
    if (
      claim.get("scheduleFinalized") === true ||
      !schedule.exists ||
      !settings.exists ||
      schedule.get("ownerUid") !== attempt.ownerUid ||
      schedule.get("dueDate") !== attempt.dueDate ||
      schedule.get("notificationCandidateActive") !== true ||
      settings.get("wateringEnabled") !== true ||
      (preference.exists && preference.get("enabled") === false)
    ) return;
    const scheduleRevision = notificationRevision(schedule);
    const settingsRevision = notificationRevision(settings);
    const preferenceRevision = preference.exists ? notificationRevision(preference) : 0;
    const authorizedScheduleRevision = claim.get("authorizedScheduleRevision");
    const authorizedSettingsRevision = claim.get("authorizedSettingsRevision");
    const authorizedPreferenceRevision = claim.get("authorizedPreferenceRevision");
    const authorizedPreferenceExists = claim.get("authorizedPreferenceExists");
    const authorizedZoneId = claim.get("authorizedZoneId");
    const authorizedReminderTime = claim.get("authorizedReminderTime");
    if (
      scheduleRevision === null ||
      settingsRevision === null ||
      preferenceRevision === null ||
      typeof authorizedScheduleRevision !== "number" ||
      typeof authorizedSettingsRevision !== "number" ||
      typeof authorizedPreferenceRevision !== "number" ||
      typeof authorizedPreferenceExists !== "boolean" ||
      typeof authorizedZoneId !== "string" ||
      typeof authorizedReminderTime !== "string"
    ) return;
    const currentZoneId = settings.get("zoneId");
    const currentDefaultTime = settings.get("defaultTime");
    const currentOverride = preference.get("timeOverride");
    if (typeof currentZoneId !== "string" || typeof currentDefaultTime !== "string") return;
    const currentReminderTime =
      typeof currentOverride === "string" ? currentOverride : currentDefaultTime;
    const authorizationStillCurrent =
      scheduleRevision === authorizedScheduleRevision &&
      settingsRevision === authorizedSettingsRevision &&
      preferenceRevision === authorizedPreferenceRevision &&
      preference.exists === authorizedPreferenceExists;
    const zoneId = authorizationStillCurrent ? authorizedZoneId : currentZoneId;
    const reminderTime = authorizationStillCurrent
      ? authorizedReminderTime
      : currentReminderTime;
    if (!/^(0\d|1\d|2[0-3]):[0-5]\d$/.test(reminderTime)) return;
    const metadata = {
      revision: scheduleRevision + 1,
      expectedRevision: scheduleRevision,
      idempotencyKey: `delivery-${deliveryDocumentId(attempt.deduplicationKey)}`,
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (attempt.attempt === 0) {
      let nextNotificationAt: Timestamp;
      try {
        nextNotificationAt = Timestamp.fromDate(
          localDateTimeToInstant(addLocalDays(attempt.dueDate, 1), reminderTime, zoneId),
        );
      } catch {
        return;
      }
      transaction.set(
        scheduleRef,
        {
          ...metadata,
          notificationCandidateActive: true,
          nextNotificationAt,
        },
        { merge: true },
      );
    } else {
      transaction.set(
        scheduleRef,
        {
          ...metadata,
          notificationCandidateActive: false,
          nextNotificationAt: FieldValue.delete(),
        },
        { merge: true },
      );
    }
  }

  private receiptRef(attempt: DueWateringAttempt) {
    return this.firestore.doc(
      `users/${attempt.ownerUid}/notificationDeliveries/${deliveryDocumentId(attempt.deduplicationKey)}`,
    );
  }
}

export class FirebaseWateringPushSender implements WateringPushSender {
  constructor(private readonly messaging: Messaging) {}

  async send(
    attempt: DueWateringAttempt,
    endpoints: readonly WateringEndpointTarget[],
    deliveryId: string,
  ): Promise<readonly EndpointDeliveryResult[]> {
    const response = await this.messaging.sendEachForMulticast({
      tokens: endpoints.map((endpoint) => endpoint.token),
      data: {
        title: `${attempt.plantName} 물 주기`,
        body:
          attempt.attempt === 0
            ? "오늘 물 줄 날이에요. 관리 기록을 확인해 주세요."
            : "어제 예정된 물 주기를 아직 완료하지 않았어요.",
        type: "WATERING_DUE",
        ownerUid: attempt.ownerUid,
        plantId: attempt.plantId,
        dueDate: attempt.dueDate,
        attempt: String(attempt.attempt),
        route: `planterior://collection/plant/${attempt.plantId}?deliveryId=${deliveryId}`,
        deliveryId,
      },
      android: { priority: "high" },
    });
    return endpoints.map((endpoint, index) => {
      const result = response.responses[index];
      if (result === undefined) {
        return {
          ...endpoint,
          success: false,
          permanent: false,
          errorCode: "messaging/missing-response",
        };
      }
      const errorCode = result.error?.code;
      return {
        ...endpoint,
        success: result.success,
        permanent: errorCode === "messaging/registration-token-not-registered" ||
          errorCode === "messaging/invalid-registration-token",
        ...(errorCode === undefined ? {} : { errorCode }),
      };
    });
  }
}

const RECOVERABLE_CLAIM_STATES = new Set([
  "CLAIMED",
  "AUTHORIZED_PRE_SEND",
  "SEND_MAY_HAVE_OCCURRED",
  "SENDING",
]);

type RecoveryClaimIdentity = Readonly<{
  ownerUid: string;
  plantId: string;
  dueDate: string;
  attempt: 0 | 1;
  claimId: string;
  deduplicationKey: string;
  attemptValue: DueWateringAttempt;
}>;

function recoveryClaimIdentity(claim: DocumentSnapshot): RecoveryClaimIdentity | null {
  const ownerUid = claim.get("ownerUid");
  const plantId = claim.get("plantId");
  const dueDate = claim.get("dueDate");
  const attempt = claim.get("attempt");
  const claimId = claim.get("claimId");
  const deduplicationKey = claim.get("deduplicationKey");
  if (
    typeof ownerUid !== "string" ||
    !/^[A-Za-z0-9_-]{1,128}$/.test(ownerUid) ||
    typeof plantId !== "string" ||
    !/^[A-Za-z0-9_-]{1,128}$/.test(plantId) ||
    typeof dueDate !== "string" ||
    !validRecoveryDate(dueDate) ||
    (attempt !== 0 && attempt !== 1) ||
    typeof claimId !== "string" ||
    !/^[0-9a-f-]{36}$/.test(claimId) ||
    deduplicationKey !== `${ownerUid}:${plantId}:${dueDate}:${attempt}` ||
    claim.ref.id !== deliveryDocumentId(deduplicationKey)
  ) return null;
  const authorizedZoneId = claim.get("authorizedZoneId");
  const authorizedReminderTime = claim.get("authorizedReminderTime");
  const attemptValue: DueWateringAttempt = {
    ownerUid,
    plantId,
    plantName: "",
    dueDate,
    zoneId: typeof authorizedZoneId === "string" ? authorizedZoneId : "UTC",
    globalEnabled: true,
    defaultTime:
      typeof authorizedReminderTime === "string" ? authorizedReminderTime : "00:00",
    plantEnabled: null,
    timeOverride: null,
    attempt,
    deduplicationKey,
    evaluatedAt: new Date(0),
  };
  return {
    ownerUid,
    plantId,
    dueDate,
    attempt,
    claimId,
    deduplicationKey,
    attemptValue,
  };
}

function validRecoveryDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (match === null) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year &&
    parsed.getUTCMonth() === month - 1 &&
    parsed.getUTCDate() === day;
}

function authorizedEndpointIds(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return [...new Set(value.flatMap((entry) => {
    if (typeof entry !== "object" || entry === null || Array.isArray(entry)) return [];
    const endpointId = (entry as Record<string, unknown>).endpointId;
    return typeof endpointId === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(endpointId)
      ? [endpointId]
      : [];
  }))];
}

function clearClaimLeases(
  transaction: Transaction,
  ownerRefs: readonly DocumentReference[],
  owners: readonly DocumentSnapshot[],
  claimId: unknown,
): void {
  if (typeof claimId !== "string") return;
  owners.forEach((owner, index) => {
    if (!owner.exists) return;
    const leases = activeSendLeases(owner.get("activeSendLeases"));
    if (!(claimId in leases)) return;
    delete leases[claimId];
    transaction.set(
      ownerRefs[index]!,
      {
        activeSendLeases: leases,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
  });
}

function notificationRevision(document: DocumentSnapshot): number | null {
  const revision = document.get("revision");
  return typeof revision === "number" &&
    Number.isSafeInteger(revision) &&
    revision >= 0
    ? revision
    : null;
}

function activeSendLeases(value: unknown): Record<string, Timestamp> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return {};
  return Object.fromEntries(
    Object.entries(value).filter((entry): entry is [string, Timestamp] =>
      entry[1] instanceof Timestamp,
    ),
  );
}

function deliveryDocumentId(key: string): string {
  return createHash("sha256").update(key, "utf8").digest("hex");
}

function notificationTerminalRetention(
  terminalAt: Date,
): Readonly<{ terminalAt: Timestamp; expiresAt: Timestamp }> {
  if (Number.isNaN(terminalAt.valueOf())) {
    throw new TypeError("Notification terminal timestamp is invalid");
  }
  return {
    terminalAt: Timestamp.fromDate(terminalAt),
    expiresAt: Timestamp.fromMillis(terminalAt.valueOf() + NOTIFICATION_RETENTION_MILLIS),
  };
}

function localDateTime(now: Date, zoneId: string): Readonly<{ date: string; time: string }> {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone: zoneId,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  });
  const parts = formatter.formatToParts(now);
  const part = (type: Intl.DateTimeFormatPartTypes): string => {
    const value = parts.find((candidate) => candidate.type === type)?.value;
    if (value === undefined) throw new Error(`Missing ${type} for timezone ${zoneId}`);
    return value;
  };
  return {
    date: `${part("year")}-${part("month")}-${part("day")}`,
    time: `${part("hour")}:${part("minute")}`,
  };
}

function addLocalDays(value: string, days: number): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (match === null) throw new Error("Invalid due date");
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}
