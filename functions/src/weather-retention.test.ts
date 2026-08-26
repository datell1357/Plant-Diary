import assert from "node:assert/strict";
import test from "node:test";
import {
  WEATHER_RETENTION_MILLIS,
  terminalWeatherAlertFields,
  weatherRetentionTimestamp,
} from "./weather-retention.js";

const anchor = new Date("2026-08-12T00:00:00.000Z");

test("weather retention timestamps are exactly thirty-five days from their anchor", () => {
  assert.equal(
    weatherRetentionTimestamp(anchor).toMillis(),
    anchor.valueOf() + WEATHER_RETENTION_MILLIS,
  );
});

test("terminal weather alert fields share one deterministic terminal anchor", () => {
  const fields = terminalWeatherAlertFields(anchor);

  assert.equal(fields.terminalAt.toMillis(), anchor.valueOf());
  assert.equal(
    fields.expiresAt.toMillis(),
    fields.terminalAt.toMillis() + WEATHER_RETENTION_MILLIS,
  );
});
