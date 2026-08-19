import assert from "node:assert/strict";
import test from "node:test";
import {
  WeatherError,
  canonicalWeather,
  evaluatePlantRisks,
  isWeatherStale,
  resolveWeatherRegion,
  shouldDeliverWeatherAlert,
  type PlantEnvironment,
  type WeatherSnapshot,
} from "./weather.js";

const observedAt = new Date("2026-08-12T00:00:00Z");
const snapshot: WeatherSnapshot = {
  regionCode: "kr-seoul-seongdong",
  regionName: "서울 성동구",
  latitude: 37.55,
  longitude: 127.04,
  temperatureCelsius: 35,
  humidityPercent: 30,
  precipitationMillimeters: 0,
  observedAt,
};
const plant: PlantEnvironment = {
  plantId: "plant-a",
  plantName: "몬스테라",
  minimumTemperatureCelsius: 18,
  maximumTemperatureCelsius: 30,
  minimumHumidityPercent: 40,
  maximumHumidityPercent: 70,
};

test("strict environmental boundaries are safe and multiple out-of-range risks are retained", () => {
  assert.deepEqual(
    evaluatePlantRisks({ ...snapshot, temperatureCelsius: 30, humidityPercent: 40 }, plant),
    [],
  );
  assert.deepEqual(
    evaluatePlantRisks(snapshot, plant).map((risk) => risk.type),
    ["HIGH_TEMPERATURE", "DRY"],
  );
  assert.deepEqual(
    evaluatePlantRisks({ ...snapshot, temperatureCelsius: 17, humidityPercent: 71 }, plant).map((risk) => risk.type),
    ["LOW_TEMPERATURE", "OVERHUMID"],
  );
});

test("missing plant targets or incomplete public environment content produce no invented risk", () => {
  assert.deepEqual(
    evaluatePlantRisks(snapshot, { ...plant, maximumTemperatureCelsius: null }),
    [],
  );
});

test("weather becomes stale only after three hours and stale observations suppress alerts", () => {
  assert.equal(isWeatherStale(observedAt, new Date("2026-08-12T03:00:00Z")), false);
  assert.equal(isWeatherStale(observedAt, new Date("2026-08-12T03:00:00.001Z")), true);
  assert.equal(
    shouldDeliverWeatherAlert({
      globalEnabled: true,
      plantEnabled: true,
      stale: true,
      wasActive: false,
      active: true,
      deliveredTransition: null,
      transition: 1,
    }),
    false,
  );
});

test("global weather off overrides plant on and each risk entry transition is delivered once", () => {
  const base = {
    plantEnabled: true,
    stale: false,
    wasActive: false,
    active: true,
    deliveredTransition: null,
    transition: 4,
  };
  assert.equal(shouldDeliverWeatherAlert({ ...base, globalEnabled: false }), false);
  assert.equal(shouldDeliverWeatherAlert({ ...base, globalEnabled: true }), true);
  assert.equal(
    shouldDeliverWeatherAlert({ ...base, globalEnabled: true, deliveredTransition: 4 }),
    false,
  );
  assert.equal(
    shouldDeliverWeatherAlert({ ...base, globalEnabled: true, wasActive: true }),
    false,
  );
});

test("manual region remains authoritative over a late current-location callback", () => {
  const manual = {
    regionCode: "kr-busan",
    regionName: "부산",
    latitude: 35.18,
    longitude: 129.08,
    source: "MANUAL" as const,
  };
  assert.deepEqual(
    resolveWeatherRegion({
      manual,
      currentLocation: { latitude: 37.56, longitude: 126.97 },
      locationConsent: true,
    }),
    manual,
  );
  assert.throws(
    () => resolveWeatherRegion({ manual: null, currentLocation: { latitude: 37.56, longitude: 126.97 }, locationConsent: false }),
    (error) => error instanceof WeatherError && error.code === "failed-precondition",
  );
});

test("OpenWeather payload is normalized to the canonical contract and rejects provider corruption", () => {
  assert.deepEqual(
    canonicalWeather(
      {
        lat: 37.55,
        lon: 127.04,
        timezone: "Asia/Seoul",
        current: { dt: 1786492800, temp: 29.25, humidity: 63, rain: { "1h": 1.2 } },
      },
      { regionCode: "kr-seoul", regionName: "서울", latitude: 37.55, longitude: 127.04, source: "MANUAL" },
    ),
    {
      regionCode: "kr-seoul",
      regionName: "서울",
      latitude: 37.55,
      longitude: 127.04,
      temperatureCelsius: 29.25,
      humidityPercent: 63,
      precipitationMillimeters: 1.2,
      observedAt: new Date("2026-08-12T00:00:00.000Z"),
      zoneId: "Asia/Seoul",
    },
  );
  assert.throws(
    () => canonicalWeather({ current: { dt: 1, temp: Number.NaN, humidity: 20 } }, { regionCode: "x", regionName: "x", latitude: 0, longitude: 0, source: "MANUAL" }),
    (error) => error instanceof WeatherError && error.code === "unavailable",
  );
});
