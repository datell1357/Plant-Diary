import assert from "node:assert/strict";
import test from "node:test";
import { executeScheduledWeatherRefresh, OpenWeatherProvider } from "./weather-runtime.js";
import { executeRefreshWeather, type WeatherContext, type WeatherProvider, type WeatherStore } from "./weather-service.js";
import { isWeatherStale, type WeatherSnapshot } from "./weather.js";

const region = {
  regionCode: "kr-seoul",
  regionName: "서울",
  latitude: 37.55,
  longitude: 126.98,
  source: "MANUAL" as const,
};

const observedAt = new Date("2026-08-12T00:00:00Z");
const scheduledSnapshot: WeatherSnapshot = {
  ...region,
  temperatureCelsius: 35,
  humidityPercent: 55,
  precipitationMillimeters: 0,
  observedAt,
  zoneId: "Asia/Seoul",
};

class ScheduledStore implements WeatherStore {
  context: WeatherContext = {
    ownerUid: "user-a",
    zoneId: "Asia/Seoul",
    manualRegion: region,
    deviceRegion: null,
    locationConsent: false,
    locationConsentGeneration: null,
    globalAlertsEnabled: true,
    plantAlerts: new Map([["plant-a", true]]),
    revision: 1,
    plants: [{
      plantId: "plant-a", plantName: "몬스테라",
      minimumTemperatureCelsius: 18, maximumTemperatureCelsius: 30,
      minimumHumidityPercent: 40, maximumHumidityPercent: 70,
    }],
    lastKnownRisks: [],
  };
  stale = false;
  recomputedAt: Date[] = [];
  evaluations: Array<{ stale: boolean; evaluatedAt: Date }> = [];
  consentGenerations: Array<number | null> = [];
  async loadContext() { return this.context; }
  async commitEvaluation(command: {
    stale: boolean;
    evaluatedAt: Date;
    expectedLocationConsentGeneration: number | null;
  }) {
    this.evaluations.push({ stale: command.stale, evaluatedAt: command.evaluatedAt });
    this.consentGenerations.push(command.expectedLocationConsentGeneration);
    return { revision: 2 };
  }
  async recomputeSnapshotStaleness(_ownerUid: string, evaluatedAt: Date) {
    this.recomputedAt.push(evaluatedAt);
    this.stale ||= isWeatherStale(observedAt, evaluatedAt);
    return { stale: this.stale };
  }
  async setLocationConsent(_ownerUid: string, granted: boolean, commandGeneration: number) {
    return { commandGeneration, granted };
  }
  async recoverLocationConsent() {
    return { commandGeneration: 1, granted: false, recovered: true as const };
  }
  async setManualRegion() { return 2; }
  async updateAlerts() { return 2; }
}

test("scheduled timeout finalizes failure with fresh completion clock", async () => {
  const store = new ScheduledStore();
  let instant = new Date("2026-08-12T02:59:00Z");
  const signal = AbortSignal.abort(new Error("deterministic scheduler timeout"));
  const provider: WeatherProvider = {
    async current(_region, receivedSignal) {
      instant = new Date("2026-08-12T03:01:00Z");
      receivedSignal?.throwIfAborted();
      return scheduledSnapshot;
    },
    async search() { return []; },
  };

  await assert.rejects(
    executeScheduledWeatherRefresh("user-a", store, provider, signal, () => instant),
    /Weather provider is unavailable/,
  );
  assert.deepEqual(store.recomputedAt, [new Date("2026-08-12T03:01:00Z")]);
  assert.equal(store.stale, true);
});

test("scheduled success crossing freshness boundary uses completion clock", async () => {
  const store = new ScheduledStore();
  let instant = new Date("2026-08-12T02:59:00Z");
  const provider: WeatherProvider = {
    async current() {
      instant = new Date("2026-08-12T03:01:00Z");
      return scheduledSnapshot;
    },
    async search() { return []; },
  };

  const result = await executeScheduledWeatherRefresh(
    "user-a", store, provider, new AbortController().signal, () => instant,
  );

  assert.equal(result.stale, true);
  assert.deepEqual(store.evaluations, [{
    stale: true,
    evaluatedAt: new Date("2026-08-12T03:01:00Z"),
  }]);
});

test("scheduled repeated failures recompute at each fresh completion without evaluation", async () => {
  const store = new ScheduledStore();
  let instant = new Date("2026-08-12T03:01:00Z");
  const provider: WeatherProvider = {
    async current() {
      instant = new Date(instant.valueOf() + 60_000);
      throw new Error("deterministic provider failure");
    },
    async search() { return []; },
  };
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await assert.rejects(
      executeScheduledWeatherRefresh(
        "user-a", store, provider, new AbortController().signal, () => instant,
      ),
      /Weather provider is unavailable/,
    );
  }

  assert.deepEqual(store.recomputedAt, [
    new Date("2026-08-12T03:02:00Z"),
    new Date("2026-08-12T03:03:00Z"),
  ]);
  assert.equal(store.evaluations.length, 0);
});

test("scheduled device refresh carries the exact granted consent generation", async () => {
  const store = new ScheduledStore();
  store.context = {
    ...store.context,
    manualRegion: null,
    deviceRegion: { ...region, source: "DEVICE" },
    locationConsent: true,
    locationConsentGeneration: 7,
  };
  const provider: WeatherProvider = {
    async current(deviceRegion) {
      return { ...scheduledSnapshot, ...deviceRegion };
    },
    async search() { return []; },
  };

  await executeScheduledWeatherRefresh(
    "user-a",
    store,
    provider,
    new AbortController().signal,
    () => new Date("2026-08-12T02:00:00Z"),
  );

  assert.deepEqual(store.consentGenerations, [7]);
});

test("scheduled and direct callable refresh share completion clock semantics", async () => {
  const scheduledStore = new ScheduledStore();
  const callableStore = new ScheduledStore();
  const completion = new Date("2026-08-12T03:01:00Z");
  const provider: WeatherProvider = {
    async current() { return scheduledSnapshot; },
    async search() { return []; },
  };

  const scheduled = await executeScheduledWeatherRefresh(
    "user-a", scheduledStore, provider, new AbortController().signal, () => completion,
  );
  const callable = await executeRefreshWeather(
    { uid: "user-a" }, { expectedOwnerUid: "user-a" }, callableStore, provider, () => completion,
  );

  assert.deepEqual(scheduled, callable);
  assert.deepEqual(scheduledStore.evaluations, callableStore.evaluations);
});

test("OpenWeather request observes scheduler cancellation without waiting for provider timeout", async () => {
  const controller = new AbortController();
  let entered!: () => void;
  const requestEntered = new Promise<void>((resolve) => { entered = resolve; });
  const requestSignals: AbortSignal[] = [];
  const provider = new OpenWeatherProvider("fixture-key", async (_input, init) => {
    const signal = init?.signal;
    if (signal != null) requestSignals.push(signal);
    entered();
    return await new Promise<Response>((_resolve, reject) => {
      if (signal?.aborted === true) {
        reject(signal.reason);
        return;
      }
      signal?.addEventListener("abort", () => reject(signal.reason), { once: true });
    });
  });

  const pending = provider.current(region, controller.signal);
  await requestEntered;
  controller.abort(new Error("scheduler deadline"));

  await assert.rejects(pending, /Weather provider is unavailable/);
  assert.equal(requestSignals[0]?.aborted, true);
});
