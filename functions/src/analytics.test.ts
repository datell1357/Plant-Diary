import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";
import {
  ANALYTICS_EVENT_NAMES,
  AnalyticsError,
  CLIENT_ANALYTICS_EVENT_NAMES,
  SERVER_ANALYTICS_EVENT_NAMES,
  analyticsEventBatchInputSchema,
  analyticsEventInputSchema,
  executeGetAnalyticsConsent,
  executeRecordClientAnalyticsEvents,
  executeSetAnalyticsConsent,
  type AnalyticsConsentMutation,
  type AnalyticsConsentView,
  type AnalyticsEventBatchWrite,
  type AnalyticsStore,
} from "./analytics.js";

const ownerUid = "user-a";
const eventId = "11111111-1111-4111-8111-111111111111";
const operationId = "analytics-operation-0001";
const defaultConsent: AnalyticsConsentView = {
  schemaVersion: 1,
  granted: false,
  commandGeneration: 0,
  grantedAtEpochMillis: null,
  revokedAtEpochMillis: null,
};

class RecordingAnalyticsStore implements AnalyticsStore {
  readonly consentMutations: AnalyticsConsentMutation[] = [];
  readonly eventBatches: AnalyticsEventBatchWrite[] = [];

  async getConsent(): Promise<AnalyticsConsentView> {
    return defaultConsent;
  }

  async setConsent(command: AnalyticsConsentMutation) {
    this.consentMutations.push(command);
    return {
      ...defaultConsent,
      granted: command.granted,
      commandGeneration: command.commandGeneration,
      replayed: false,
      purgedEventCount: 0,
    };
  }

  async recordEvents(command: AnalyticsEventBatchWrite) {
    this.eventBatches.push(command);
    return {
      results: command.events.map((event) => ({
        eventId: event.eventId,
        accepted: true as const,
        duplicate: false,
      })),
    };
  }
}

const validEvent = {
  schemaVersion: 1 as const,
  eventId,
  eventName: "APP_SESSION_STARTED" as const,
  consentRevision: 1,
};

const validBatch = { ownerUid, events: [validEvent] };

const expectedAnalyticsEventNames = [
  "APP_SESSION_STARTED",
  "IDENTIFICATION_REQUEST_SUBMITTED",
  "IDENTIFICATION_RESULT_AVAILABLE",
  "IDENTIFICATION_FAILED",
  "IDENTIFICATION_RESULT_CONFIRMED",
  "IDENTIFICATION_RESULT_EDITED",
  "PLANT_REGISTRATION_COMPLETED",
  "CARE_INFORMATION_VIEWED",
  "WATERING_NOTIFICATION_SENT",
  "WATERING_NOTIFICATION_OPENED",
  "WATERING_COMPLETED",
  "WEATHER_RISK_ALERT_CREATED",
  "WEATHER_RISK_NOTIFICATION_SENT",
  "WEATHER_RISK_ALERT_VIEWED",
  "MINI_HOME_LAYOUT_SAVED",
  "MINI_HOME_SHARE_LINK_CREATED",
  "MINI_HOME_SHARE_SHEET_OPENED",
  "MINI_HOME_ACQUISITION_SOURCE_VIEWED",
  "SYNC_COMPLETED",
  "SYNC_FAILED",
  "ACCOUNT_DELETION_REQUESTED",
  "ACCOUNT_DELETION_COMPLETED",
  "ACCOUNT_DELETION_FAILED",
] as const;

const expectedClientAnalyticsEventNames = [
  "APP_SESSION_STARTED",
  "IDENTIFICATION_REQUEST_SUBMITTED",
  "IDENTIFICATION_RESULT_AVAILABLE",
  "IDENTIFICATION_FAILED",
  "IDENTIFICATION_RESULT_CONFIRMED",
  "IDENTIFICATION_RESULT_EDITED",
  "PLANT_REGISTRATION_COMPLETED",
  "CARE_INFORMATION_VIEWED",
  "WATERING_COMPLETED",
  "WEATHER_RISK_ALERT_VIEWED",
  "MINI_HOME_SHARE_SHEET_OPENED",
  "MINI_HOME_ACQUISITION_SOURCE_VIEWED",
  "SYNC_COMPLETED",
  "SYNC_FAILED",
] as const;

