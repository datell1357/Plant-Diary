import assert from "node:assert/strict";
import test from "node:test";
import {
  executeRefreshWeather,
  executeSearchWeatherRegions,
  executeSetLocationConsent,
  executeSetManualWeatherRegion,
  executeUpdateWeatherAlerts,
  type WeatherContext,
  type WeatherProvider,
  type WeatherStore,
} from "./weather-service.js";
import { isWeatherStale, WeatherError, type WeatherRegion, type WeatherSnapshot } from "./weather.js";

const now = new Date("2026-08-12T03:00:00Z");
const manual: WeatherRegion = { regionCode: "kr-seoul", regionName: "서울", latitude: 37.55, longitude: 126.98, source: "MANUAL" };
const device: WeatherRegion = { regionCode: "device-a", regionName: "현재 위치 주변", latitude: 35.18, longitude: 129.08, source: "DEVICE" };
const snapshot: WeatherSnapshot = {
  regionCode: manual.regionCode,
  regionName: manual.regionName,
  latitude: manual.latitude,
  longitude: manual.longitude,
  temperatureCelsius: 35,
  humidityPercent: 30,
  precipitationMillimeters: 0,
  observedAt: new Date("2026-08-12T02:00:00Z"),
  zoneId: "Asia/Seoul",
};

class FakeStore implements WeatherStore {
  context: WeatherContext = {
    ownerUid: "user-a",
    zoneId: "Asia/Seoul",
    manualRegion: manual,
    deviceRegion: device,
    locationConsent: true,
    locationConsentGeneration: 1,
    globalAlertsEnabled: true,
    plantAlerts: new Map([["plant-a", true]]),
    revision: 2,
    lastKnownRisks: [{
      plantId: "plant-a", plantName: "몬스테라", type: "HIGH_TEMPERATURE",
      reason: "어제 관측 고온", action: "그늘로 옮겨 주세요.",
    }],
    plants: [{
      plantId: "plant-a", plantName: "몬스테라",
      minimumTemperatureCelsius: 18, maximumTemperatureCelsius: 30,
      minimumHumidityPercent: 40, maximumHumidityPercent: 70,
    }],
  };
  commits: unknown[] = [];
  commitFailure: Error | null = null;
  consent: boolean[] = [];
  consentGeneration = 0;
  consentGranted = false;
  consentRecovered = false;
  retainedObservedAt = snapshot.observedAt;
  retainedStale = false;
  failureRecomputations: Date[] = [];
  async loadContext() { return this.context; }
  async commitEvaluation(command: unknown) {
    if (this.commitFailure !== null) throw this.commitFailure;
    const evaluation = command as {
      expectedLocationConsentGeneration: number | null;
      region: WeatherRegion;
    };
    if (
      evaluation.region.source === "DEVICE" &&
      (!this.context.locationConsent ||
        evaluation.expectedLocationConsentGeneration !==
          this.context.locationConsentGeneration)
    ) {
      throw new WeatherError("aborted", "Weather location consent changed during refresh");
    }
    this.commits.push(command);
    return { revision: 3 };
  }
  async recomputeSnapshotStaleness(
    _ownerUid: string,
    evaluatedAt: Date,
    expectedLocationConsentGeneration: number | null = null,
  ) {
    if (
      expectedLocationConsentGeneration !== null &&
      (!this.context.locationConsent ||
        expectedLocationConsentGeneration !== this.context.locationConsentGeneration)
    ) {
      throw new WeatherError("aborted", "Weather location consent changed during refresh");
    }
    this.failureRecomputations.push(evaluatedAt);
    this.retainedStale = this.retainedStale || isWeatherStale(this.retainedObservedAt, evaluatedAt);
    return { stale: this.retainedStale };
  }
  async setLocationConsent(_ownerUid: string, granted: boolean, commandGeneration: number) {
    if (commandGeneration === this.consentGeneration) {
      if (granted !== this.consentGranted) {
        throw new WeatherError("aborted", "Consent generation replay payload changed");
      }
      return { commandGeneration: this.consentGeneration, granted: this.consentGranted };
    }
    if (commandGeneration !== this.consentGeneration + 1) {
      throw new WeatherError("aborted", "Consent generation changed; reload required");
    }
    this.consent.push(granted);
    this.consentGeneration = commandGeneration;
    this.consentGranted = granted;
    this.consentRecovered = false;
    this.context = {
      ...this.context,
      locationConsent: granted,
      locationConsentGeneration: commandGeneration,
    };
    return { commandGeneration: this.consentGeneration, granted: this.consentGranted };
  }
  async recoverLocationConsent(_ownerUid: string) {
    if (this.consentRecovered && this.consentGeneration === 1 && !this.consentGranted) {
      return { commandGeneration: 1, granted: false, recovered: true as const };
    }
    if (this.consentGeneration !== Number.MAX_SAFE_INTEGER) {
      throw new WeatherError("aborted", "Consent recovery is not available");
    }
    this.consentGeneration = 1;
    this.consentGranted = false;
    this.consentRecovered = true;
    this.context = {
      ...this.context,
      locationConsent: false,
      locationConsentGeneration: null,
    };
    return { commandGeneration: 1, granted: false, recovered: true as const };
  }
  async setManualRegion(_ownerUid: string, region: WeatherRegion, _expectedRevision: number) { this.context = { ...this.context, manualRegion: region }; return 3; }
  async updateAlerts(_ownerUid: string, _global: boolean, _plants: ReadonlyMap<string, boolean>, _expectedRevision: number) { return 3; }
}

