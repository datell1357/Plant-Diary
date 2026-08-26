const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const root = path.resolve(__dirname, "../..");
const read = (relative) => fs.readFileSync(path.join(root, relative), "utf8");
const manifest = JSON.parse(
  read("firebase-tests/test/data-safety-matrix.manifest.json"),
);
const matrix = read("docs/data-safety-matrix.md");

const analyticsEventNames = [
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
];
const clientAnalyticsEventNames = new Set([
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
]);

function block(source, start, end) {
  const first = source.indexOf(start);
  assert.notEqual(first, -1, `missing ${start}`);
  const last = source.indexOf(end, first + start.length);
  assert.notEqual(last, -1, `missing ${end}`);
  return source.slice(first, last);
}

function quotedKeys(source) {
  return [...source.matchAll(/"([A-Za-z][A-Za-z0-9]*)"/g)]
    .map((match) => match[1])
    .filter(Boolean)
    .sort();
}

function markdownTable(source, heading) {
  const start = source.indexOf(heading);
  assert.notEqual(start, -1, `missing matrix heading ${heading}`);
  const lines = source.slice(start).split("\n");
  const headerIndex = lines.findIndex((line) => line.startsWith("|"));
  assert.notEqual(headerIndex, -1, `missing matrix table after ${heading}`);
  const tableLines = [];
  for (const line of lines.slice(headerIndex)) {
    if (!line.startsWith("|")) break;
    tableLines.push(line);
  }
  assert.ok(
    tableLines.length >= 3,
    `matrix table after ${heading} has no rows`,
  );
  const cells = (line) =>
    line
      .split("|")
      .slice(1, -1)
      .map((cell) => cell.trim().replaceAll("`", ""));
  const headers = cells(tableLines[0]).map((header) => header.toLowerCase());
  return tableLines
    .slice(2)
    .map((line) =>
      Object.fromEntries(
        cells(line).map((value, index) => [headers[index], value]),
      ),
    );
}

function sourceArray(source, start) {
  return [
    ...block(source, start, "] as const").matchAll(/"([A-Z][A-Z0-9_]+)"/g),
  ]
    .map((match) => match[1])
    .filter(Boolean);
}

test("data-safety matrix has executable evidence for every declared invariant", () => {
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(
    new Set(manifest.invariants.map(({ id }) => id)).size,
    manifest.invariants.length,
  );
  assert.ok(manifest.invariants.length >= 24);
  for (const invariant of manifest.invariants) {
    assert.match(invariant.id, /^[a-z0-9-]+$/);
    assert.ok(
      Array.isArray(invariant.evidence) && invariant.evidence.length > 0,
      invariant.id,
    );
    for (const evidence of invariant.evidence) {
      const source = read(evidence.path);
      assert.ok(
        source.includes(evidence.needle),
        `${invariant.id}: ${evidence.path} lacks ${evidence.needle}`,
      );
    }
  }
});

test("matrix documents the exact uppercase analytics vocabulary, origin, owner wrapper, and retention state", () => {
  const analytics = read("functions/src/analytics.ts");
  const gateway = read(
    "app/src/main/kotlin/com/planterior/helper/analytics/AnalyticsRemoteGateway.kt",
  );
  const inputFields = markdownTable(matrix, "**Client input contract");
  const events = markdownTable(matrix, "### 1.1 Allowlisted event names");
  const retention = markdownTable(
    matrix,
    "## 4. Retention and deletion matrix",
  );

  assert.deepEqual(
    sourceArray(analytics, "export const ANALYTICS_EVENT_NAMES = ["),
    analyticsEventNames,
  );
  assert.deepEqual(
    sourceArray(analytics, "export const CLIENT_ANALYTICS_EVENT_NAMES = ["),
    [...clientAnalyticsEventNames],
  );
  assert.deepEqual(
    inputFields.map((row) => row.field),
    ["schemaVersion", "eventId", "eventName", "consentRevision"],
  );
  assert.equal(events.length, analyticsEventNames.length);
  assert.deepEqual(
    events.map((row) => row["event name"]),
    analyticsEventNames,
  );
  assert.ok(
    events.every((row) => row.origin === "CLIENT" || row.origin === "SERVER"),
  );
  assert.deepEqual(
    events.map((row) => row.origin),
    analyticsEventNames.map((eventName) =>
      clientAnalyticsEventNames.has(eventName) ? "CLIENT" : "SERVER",
    ),
  );
  assert.match(gateway, /"ownerUid" to command\.ownerUid/);
  assert.match(
    analytics,
    /assertOwner\(parsed\.ownerUid, authenticatedOwnerUid\)/,
  );
  assert.match(matrix, /`ownerUid`/);
  assert.match(matrix, /`request\.auth\.uid`/);
  assert.doesNotMatch(
    block(
      analytics,
      "export const analyticsEventInputSchema",
      "export const analyticsEventBatchInputSchema",
    ),
    /ownerUid/,
  );

  assert.equal(retention.length, 12);
  for (const row of retention) {
    assert.doesNotMatch(
      row.status,
      /todo17|unimplemented|not implemented|pending/i,
    );
  }
  const rawAnalytics = retention.find(
    (row) => row["data class"] === "Raw analytics events",
  );
  const original = retention.find(
    (row) => row["data class"] === "Identification originals (normal path)",
  );
  const abandoned = retention.find(
    (row) =>
      row["data class"] ===
      "Identification originals (abandoned, no terminal state reached)",
  );
  const aggregate = retention.find(
    (row) => row["data class"] === "Ownerless analytics daily aggregates",
  );
  assert.ok(rawAnalytics && original && abandoned && aggregate);
  assert.match(rawAnalytics.boundary, /^35 days from occurredAt$/);
  assert.match(rawAnalytics.enforcement, /expiresAt = occurredAt \+ 35d/);
  assert.match(original.boundary, /24h/);
  assert.match(abandoned.boundary, /createdAt \+ 24h/);
  assert.match(aggregate.store, /analyticsDailyAggregates\/\{date\}/);
  assert.match(aggregate.boundary, /expiresAt/);
  assert.match(aggregate.enforcement, /cleanupExpiredAnalyticsRetention/);
  assert.doesNotMatch(
    matrix,
    /Todo17 is unchecked|no analytics event pipeline|Todo17 to implement/i,
  );
});