test("Android source and Functions share callable names, payload keys, and exact event vocabularies", () => {
  const gateway = readFileSync(
    resolve(
      __dirname,
      "../../app/src/main/kotlin/com/planterior/helper/analytics/AnalyticsRemoteGateway.kt",
    ),
    "utf8",
  );
  const models = readFileSync(
    resolve(
      __dirname,
      "../../core/model/src/main/kotlin/com/planterior/helper/core/model/AnalyticsModels.kt",
    ),
    "utf8",
  );
  const getBlock = kotlinFunctionBlock(gateway, "getConsent", "setConsent");
  const setBlock = kotlinFunctionBlock(gateway, "setConsent", "recordEvents");
  const recordBlock = kotlinFunctionBlock(
    gateway,
    "recordEvents",
    "private suspend fun call",
  );
  assert.equal(callableName(getBlock), "getAnalyticsConsent");
  assert.equal(callableName(setBlock), "setAnalyticsConsent");
  assert.equal(callableName(recordBlock), "recordAnalyticsEvent");
  assert.deepEqual(payloadKeys(getBlock), ["ownerUid"]);
  assert.deepEqual(payloadKeys(setBlock), [
    "commandGeneration",
    "granted",
    "operationId",
    "ownerUid",
  ]);
  assert.deepEqual(payloadKeys(recordBlock), [
    "consentRevision",
    "eventId",
    "eventName",
    "events",
    "ownerUid",
    "schemaVersion",
  ]);
  for (const key of ["granted", "commandGeneration"]) {
    assert.match(
      getBlock,
      new RegExp(`required(?:Boolean|Int)\\(\"${key}\"\\)`),
    );
  }
  for (const key of ["granted", "commandGeneration", "replayed"]) {
    assert.match(
      setBlock,
      new RegExp(`required(?:Boolean|Int)\\(\"${key}\"\\)`),
    );
  }
  for (const key of ["results", "eventId", "accepted", "duplicate"]) {
    assert.match(recordBlock, new RegExp(`\"${key}\"`));
  }
  assert.deepEqual(
    kotlinEnumValues(models, "ProductEvent"),
    expectedAnalyticsEventNames,
  );
  assert.deepEqual(
    kotlinEnumValues(models, "ClientProductEvent"),
    expectedClientAnalyticsEventNames,
  );
});

test("analytics contract enumerates the exact lifecycle vocabulary once and marks each origin", () => {
  assert.deepEqual(ANALYTICS_EVENT_NAMES, expectedAnalyticsEventNames);
  assert.deepEqual(
    CLIENT_ANALYTICS_EVENT_NAMES,
    expectedClientAnalyticsEventNames,
  );
  assert.equal(ANALYTICS_EVENT_NAMES.length, 23);
  assert.equal(CLIENT_ANALYTICS_EVENT_NAMES.length, 14);
  assert.equal(
    new Set(ANALYTICS_EVENT_NAMES).size,
    ANALYTICS_EVENT_NAMES.length,
  );
  assert.deepEqual(
    [...CLIENT_ANALYTICS_EVENT_NAMES, ...SERVER_ANALYTICS_EVENT_NAMES].sort(),
    [...ANALYTICS_EVENT_NAMES].sort(),
  );
  assert.equal(
    new Set([...CLIENT_ANALYTICS_EVENT_NAMES, ...SERVER_ANALYTICS_EVENT_NAMES])
      .size,
    ANALYTICS_EVENT_NAMES.length,
  );
});

test("event and batch schemas are strict, owner-bound, and capped at fifty", () => {
  assert.deepEqual(analyticsEventInputSchema.parse(validEvent), validEvent);
  assert.deepEqual(
    analyticsEventBatchInputSchema.parse(validBatch),
    validBatch,
  );
  for (const forbidden of [
    "occurredAt",
    "timestamp",
    "properties",
    "sessionId",
    "installId",
    "deviceId",
    "uid",
  ]) {
    assert.throws(() =>
      analyticsEventInputSchema.parse({
        ...validEvent,
        [forbidden]: "forbidden",
      }),
    );
  }
  for (const invalid of [
    { ...validEvent, schemaVersion: 2 },
    { ...validEvent, eventId: "not-a-uuid" },
    { ...validEvent, consentRevision: 0 },
    { ...validEvent, consentRevision: 1.5 },
    { ...validEvent, eventName: "UNKNOWN" },
  ])
    assert.throws(() => analyticsEventInputSchema.parse(invalid));
  assert.throws(() =>
    analyticsEventBatchInputSchema.parse({ ownerUid, events: [] }),
  );
  assert.throws(() =>
    analyticsEventBatchInputSchema.parse({
      ownerUid,
      events: Array.from({ length: 51 }, () => validEvent),
    }),
  );
  assert.throws(() =>
    analyticsEventBatchInputSchema.parse({ ...validBatch, extra: true }),
  );
});

test("client callable accepts every client event and rejects an entire server-only batch", async () => {
  for (const eventName of CLIENT_ANALYTICS_EVENT_NAMES) {
    const store = new RecordingAnalyticsStore();
    await executeRecordClientAnalyticsEvents(
      { uid: ownerUid },
      { ownerUid, events: [{ ...validEvent, eventName }] },
      store,
    );
    assert.equal(store.eventBatches[0]?.events[0]?.eventName, eventName);
  }
  for (const eventName of SERVER_ANALYTICS_EVENT_NAMES) {
    const store = new RecordingAnalyticsStore();
    await assert.rejects(
      executeRecordClientAnalyticsEvents(
        { uid: ownerUid },
        { ownerUid, events: [validEvent, { ...validEvent, eventName }] },
        store,
      ),
      (error) =>
        error instanceof AnalyticsError && error.code === "permission-denied",
    );
    assert.deepEqual(store.eventBatches, []);
  }
});