class FakeProvider implements WeatherProvider {
  requested: WeatherRegion[] = [];
  async current(region: WeatherRegion) { this.requested.push(region); return { ...snapshot, regionCode: region.regionCode, regionName: region.regionName }; }
  async search() { return [manual]; }
}

test("refresh derives owner from auth, keeps manual priority, evaluates all risks and commits canonical data", async () => {
  const store = new FakeStore();
  const provider = new FakeProvider();
  const result = await executeRefreshWeather({ uid: "user-a" }, { expectedOwnerUid: "user-a", location: { latitude: 33.5, longitude: 126.5 } }, store, provider, now);
  assert.deepEqual(provider.requested, [manual]);
  assert.deepEqual(result.risks.map((risk) => risk.type), ["HIGH_TEMPERATURE", "DRY"]);
  assert.equal(result.stale, false);
  assert.equal(store.commits.length, 1);
});

test("evaluation carries authoritative unavailable criteria ids into transactional commit", async () => {
  const store = new FakeStore();
  store.context = {
    ...store.context,
    plants: [
      ...store.context.plants,
      {
        plantId: "plant-b", plantName: "선인장",
        minimumTemperatureCelsius: null, maximumTemperatureCelsius: null,
        minimumHumidityPercent: null, maximumHumidityPercent: null,
      },
    ],
  };

  const result = await executeRefreshWeather(
    { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, new FakeProvider(), now,
  );
  const command = store.commits[0] as {
    unavailablePlantIds: string[];
    plants: Array<{ plantId: string }>;
  };

  assert.deepEqual(result.unavailablePlantIds, ["plant-b"]);
  assert.deepEqual(command.unavailablePlantIds, ["plant-b"]);
  assert.deepEqual(command.plants.map((plant) => plant.plantId), ["plant-a", "plant-b"]);
});

test("stale observations retain timestamped last-known risks without creating fresh candidates", async () => {
  const store = new FakeStore();
  const provider = new FakeProvider();
  provider.current = async () => ({ ...snapshot, observedAt: new Date("2026-08-11T23:59:59Z") });
  const result = await executeRefreshWeather({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, provider, now);
  assert.equal(result.stale, true);
  assert.deepEqual(result.risks.map((risk) => risk.reason), ["어제 관측 고온"]);
  assert.deepEqual((store.commits[0] as { risks: unknown[] }).risks, []);
});

test("stale retention never resurrects deleted or non-owned plant risks", async () => {
  const store = new FakeStore();
  store.context = {
    ...store.context,
    lastKnownRisks: [
      ...store.context.lastKnownRisks,
      { plantId: "deleted-plant", plantName: "삭제 식물", type: "DRY", reason: "old", action: "none" },
    ],
  };
  const provider = new FakeProvider();
  provider.current = async () => ({ ...snapshot, observedAt: new Date("2026-08-11T23:59:59Z") });

  const result = await executeRefreshWeather(
    { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, provider, now,
  );

  assert.deepEqual(result.risks.map((risk) => risk.plantId), ["plant-a"]);
});

test("provider failures remain typed and never overwrite the last successful snapshot", async () => {
  const store = new FakeStore();
  const provider = new FakeProvider();
  provider.current = async () => { throw new Error("provider secret response"); };
  await assert.rejects(
    executeRefreshWeather({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, provider, now),
    (error) => error instanceof WeatherError && error.code === "unavailable" && !error.message.includes("secret"),
  );
  assert.equal(store.commits.length, 0);
});

test("provider failure crossing 2h59 to 3h01 recomputes retained snapshot as stale", async () => {
  const store = new FakeStore();
  store.retainedObservedAt = new Date("2026-08-12T00:00:00Z");
  const provider = new FakeProvider();
  let instant = new Date("2026-08-12T02:59:00Z");
  provider.current = async () => {
    instant = new Date("2026-08-12T03:01:00Z");
    throw new Error("provider unavailable");
  };

  await assert.rejects(
    executeRefreshWeather(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a" },
      store,
      provider,
      () => instant,
    ),
    (error) => error instanceof WeatherError && error.code === "unavailable",
  );

  assert.equal(store.retainedStale, true);
  assert.deepEqual(store.failureRecomputations, [new Date("2026-08-12T03:01:00Z")]);
  assert.equal(store.commits.length, 0);
  assert.deepEqual(store.context.lastKnownRisks.map((risk) => risk.reason), ["어제 관측 고온"]);
});

test("repeated provider failure leaves retained stale data idempotent and creates no evaluation", async () => {
  const store = new FakeStore();
  store.retainedObservedAt = new Date("2026-08-12T00:00:00Z");
  const provider = new FakeProvider();
  provider.current = async () => { throw new WeatherError("unavailable", "typed provider failure"); };
  const clock = () => new Date("2026-08-12T03:01:00Z");

  for (let attempt = 0; attempt < 2; attempt += 1) {
    await assert.rejects(
      executeRefreshWeather(
        { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, provider, clock,
      ),
      (error) => error instanceof WeatherError && error.code === "unavailable",
    );
  }

  assert.equal(store.retainedStale, true);
  assert.equal(store.failureRecomputations.length, 2);
  assert.equal(store.commits.length, 0);
  assert.equal(store.context.lastKnownRisks.length, 1);
});

test("failure staleness uses absolute time across local day and timezone boundary", async () => {
  const store = new FakeStore();
  store.context = { ...store.context, zoneId: "Asia/Seoul" };
  store.retainedObservedAt = new Date("2026-08-12T14:59:00Z");
  const provider = new FakeProvider();
  let instant = new Date("2026-08-12T17:58:00Z");
  provider.current = async () => {
    instant = new Date("2026-08-12T18:01:00Z");
    throw new Error("next local day failure");
  };

  await assert.rejects(
    executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, provider, () => instant,
    ),
    (error) => error instanceof WeatherError && error.code === "unavailable",
  );

  assert.equal(store.retainedStale, true);
  assert.deepEqual(store.failureRecomputations, [new Date("2026-08-12T18:01:00Z")]);
});

test("provider failure preserves a previously stale snapshot and risks", async () => {
  const store = new FakeStore();
  store.retainedObservedAt = new Date("2026-08-11T23:00:00Z");
  store.retainedStale = true;
  const retainedRisks = store.context.lastKnownRisks;
  const provider = new FakeProvider();
  provider.current = async () => { throw new Error("provider unavailable"); };

  await assert.rejects(
    executeRefreshWeather(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a" },
      store,
      provider,
      () => new Date("2026-08-12T03:01:00Z"),
    ),
    (error) => error instanceof WeatherError && error.code === "unavailable",
  );

  assert.equal(store.retainedStale, true);
  assert.strictEqual(store.context.lastKnownRisks, retainedRisks);
  assert.equal(store.commits.length, 0);
});

test("consent accepts exact-next and exact replay but rejects altered replay and jumps", async () => {
  const store = new FakeStore();
  assert.deepEqual(
    await executeSetLocationConsent(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", granted: true, commandGeneration: 1 },
      store,
    ),
    { commandGeneration: 1, granted: true },
  );
  assert.deepEqual(
    await executeSetLocationConsent(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", granted: true, commandGeneration: 1 },
      store,
    ),
    { commandGeneration: 1, granted: true },
  );
  await assert.rejects(
    executeSetLocationConsent(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", granted: false, commandGeneration: 1 },
      store,
    ),
    (error) => error instanceof WeatherError && error.code === "aborted",
  );
  for (const commandGeneration of [3, Number.MAX_SAFE_INTEGER]) {
    await assert.rejects(
      executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: false, commandGeneration },
        store,
      ),
      (error) => error instanceof WeatherError && error.code === "aborted",
    );
  }
  assert.equal(store.consentGeneration, 1);
  assert.equal(store.consentGranted, true);
});

test("consent rejects negative float and unsafe generations before mutation", async () => {
  const store = new FakeStore();
  for (const commandGeneration of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
    await assert.rejects(
      executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: true, commandGeneration },
        store,
      ),
      (error) => error instanceof WeatherError && error.code === "invalid-argument",
    );
  }
  assert.equal(store.consentGeneration, 0);
  assert.deepEqual(store.consent, []);
});

