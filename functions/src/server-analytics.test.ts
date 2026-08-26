import assert from "node:assert/strict";
import test from "node:test";
import { AccountMutationLockedError } from "./account-mutation-lock.js";
import { AnalyticsError, type AnalyticsConsentView } from "./analytics.js";
import {
  createSafeServerAnalyticsRecorder,
  deterministicServerAnalyticsEventId,
  type OwnedServerAnalyticsEventName,
} from "./server-analytics.js";
import type { ServerAnalyticsEventCommand } from "./firestore-analytics-store.js";

const granted: AnalyticsConsentView = {
  schemaVersion: 1,
  granted: true,
  commandGeneration: 3,
  grantedAtEpochMillis: 1,
  revokedAtEpochMillis: null,
};

const operation = {
  ownerUid: "owner-a",
  eventName: "WATERING_NOTIFICATION_SENT" as const,
  operationIdentifier: "raw-delivery-identifier-0001",
};

test("typed aggregate path owns both account deletion result events", () => {
  const deletionResults = [
    "ACCOUNT_DELETION_COMPLETED",
    "ACCOUNT_DELETION_FAILED",
  ] as const satisfies readonly OwnedServerAnalyticsEventName[];

  assert.notEqual(
    deterministicServerAnalyticsEventId(deletionResults[0], "deletion-1"),
    deterministicServerAnalyticsEventId(deletionResults[1], "deletion-1"),
  );
});

test("server event UUID is deterministic, domain-separated, and contains no raw identifier", () => {
  const first = deterministicServerAnalyticsEventId(
    operation.eventName,
    operation.operationIdentifier,
  );
  const replay = deterministicServerAnalyticsEventId(
    operation.eventName,
    operation.operationIdentifier,
  );
  const otherDomain = deterministicServerAnalyticsEventId(
    "WATERING_NOTIFICATION_OPENED",
    operation.operationIdentifier,
  );

  assert.equal(replay, first);
  assert.notEqual(otherDomain, first);
  assert.match(
    first,
    /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
  assert.equal(first.includes(operation.operationIdentifier), false);
});

test("safe recorder writes once after consent and exact replay is a duplicate", async () => {
  const stored = new Map<string, ServerAnalyticsEventCommand>();
  const recorder = createSafeServerAnalyticsRecorder({
    async getConsent() {
      return granted;
    },
    async record(command) {
      const replayed = stored.has(command.eventId);
      stored.set(command.eventId, command);
      return {
        eventId: command.eventId,
        accepted: true,
        replayed,
        occurredAtEpochMillis: 1,
        expiresAtEpochMillis: 2,
      };
    },
  });

  const first = await recorder(operation);
  const replay = await recorder(operation);

  assert.equal(first.kind, "recorded");
  assert.deepEqual(replay, {
    kind: "recorded",
    eventId: first.kind === "recorded" ? first.eventId : "unreachable",
    replayed: true,
  });
  assert.equal(stored.size, 1);
  assert.equal(
    JSON.stringify([...stored.values()]).includes(
      operation.operationIdentifier,
    ),
    false,
  );
});

test("safe recorder explicitly classifies consent-off, locked, and transient outcomes", async () => {
  let writes = 0;
  const consentOff = createSafeServerAnalyticsRecorder({
    async getConsent() {
      return { ...granted, granted: false };
    },
    async record() {
      writes += 1;
      throw new Error("unreachable");
    },
  });
  assert.deepEqual(await consentOff(operation), { kind: "consent-off" });
  assert.equal(writes, 0);

  for (const [error, expected] of [
    [new AccountMutationLockedError(operation.ownerUid), "locked"],
    [new Error("store unavailable"), "transient"],
    [
      new AnalyticsError("failed-precondition", "consent changed"),
      "consent-off",
    ],
  ] as const) {
    const recorder = createSafeServerAnalyticsRecorder({
      async getConsent() {
        return granted;
      },
      async record() {
        throw error;
      },
    });
    assert.deepEqual(await recorder(operation), { kind: expected });
  }

  const readFailure = createSafeServerAnalyticsRecorder({
    async getConsent() {
      throw new Error("consent store unavailable");
    },
    async record() {
      throw new Error("unreachable");
    },
  });
  assert.deepEqual(await readFailure(operation), { kind: "transient" });
});