test("analytics callables require auth and bind Android ownerUid to Auth uid", async () => {
  const store = new RecordingAnalyticsStore();
  await assert.rejects(
    executeGetAnalyticsConsent(null, { ownerUid }, store),
    (error) =>
      error instanceof AnalyticsError && error.code === "unauthenticated",
  );
  await assert.rejects(
    executeGetAnalyticsConsent(
      { uid: ownerUid },
      { ownerUid: "user-b" },
      store,
    ),
    (error) =>
      error instanceof AnalyticsError && error.code === "permission-denied",
  );
  await assert.rejects(
    executeSetAnalyticsConsent(
      { uid: ownerUid },
      { ownerUid: "user-b", granted: true, commandGeneration: 1, operationId },
      store,
    ),
    (error) =>
      error instanceof AnalyticsError && error.code === "permission-denied",
  );
  await assert.rejects(
    executeRecordClientAnalyticsEvents(
      { uid: ownerUid },
      { ownerUid: "user-b", events: [validEvent] },
      store,
    ),
    (error) =>
      error instanceof AnalyticsError && error.code === "permission-denied",
  );
});

test("consent rejects stale wire names and event payloads reject extra fields", async () => {
  const store = new RecordingAnalyticsStore();
  for (const input of [
    {
      ownerUid,
      granted: true,
      commandGeneration: 1,
      operationId,
      timestamp: "client",
    },
    { ownerUid, granted: true, consentRevision: 1, operationId },
    {
      expectedOwnerUid: ownerUid,
      granted: true,
      commandGeneration: 1,
      operationId,
    },
  ]) {
    await assert.rejects(
      executeSetAnalyticsConsent({ uid: ownerUid }, input, store),
      (error) =>
        error instanceof AnalyticsError && error.code === "invalid-argument",
    );
  }
  await assert.rejects(
    executeRecordClientAnalyticsEvents(
      { uid: ownerUid },
      { ownerUid, events: [{ ...validEvent, properties: {} }] },
      store,
    ),
    (error) =>
      error instanceof AnalyticsError && error.code === "invalid-argument",
  );
  assert.deepEqual(store.consentMutations, []);
  assert.deepEqual(store.eventBatches, []);
});

test("valid Android consent and ordered batch commands derive persistence owner from Auth", async () => {
  const store = new RecordingAnalyticsStore();
  await executeSetAnalyticsConsent(
    { uid: ownerUid },
    { ownerUid, granted: true, commandGeneration: 1, operationId },
    store,
  );
  const second = {
    ...validEvent,
    eventId: "22222222-2222-4222-8222-222222222222",
  };
  const result = await executeRecordClientAnalyticsEvents(
    { uid: ownerUid },
    { ownerUid, events: [validEvent, second] },
    store,
  );
  assert.deepEqual(store.consentMutations, [
    {
      ownerUid,
      granted: true,
      commandGeneration: 1,
      operationId,
    },
  ]);
  assert.deepEqual(store.eventBatches, [
    {
      ownerUid,
      origin: "CLIENT",
      events: [validEvent, second],
    },
  ]);
  assert.deepEqual(
    result.results.map((entry) => entry.eventId),
    [eventId, second.eventId],
  );
});

function kotlinFunctionBlock(
  source: string,
  name: string,
  nextName: string,
): string {
  const start = source.indexOf(`override suspend fun ${name}`);
  const nextMarker = nextName.includes("fun ")
    ? nextName
    : `override suspend fun ${nextName}`;
  const end = source.indexOf(nextMarker, start + 1);
  assert.notEqual(start, -1, `Missing Kotlin function ${name}`);
  assert.notEqual(end, -1, `Missing Kotlin function boundary ${nextName}`);
  return source.slice(start, end);
}

function callableName(block: string): string {
  const match = block.match(/call\(\s*"([A-Za-z]+)"/);
  assert.ok(match, "Missing callable name");
  const name = match[1];
  assert.ok(name !== undefined);
  return name;
}

function payloadKeys(block: string): readonly string[] {
  return [...block.matchAll(/"([A-Za-z][A-Za-z0-9]*)"\s+to\s+/g)]
    .map((match) => match[1])
    .filter((key): key is string => key !== undefined)
    .sort();
}

function kotlinEnumValues(source: string, enumName: string): readonly string[] {
  const start = source.indexOf(`enum class ${enumName}`);
  const bodyStart = source.indexOf("{", start);
  const bodyEnd = source.indexOf("}", bodyStart);
  assert.notEqual(start, -1, `Missing Kotlin enum ${enumName}`);
  assert.notEqual(bodyStart, -1, `Missing Kotlin enum body ${enumName}`);
  assert.notEqual(bodyEnd, -1, `Missing Kotlin enum end ${enumName}`);
  return [
    ...source
      .slice(bodyStart + 1, bodyEnd)
      .matchAll(/^\s*([A-Z][A-Z0-9_]+)(?:\(|,)/gm),
  ]
    .map((match) => match[1])
    .filter((value): value is string => value !== undefined);
}