test("legacy exhausted consent recovery is revoke-only and idempotent", async () => {
  const store = new FakeStore();
  store.consentGeneration = Number.MAX_SAFE_INTEGER;
  store.consentGranted = true;
  assert.deepEqual(
    await executeSetLocationConsent(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", granted: false, recoverLegacy: true },
      store,
    ),
    { commandGeneration: 1, granted: false, recovered: true },
  );
  assert.deepEqual(
    await executeSetLocationConsent(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", granted: false, recoverLegacy: true },
      store,
    ),
    { commandGeneration: 1, granted: false, recovered: true },
  );
  await assert.rejects(
    executeSetLocationConsent(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", granted: true, recoverLegacy: true },
      store,
    ),
    (error) => error instanceof WeatherError && error.code === "invalid-argument",
  );
});

test("location consent revocation is authoritative and blocks current-location refresh", async () => {
  const store = new FakeStore();
  await executeSetLocationConsent(
    { uid: "user-a" },
    { expectedOwnerUid: "user-a", granted: false, commandGeneration: 1 },
    store,
  );
  assert.deepEqual(store.consent, [false]);
  store.context = { ...store.context, manualRegion: null, locationConsent: false };
  await assert.rejects(
    executeRefreshWeather({ uid: "user-a" }, { expectedOwnerUid: "user-a", location: { latitude: 37.5, longitude: 127 } }, store, new FakeProvider(), now),
    (error) => error instanceof WeatherError && error.code === "failed-precondition",
  );
});

