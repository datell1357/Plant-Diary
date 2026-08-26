import { createHash } from "node:crypto";
import type { Firestore } from "firebase-admin/firestore";
import { AccountMutationLockedError } from "./account-mutation-lock.js";
import {
  AnalyticsError,
  type AnalyticsConsentView,
  type AnalyticsEventWriteResult,
} from "./analytics.js";
import {
  FirestoreAnalyticsStore,
  recordServerAnalyticsEvent,
  type ServerAnalyticsEventCommand,
} from "./firestore-analytics-store.js";

export type OwnedServerAnalyticsEventName =
  | "WATERING_NOTIFICATION_SENT"
  | "WATERING_NOTIFICATION_OPENED"
  | "MINI_HOME_LAYOUT_SAVED"
  | "MINI_HOME_SHARE_LINK_CREATED"
  | "ACCOUNT_DELETION_REQUESTED"
  | "ACCOUNT_DELETION_COMPLETED"
  | "ACCOUNT_DELETION_FAILED"
  | "WEATHER_RISK_ALERT_CREATED"
  | "WEATHER_RISK_NOTIFICATION_SENT";

export type ServerAnalyticsOperation = Readonly<{
  ownerUid: string;
  eventName: OwnedServerAnalyticsEventName;
  operationIdentifier: string;
}>;

export type SafeServerAnalyticsResult =
  | Readonly<{ kind: "recorded"; eventId: string; replayed: boolean }>
  | Readonly<{ kind: "consent-off" }>
  | Readonly<{ kind: "locked" }>
  | Readonly<{ kind: "transient" }>;

export type ServerAnalyticsRecorder = (
  operation: ServerAnalyticsOperation,
) => Promise<SafeServerAnalyticsResult>;

type ServerAnalyticsBackend = Readonly<{
  getConsent(ownerUid: string): Promise<AnalyticsConsentView>;
  record(
    command: ServerAnalyticsEventCommand,
  ): Promise<AnalyticsEventWriteResult>;
}>;

export function deterministicServerAnalyticsEventId(
  eventName: OwnedServerAnalyticsEventName,
  operationIdentifier: string,
): string {
  const bytes = createHash("sha256")
    .update("planterior:server-analytics-event:v1\0", "utf8")
    .update(eventName, "utf8")
    .update("\0", "utf8")
    .update(operationIdentifier, "utf8")
    .digest()
    .subarray(0, 16);
  bytes[6] = (bytes[6]! & 0x0f) | 0x50;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function createSafeServerAnalyticsRecorder(
  backend: ServerAnalyticsBackend,
): ServerAnalyticsRecorder {
  return async (operation) => {
    let consent: AnalyticsConsentView;
    try {
      consent = await backend.getConsent(operation.ownerUid);
    } catch {
      return { kind: "transient" };
    }
    if (!consent.granted || consent.commandGeneration < 1) {
      return { kind: "consent-off" };
    }

    const eventId = deterministicServerAnalyticsEventId(
      operation.eventName,
      operation.operationIdentifier,
    );
    try {
      const result = await backend.record({
        ownerUid: operation.ownerUid,
        schemaVersion: 1,
        eventId,
        eventName: operation.eventName,
        consentRevision: consent.commandGeneration,
      });
      return { kind: "recorded", eventId, replayed: result.replayed };
    } catch (error: unknown) {
      if (error instanceof AccountMutationLockedError)
        return { kind: "locked" };
      if (
        error instanceof AnalyticsError &&
        error.code === "failed-precondition"
      ) {
        return { kind: "consent-off" };
      }
      return { kind: "transient" };
    }
  };
}

export function createFirestoreServerAnalyticsRecorder(
  firestore: Firestore,
): ServerAnalyticsRecorder {
  const store = new FirestoreAnalyticsStore(firestore);
  return createSafeServerAnalyticsRecorder({
    getConsent: (ownerUid) => store.getConsent(ownerUid),
    record: (command) => recordServerAnalyticsEvent(firestore, command),
  });
}
