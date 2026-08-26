import assert from "node:assert/strict";
import test from "node:test";
import { ANALYTICS_EVENT_TTL_MILLIS } from "./analytics.js";
import {
  ANALYTICS_AGGREGATE_RETENTION_MILLIS,
  analyticsDailyAggregateExpiresAt,
} from "./firestore-analytics-store.js";

test("daily aggregate expiry is the UTC bucket boundary plus the analytics 35-day retention", () => {
  assert.equal(
    ANALYTICS_AGGREGATE_RETENTION_MILLIS,
    ANALYTICS_EVENT_TTL_MILLIS,
  );
  assert.equal(
    analyticsDailyAggregateExpiresAt("2026-08-19").toDate().toISOString(),
    "2026-09-23T00:00:00.000Z",
  );
});

test("daily aggregate expiry rejects non-canonical or impossible UTC bucket dates", () => {
  for (const date of [
    "2026-8-19",
    "2026-02-29",
    "2026-08-19T00:00:00.000Z",
    "not-a-date",
  ]) {
    assert.throws(
      () => analyticsDailyAggregateExpiresAt(date),
      /UTC bucket date/,
    );
  }
});