test("device switch binds commit to the exact granted consent generation read before provider", async () => {
  const store = new FakeStore();
  store.consentGeneration = 1;
  store.consentGranted = true;
  let providerEntered!: () => void;
  const entered = new Promise<void>((resolve) => { providerEntered = resolve; });
  let releaseProvider!: () => void;
  const released = new Promise<void>((resolve) => { releaseProvider = resolve; });
  const pausedProvider: WeatherProvider = {
    async current(region) {
      providerEntered();
      await released;
      return { ...snapshot, regionCode: region.regionCode, regionName: region.regionName };
    },
    async search() { return []; },
  };

  const pending = executeRefreshWeather(
    { uid: "user-a" },
    {
      expectedOwnerUid: "user-a",
      location: { latitude: 37.56, longitude: 126.98 },
      switchToDeviceRegion: true,
    },
    store,
    pausedProvider,
    now,
  );
  await entered;
  await store.setLocationConsent("user-a", false, 2);
  releaseProvider();

  await assert.rejects(
    pending,
    (error) =>
      error instanceof WeatherError &&
      error.code === "aborted" &&
      error.message === "Weather location consent changed during refresh",
  );
  assert.equal(store.commits.length, 0);
  assert.equal(store.context.manualRegion?.source, "MANUAL");
});

test("device provider failure reports consent change and does not recompute snapshot after revoke", async () => {
  const store = new FakeStore();
  store.consentGeneration = 1;
  store.consentGranted = true;
  const provider: WeatherProvider = {
    async current() {
      await store.setLocationConsent("user-a", false, 2);
      throw new WeatherError("unavailable", "provider unavailable");
    },
    async search() { return []; },
  };

  await assert.rejects(
    executeRefreshWeather(
      { uid: "user-a" },
      {
        expectedOwnerUid: "user-a",
        location: { latitude: 37.56, longitude: 126.98 },
        switchToDeviceRegion: true,
      },
      store,
      provider,
      now,
    ),
    (error) =>
      error instanceof WeatherError &&
      error.code === "aborted" &&
      error.message === "Weather location consent changed during refresh",
  );
  assert.equal(store.failureRecomputations.length, 0);
  assert.equal(store.commits.length, 0);
});