test("analytics input, Android batch wire, persisted event, and server emitter expose only the closed contract", () => {
  const analytics = read("functions/src/analytics.ts");
  const store = read("functions/src/firestore-analytics-store.ts");
  const gateway = read(
    "app/src/main/kotlin/com/planterior/helper/analytics/AnalyticsRemoteGateway.kt",
  );
  const server = read("functions/src/server-analytics.ts");

  const input = block(
    analytics,
    "export const analyticsEventInputSchema",
    "export const analyticsEventBatchInputSchema",
  );
  for (const field of [
    "schemaVersion",
    "eventId",
    "eventName",
    "consentRevision",
  ]) {
    assert.match(input, new RegExp(`\\b${field}:`));
  }
  assert.doesNotMatch(input, /ownerUid|properties|timestamp|uri|url|token/i);
  const persisted = block(
    store,
    "type StoredEvent",
    "type BatchPersistenceResult",
  );
  assert.deepEqual(
    [...persisted.matchAll(/^ {2}([A-Za-z][A-Za-z0-9]*):/gm)]
      .map((match) => match[1])
      .filter(Boolean)
      .sort(),
    [
      "consentRevision",
      "eventName",
      "expiresAt",
      "occurredAt",
      "schemaVersion",
    ],
  );
  const wire = block(
    gateway,
    "override suspend fun recordEvents",
    "private suspend fun call",
  );
  const wireRequest = block(wire, '"recordAnalyticsEvent"', ".objectMap()");
  assert.deepEqual(quotedKeys(wireRequest), [
    "consentRevision",
    "eventId",
    "eventName",
    "events",
    "ownerUid",
    "recordAnalyticsEvent",
    "schemaVersion",
  ]);
  assert.match(server, /deterministicServerAnalyticsEventId/);
  assert.match(server, /createSafeServerAnalyticsRecorder/);
  assert.doesNotMatch(server, /eventName: .*operationIdentifier/);
});

test("first-party analytics has no vendor SDK dependency", () => {
  const dependencies = JSON.parse(read("functions/package.json")).dependencies;
  const androidCatalog = read("gradle/libs.versions.toml");
  for (const dependency of ["firebase-analytics", "amplitude", "mixpanel"]) {
    assert.equal(Object.hasOwn(dependencies, dependency), false, dependency);
    assert.doesNotMatch(androidCatalog, new RegExp(dependency, "i"));
  }
});

test("forbidden data classes are rejected structurally rather than redacted after persistence", () => {
  const analytics = read("functions/src/analytics.ts");
  const store = read("functions/src/firestore-analytics-store.ts");
  const queue = read(
    "core/database/src/main/kotlin/com/planterior/helper/core/database/DatabaseEntities.kt",
  );
  const contracts = read("functions/src/contracts.ts");
  const persisted = block(
    store,
    "type StoredEvent",
    "type BatchPersistenceResult",
  );
  const queueEntity = block(
    queue,
    "data class AnalyticsEventQueueEntity",
    '@Entity(tableName = "last_sync"',
  );
  const notificationDelivery = block(
    contracts,
    "function validateServerPayload",
    'case "weatherSnapshots"',
  );

  for (const source of [analytics, persisted, queueEntity]) {
    assert.doesNotMatch(
      source,
      /\b(uri|url|latitude|longitude|token|authorization|cookie|base64|memo|note)\b/i,
    );
  }
  assert.match(analytics, /\.strict\(\)/);
  assert.match(store, /hasExactKeys\(value, \[/);
  assert.match(notificationDelivery, /exactFields\(/);
  assert.doesNotMatch(notificationDelivery, /endpointResults/);
});