test("explicit current location switches Busan manual source to Seoul device source", async () => {
  const store = new FakeStore();
  const busan: WeatherRegion = {
    regionCode: "kr-busan",
    regionName: "부산",
    latitude: 35.18,
    longitude: 129.08,
    source: "MANUAL",
  };
  store.context = { ...store.context, manualRegion: busan };
  const provider = new FakeProvider();

  await executeRefreshWeather(
    { uid: "user-a" },
    {
      expectedOwnerUid: "user-a",
      location: { latitude: 37.56, longitude: 126.98 },
      switchToDeviceRegion: true,
    },
    store,
    provider,
    now,
  );

  assert.equal(provider.requested[0]?.source, "DEVICE");
  assert.equal(provider.requested[0]?.latitude, 37.56);
  const command = store.commits[0] as {
    switchToDeviceRegion: boolean;
    expectedLocationConsentGeneration: number | null;
    region: WeatherRegion;
  };
  assert.equal(command.switchToDeviceRegion, true);
  assert.equal(command.expectedLocationConsentGeneration, 1);
  assert.equal(command.region.source, "DEVICE");
});

test("device source switch provider failure leaves manual source authoritative", async () => {
  const store = new FakeStore();
  const failingProvider: WeatherProvider = {
    async current() { throw new WeatherError("unavailable", "provider unavailable"); },
    async search() { return []; },
  };
  await assert.rejects(
    executeRefreshWeather(
      { uid: "user-a" },
      {
        expectedOwnerUid: "user-a",
        location: { latitude: 37.56, longitude: 126.98 },
        switchToDeviceRegion: true,
      },
      store,
      failingProvider,
      now,
    ),
    (error) => error instanceof WeatherError && error.code === "unavailable",
  );
  assert.equal(store.commits.length, 0);
  assert.equal(store.context.manualRegion?.source, "MANUAL");
});

test("device source switch conflict leaves manual source authoritative", async () => {
  const store = new FakeStore();
  store.commitFailure = new WeatherError("aborted", "Weather settings changed during refresh");
  const provider = new FakeProvider();
  await assert.rejects(
    executeRefreshWeather(
      { uid: "user-a" },
      {
        expectedOwnerUid: "user-a",
        location: { latitude: 37.56, longitude: 126.98 },
        switchToDeviceRegion: true,
      },
      store,
      provider,
      now,
    ),
    (error) => error instanceof WeatherError && error.code === "aborted",
  );
  assert.equal(store.commits.length, 0);
  assert.equal(store.context.manualRegion?.source, "MANUAL");
});

test("device source switch requires explicit location and manual selection regains priority", async () => {
  const store = new FakeStore();
  const provider = new FakeProvider();
  await assert.rejects(
    executeRefreshWeather(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", switchToDeviceRegion: true },
      store,
      provider,
      now,
    ),
    (error) => error instanceof WeatherError && error.code === "invalid-argument",
  );
  const busan: WeatherRegion = {
    regionCode: "kr-busan",
    regionName: "부산",
    latitude: 35.18,
    longitude: 129.08,
    source: "MANUAL",
  };
  store.context = { ...store.context, manualRegion: busan };
  await executeRefreshWeather(
    { uid: "user-a" },
    { expectedOwnerUid: "user-a", location: { latitude: 37.56, longitude: 126.98 } },
    store,
    provider,
    now,
  );
  assert.equal(provider.requested[0]?.regionName, "부산");
});

test("region search manual save and alert settings reject spoofing and preserve revisions", async () => {
  const store = new FakeStore();
  const provider = new FakeProvider();
  assert.deepEqual(await executeSearchWeatherRegions({ uid: "user-a" }, { expectedOwnerUid: "user-a", query: "서울" }, provider), [manual]);
  assert.deepEqual(await executeSetManualWeatherRegion({ uid: "user-a" }, { expectedOwnerUid: "user-a", region: manual, expectedRevision: 2 }, store), { revision: 3 });
  assert.deepEqual(await executeUpdateWeatherAlerts({ uid: "user-a" }, { expectedOwnerUid: "user-a", globalEnabled: false, plants: [{ plantId: "plant-a", enabled: true }], expectedRevision: 2 }, store), { revision: 3 });
  await assert.rejects(
    executeSearchWeatherRegions({ uid: "user-a" }, { expectedOwnerUid: "user-b", query: "서울" }, provider),
    (error) => error instanceof WeatherError && error.code === "permission-denied",
  );
});
