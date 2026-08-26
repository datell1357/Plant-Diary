import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import type { Messaging } from "firebase-admin/messaging";
import { AccountMutationLockedError } from "./account-mutation-lock.js";
import { FirestoreAnalyticsStore } from "./firestore-analytics-store.js";
import {
  createFirestoreServerAnalyticsRecorder,
  type ServerAnalyticsOperation,
} from "./server-analytics.js";
import {
  executeRefreshWeather,
  executeSetLocationConsent,
} from "./weather-service.js";
import {
  FirestoreWeatherStore,
  deliverPendingWeatherAlerts,
  executeScheduledWeatherRefresh,
  runConfiguredWeatherRefreshScan,
} from "./weather-runtime.js";
import { WEATHER_RETENTION_MILLIS } from "./weather-retention.js";
import { WeatherError, type WeatherSnapshot } from "./weather.js";
import type { WeatherProvider } from "./weather-service.js";

const projectId = "demo-planterior";
const now = new Date("2026-08-12T03:00:00Z");

const provider: WeatherProvider = {
  async current(region) {
    return {
      regionCode: region.regionCode,
      regionName: region.regionName,
      latitude: region.latitude,
      longitude: region.longitude,
      temperatureCelsius: 35,
      humidityPercent: 30,
      precipitationMillimeters: 0,
      observedAt: new Date("2026-08-12T02:00:00Z"),
      zoneId: "Asia/Seoul",
    } satisfies WeatherSnapshot;
  },
  async search() { return []; },
};

const singleRiskProvider: WeatherProvider = {
  async current(region) {
    return { ...(await provider.current(region)), humidityPercent: 55 };
  },
  async search() { return []; },
};

test("configured weather refresh checkpoints repeated provider timeouts and restart continues", async () => {
  const app = initializeApp({ projectId }, "weather-refresh-timeout-checkpoints");
  const firestore = getFirestore(app);
  try {
    await clearRefreshScheduler(firestore);
    await seedConfiguredUsers(firestore, 6);
    let instant = now.valueOf();
    const calls = new Map<string, number>();
    const timeoutSignal = () => AbortSignal.abort(new Error("deterministic ten second timeout"));
    const refresh = async (ownerUid: string, signal: AbortSignal) => {
      calls.set(ownerUid, (calls.get(ownerUid) ?? 0) + 1);
      instant += 10_000;
      signal.throwIfAborted();
    };

    const first = await runConfiguredWeatherRefreshScan(firestore, refresh, {
      pageSize: 100,
      maxPages: 1,
      deadlineMs: 25_000,
      perUserTimeoutMs: 10_000,
      clock: () => new Date(instant),
      createTimeoutSignal: timeoutSignal,
      invocationId: "timeout-checkpoint-first",
    });
    const second = await runConfiguredWeatherRefreshScan(firestore, refresh, {
      pageSize: 100,
      maxPages: 1,
      deadlineMs: 40_000,
      perUserTimeoutMs: 10_000,
      clock: () => new Date(instant),
      createTimeoutSignal: timeoutSignal,
      invocationId: "timeout-checkpoint-restart",
    });

    assert.deepEqual([first.processed, second.processed], [3, 3]);
    assert.equal(first.failed, 3);
    assert.equal(second.failed, 3);
    assert.equal(second.wrapped, true);
    assert.equal(calls.size, 6);
    assert.ok([...calls.values()].every((count) => count === 1));
  } finally {
    await clearRefreshScheduler(firestore);
    await deleteApp(app);
  }
});

test("configured weather refresh interruption after two checkpoints resumes without duplicate success", async () => {
  const app = initializeApp({ projectId }, "weather-refresh-interruption-checkpoints");
  const firestore = getFirestore(app);
  try {
    await clearRefreshScheduler(firestore);
    await seedConfiguredUsers(firestore, 5);
    const calls = new Map<string, number>();
    let checkpoints = 0;
    const refresh = async (ownerUid: string) => {
      calls.set(ownerUid, (calls.get(ownerUid) ?? 0) + 1);
    };

    await assert.rejects(
      runConfiguredWeatherRefreshScan(firestore, refresh, {
        pageSize: 100,
        maxPages: 1,
        now,
        invocationId: "interruption-first",
        afterCheckpoint: async () => {
          checkpoints += 1;
          if (checkpoints === 2) throw new Error("deterministic process interruption");
        },
      }),
      /deterministic process interruption/,
    );
    const resumed = await runConfiguredWeatherRefreshScan(firestore, refresh, {
      pageSize: 100,
      maxPages: 1,
      now: new Date(now.valueOf() + 60_000),
      invocationId: "interruption-restart",
    });

    assert.equal(resumed.processed, 3);
    assert.equal(resumed.wrapped, true);
    assert.equal(calls.size, 5);
    assert.ok([...calls.values()].every((count) => count === 1));
  } finally {
    await clearRefreshScheduler(firestore);
    await deleteApp(app);
  }
});

test("configured weather refresh paginates 501 users without starvation or duplicate side effects", async () => {
  const app = initializeApp({ projectId }, "weather-refresh-pagination-501");
  const firestore = getFirestore(app);
  try {
    await clearRefreshScheduler(firestore);
    await seedConfiguredUsers(firestore, 501);
    const calls = new Map<string, number>();
    const refresh = async (ownerUid: string) => {
      calls.set(ownerUid, (calls.get(ownerUid) ?? 0) + 1);
    };

    const first = await runConfiguredWeatherRefreshScan(firestore, refresh, {
      pageSize: 100,
      maxPages: 5,
      now,
      invocationId: "refresh-501-first",
    });
    const second = await runConfiguredWeatherRefreshScan(firestore, refresh, {
      pageSize: 100,
      maxPages: 5,
      now: new Date(now.valueOf() + 60_000),
      invocationId: "refresh-501-second",
    });

    assert.equal(first.processed, 500);
    assert.equal(first.wrapped, false);
    assert.equal(second.processed, 1);
    assert.equal(second.wrapped, true);
    assert.equal(calls.size, 501);
    assert.ok([...calls.values()].every((count) => count === 1));
  } finally {
    await clearRefreshScheduler(firestore);
    await deleteApp(app);
  }
});

test("configured weather refresh resumes over more than two pages and advances past failures", async () => {
  const app = initializeApp({ projectId }, "weather-refresh-pagination-failures");
  const firestore = getFirestore(app);
  try {
    await clearRefreshScheduler(firestore);
    await seedConfiguredUsers(firestore, 251);
    const calls = new Map<string, number>();
    const refresh = async (ownerUid: string) => {
      calls.set(ownerUid, (calls.get(ownerUid) ?? 0) + 1);
      if (ownerUid === "weather-user-0125") throw new Error("deterministic provider failure");
    };

    const results = [];
    for (let invocation = 0; invocation < 3; invocation += 1) {
      results.push(await runConfiguredWeatherRefreshScan(firestore, refresh, {
        pageSize: 50,
        maxPages: 2,
        now: new Date(now.valueOf() + invocation * 60_000),
        invocationId: `refresh-many-${invocation}`,
      }));
    }

    assert.deepEqual(results.map((result) => result.processed), [100, 100, 51]);
    assert.equal(results.reduce((sum, result) => sum + result.failed, 0), 1);
    assert.equal(results[2]?.wrapped, true);
    assert.equal(calls.size, 251);
    assert.ok([...calls.values()].every((count) => count === 1));
  } finally {
    await clearRefreshScheduler(firestore);
    await deleteApp(app);
  }
});

test("configured weather refresh lease serializes invocations and additions and deletions converge", async () => {
  const app = initializeApp({ projectId }, "weather-refresh-pagination-concurrency");
  const firestore = getFirestore(app);
  try {
    await clearRefreshScheduler(firestore);
    await seedConfiguredUsers(firestore, 120);
    let releaseFirst!: () => void;
    const firstBlocked = new Promise<void>((resolve) => { releaseFirst = resolve; });
    let entered!: () => void;
    const firstEntered = new Promise<void>((resolve) => { entered = resolve; });
    const calls = new Map<string, number>();
    let blocked = false;
    const refresh = async (ownerUid: string) => {
      calls.set(ownerUid, (calls.get(ownerUid) ?? 0) + 1);
      if (!blocked) {
        blocked = true;
        entered();
        await firstBlocked;
      }
    };
    const running = runConfiguredWeatherRefreshScan(firestore, refresh, {
      pageSize: 50,
      maxPages: 1,
      now,
      invocationId: "refresh-concurrent-first",
    });
    await firstEntered;
    const concurrent = await runConfiguredWeatherRefreshScan(firestore, refresh, {
      pageSize: 50,
      maxPages: 1,
      now,
      invocationId: "refresh-concurrent-second",
    });
    await firestore.doc("users/weather-user-0200/weatherSettings/current").set({
      ownerUid: "weather-user-0200", revision: 1,
    });
    await firestore.doc("users/weather-user-0119/weatherSettings/current").delete();
    releaseFirst();
    await running;
    assert.equal(concurrent.busy, true);

    await runConfiguredWeatherRefreshScan(firestore, refresh, {
      pageSize: 100,
      maxPages: 2,
      now: new Date(now.valueOf() + 60_000),
      invocationId: "refresh-concurrent-resume",
    });

    assert.equal(calls.has("weather-user-0119"), false);
    assert.equal(calls.get("weather-user-0200"), 1);
    assert.ok([...calls.values()].every((count) => count === 1));
    assert.equal(calls.size, 120);
  } finally {
    await clearRefreshScheduler(firestore);
    await deleteApp(app);
  }
});

const preSendRaces = [
  {
    name: "plant delete",
    stage: "afterClaim" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("users/user-a/personalPlants/plant-a").delete();
    },
  },
  {
    name: "risk resolve",
    stage: "afterClaim" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore, alert: FirebaseFirestore.DocumentReference) => {
      const riskId = (await alert.get()).get("riskId") as string;
      await firestore.doc(`users/user-a/weatherRisks/${riskId}`).update({ active: false, revision: 2 });
    },
  },
  {
    name: "risk identity replacement",
    stage: "afterClaim" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore, alert: FirebaseFirestore.DocumentReference) => {
      const riskId = (await alert.get()).get("riskId") as string;
      await firestore.doc(`users/user-a/weatherRisks/${riskId}`).update({ transition: 2, type: "DRY", revision: 2 });
    },
  },
  {
    name: "snapshot revision change",
    stage: "afterClaim" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("users/user-a/weatherSnapshots/current").update({ revision: 99 });
    },
  },
  {
    name: "settings revision change",
    stage: "afterClaim" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("users/user-a/weatherSettings/current").update({ revision: 99, globalAlertsEnabled: false });
    },
  },
  {
    name: "plant alert preference disable",
    stage: "afterClaim" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("users/user-a/weatherPlantSettings/plant-a").set({
        ownerUid: "user-a", plantId: "plant-a", enabled: false, revision: 1,
      });
    },
  },
  {
    name: "criteria change makes risk safe",
    stage: "afterClaim" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("plantContents/species-a").update({ maximumTemperatureCelsius: 40, revision: 2 });
    },
  },
  {
    name: "criteria revision tightens threshold",
    stage: "afterClaim" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("plantContents/species-a").update({ maximumTemperatureCelsius: 20, revision: 2 });
    },
  },
  {
    name: "public content becomes missing",
    stage: "afterClaim" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("plantContents/species-a").delete();
    },
  },
  {
    name: "endpoint revoke",
    stage: "beforeSendBoundary" as const,
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("users/user-a/notificationEndpoints/endpoint-a").delete();
    },
  },
] as const;

for (const race of preSendRaces) {
  test(`weather pre-send boundary blocks ${race.name} after claim`, async () => {
    const app = initializeApp({ projectId }, `weather-race-${race.name.replaceAll(" ", "-")}`);
    const firestore = getFirestore(app);
    const store = new FirestoreWeatherStore(firestore);
    try {
      await clear(firestore);
      await seedWeather(firestore);
      await executeRefreshWeather({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now);
      let sends = 0;
      const messaging = {
        async sendEachForMulticast() {
          sends += 1;
          return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
        },
      } as unknown as Messaging;
      let mutated = false;
      const hook = async (alert: FirebaseFirestore.DocumentReference) => {
        if (mutated) return;
        mutated = true;
        await race.mutate(firestore, alert);
      };

      await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now, {
        ...(race.stage === "afterClaim" ? { afterClaim: hook } : {}),
        ...(race.stage === "beforeSendBoundary" ? { beforeSendBoundary: hook } : {}),
      });

      assert.equal(sends, 0);
      const alert = (await firestore.collection("users/user-a/weatherAlerts").get()).docs[0]!;
      assert.notEqual(alert.get("status"), "SENT");
      assert.notEqual(alert.get("status"), "SENT_AMBIGUOUS");
      const risk = await firestore.doc(`users/user-a/weatherRisks/${alert.get("riskId") as string}`).get();
      assert.notEqual(risk.get("deliveredTransition"), 1);
      assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
      if (race.name === "endpoint revoke") {
        await firestore.doc("users/user-a/notificationEndpoints/endpoint-a").set({
          ownerUid: "user-a", installationId: "endpoint-a", token: "token-a",
          notificationsEnabled: true, generation: 1,
        });
        await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now);
        assert.equal(sends, 1);
      }
    } finally {
      await clear(firestore);
      await deleteApp(app);
    }
  });
}

test("fresh pre-send clock retires alert that expires after claim without FCM", async () => {
  const app = initializeApp({ projectId }, "weather-race-expiry-clock");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  const claimAt = new Date("2026-08-12T02:59:00Z");
  let boundaryAt = claimAt;
  const expiringProvider: WeatherProvider = {
    async current(region) {
      return {
        ...(await singleRiskProvider.current(region)),
        observedAt: new Date("2026-08-12T00:00:00Z"),
      };
    },
    async search() { return []; },
  };
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, expiringProvider, claimAt,
    );
    let sends = 0;
    const messaging = {
      async sendEachForMulticast() {
        sends += 1;
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;

    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, claimAt, {
      beforeSendBoundary: async () => { boundaryAt = new Date("2026-08-12T03:01:00Z"); },
      clock: () => boundaryAt,
    });

    const alert = (await firestore.collection("users/user-a/weatherAlerts").get()).docs[0]!;
    assert.equal(sends, 0);
    assert.equal(alert.get("status"), "CANCELLED");
    assert.equal(alert.get("failureKind"), "PRE_SEND_EXPIRED");
    assert.equal(alert.get("terminalAt").toMillis(), boundaryAt.valueOf());
    assert.equal(
      alert.get("expiresAt").toMillis() - alert.get("terminalAt").toMillis(),
      WEATHER_RETENTION_MILLIS,
    );
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
    const risk = await firestore.doc(`users/user-a/weatherRisks/${alert.get("riskId") as string}`).get();
    assert.equal(risk.get("deliveredTransition"), null);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("unchanged weather pre-send authorization sends exactly once", async () => {
  const app = initializeApp({ projectId }, "weather-race-unchanged");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now);
    let sends = 0;
    const messaging = {
      async sendEachForMulticast() {
        sends += 1;
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now);
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now);
    assert.equal(sends, 1);
    const sentAlert = (await firestore.collection("users/user-a/weatherAlerts").get()).docs[0]!;
    assert.equal(sentAlert.get("status"), "SENT");
    assert.equal(sentAlert.get("terminalAt").toMillis(), now.valueOf());
    assert.equal(
      sentAlert.get("expiresAt").toMillis() - sentAlert.get("terminalAt").toMillis(),
      WEATHER_RETENTION_MILLIS,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("weather analytics records redacted created and sent events exactly once after durable seams", async () => {
  const app = initializeApp({ projectId }, "weather-analytics-exactly-once");
  const firestore = getFirestore(app);
  const analyticsStore = new FirestoreAnalyticsStore(firestore);
  const analytics = createFirestoreServerAnalyticsRecorder(firestore);
  const store = new FirestoreWeatherStore(firestore, analytics);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await analyticsStore.setConsent({
      ownerUid: "user-a",
      granted: true,
      commandGeneration: 1,
      operationId: "weather-analytics-consent-grant",
    });
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    const alert = (await firestore.collection("users/user-a/weatherAlerts").get()).docs[0]!;
    const messaging = {
      async sendEachForMulticast() {
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now, { analytics });
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now, { analytics });

    const events = await firestore.collection("users/user-a/analyticsEvents").get();
    assert.deepEqual(
      events.docs.map((event) => event.get("eventName")).sort(),
      ["WEATHER_RISK_ALERT_CREATED", "WEATHER_RISK_NOTIFICATION_SENT"],
    );
    const forbidden = [
      "plant-a",
      "device-a",
      alert.id,
      alert.get("riskId") as string,
    ];
    for (const event of events.docs) {
      assert.match(
        event.id,
        /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      );
      assert.deepEqual(
        Object.keys(event.data()).sort(),
        ["consentRevision", "eventName", "expiresAt", "occurredAt", "schemaVersion"],
      );
      for (const identifier of forbidden) {
        assert.equal(JSON.stringify(event.data()).includes(identifier), false);
      }
    }
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("weather analytics consent off never changes alert creation or successful send", async () => {
  const app = initializeApp({ projectId }, "weather-analytics-consent-off");
  const firestore = getFirestore(app);
  const analytics = createFirestoreServerAnalyticsRecorder(firestore);
  const store = new FirestoreWeatherStore(firestore, analytics);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    const messaging = {
      async sendEachForMulticast() {
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now, { analytics });

    assert.equal(
      (await firestore.collection("users/user-a/weatherAlerts").get()).docs[0]!.get("status"),
      "SENT",
    );
    assert.equal((await firestore.collection("users/user-a/analyticsEvents").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("weather analytics recorder failure is best effort at both durable seams", async () => {
  const app = initializeApp({ projectId }, "weather-analytics-failure");
  const firestore = getFirestore(app);
  const operations: ServerAnalyticsOperation[] = [];
  const analytics = async (operation: ServerAnalyticsOperation) => {
    operations.push(operation);
    throw new Error("deterministic analytics outage");
  };
  const store = new FirestoreWeatherStore(firestore, analytics);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    const messaging = {
      async sendEachForMulticast() {
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now, { analytics });

    assert.equal(
      (await firestore.collection("users/user-a/weatherAlerts").get()).docs[0]!.get("status"),
      "SENT",
    );
    assert.deepEqual(
      operations.map((operation) => operation.eventName),
      ["WEATHER_RISK_ALERT_CREATED", "WEATHER_RISK_NOTIFICATION_SENT"],
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("weather analytics never records sent for ambiguous or rejected transport", async () => {
  const app = initializeApp({ projectId }, "weather-analytics-unsent");
  const firestore = getFirestore(app);
  const operations: ServerAnalyticsOperation[] = [];
  const analytics = async (operation: ServerAnalyticsOperation) => {
    operations.push(operation);
    return { kind: "consent-off" as const };
  };
  const store = new FirestoreWeatherStore(firestore, analytics);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, provider, now,
    );
    let attempts = 0;
    const messaging = {
      async sendEachForMulticast() {
        attempts += 1;
        if (attempts === 1) throw new Error("ambiguous transport");
        return { successCount: 0, failureCount: 1, responses: [{ success: false }] };
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now, { analytics });

    const alerts = await firestore.collection("users/user-a/weatherAlerts").get();
    assert.deepEqual(
      alerts.docs.map((alert) => alert.get("status")).sort(),
      ["FAILED", "SENT_AMBIGUOUS"],
    );
    assert.equal(
      operations.filter((operation) => operation.eventName === "WEATHER_RISK_NOTIFICATION_SENT").length,
      0,
    );
    assert.equal(
      operations.filter((operation) => operation.eventName === "WEATHER_RISK_ALERT_CREATED").length,
      2,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("weather outbox fences the path owner even when stored ownership is malformed", async () => {
  const app = initializeApp({ projectId }, "weather-malformed-owner-fence");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a" },
      store,
      singleRiskProvider,
      now,
    );
    const alert = (await firestore.collection("users/user-a/weatherAlerts").get()).docs[0]!;
    await alert.ref.update({ ownerUid: "user-b" });
    await firestore.doc("accountDeletionRequests/user-a").set({
      status: "PROCESSING",
      completedScopes: [],
    });
    const messaging = {
      async sendEachForMulticast() {
        throw new Error("FCM must not run for a deletion-locked path owner");
      },
    } as unknown as Messaging;

    await assert.rejects(
      deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now),
      AccountMutationLockedError,
    );
    assert.equal((await alert.ref.get()).get("status"), "PENDING");
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("weather emulator recovers leases without duplicate FCM and carries immutable risk identity", async () => {
  const app = initializeApp({ projectId }, "weather-outbox-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, provider, now);
    const alerts = await firestore.collection("users/user-a/weatherAlerts").orderBy("createdAt").get();
    assert.equal(alerts.size, 2);
    const expired = new Date("2026-08-12T02:00:00Z");
    await alerts.docs[0]!.ref.update({ status: "CLAIMED", leaseExpiresAt: expired });
    await alerts.docs[1]!.ref.update({ status: "SEND_MAY_HAVE_OCCURRED", leaseExpiresAt: expired });
    let sends = 0;
    let payload: Readonly<Record<string, string>> | undefined;
    const messaging = {
      async sendEachForMulticast(message: { data?: Readonly<Record<string, string>> }) {
        sends += 1;
        payload = message.data;
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;

    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now);
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now);

    assert.equal(sends, 1);
    assert.ok(payload?.riskId);
    assert.ok(payload?.riskType);
    assert.equal(payload?.transition, "1");
    assert.ok(payload?.alertId);
    assert.equal((await alerts.docs[1]!.ref.get()).get("status"), "SENT_AMBIGUOUS");

    const firstAlert = await alerts.docs[0]!.ref.get();
    await firestore.doc(`users/user-a/weatherRisks/${firstAlert.get("riskId") as string}`).update({
      deliveredTransition: null,
    });
    await firstAlert.ref.update({ status: "PENDING", failureKind: null });
    let failedTransportCalls = 0;
    const failingMessaging = {
      async sendEachForMulticast() {
        failedTransportCalls += 1;
        throw new Error("deterministic FCM failure");
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, failingMessaging, "user-a", 100, now);
    await deliverPendingWeatherAlerts(firestore, failingMessaging, "user-a", 100, now);
    assert.equal(failedTransportCalls, 1);
    assert.equal((await firstAlert.ref.get()).get("status"), "SENT_AMBIGUOUS");
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("provider failure crossing freshness boundary commits stale without new risks or alerts", async () => {
  const app = initializeApp({ projectId }, "weather-failure-staleness-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  let instant = new Date("2026-08-12T02:59:00Z");
  const initialProvider: WeatherProvider = {
    async current(region) {
      return {
        ...(await singleRiskProvider.current(region)),
        observedAt: new Date("2026-08-12T00:00:00Z"),
      };
    },
    async search() { return []; },
  };
  const failingProvider: WeatherProvider = {
    async current() {
      instant = new Date("2026-08-12T03:01:00Z");
      throw new Error("deterministic provider failure");
    },
    async search() { return []; },
  };
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, initialProvider, () => instant,
    );
    const beforeRisks = await firestore.collection("users/user-a/weatherRisks").get();
    const beforeAlerts = await firestore.collection("users/user-a/weatherAlerts").get();
    const riskState = beforeRisks.docs.map((risk) => ({
      id: risk.id,
      transition: risk.get("transition"),
      revision: risk.get("revision"),
      active: risk.get("active"),
    }));

    await assert.rejects(
      executeRefreshWeather(
        { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, failingProvider, () => instant,
      ),
      /Weather provider is unavailable/,
    );
    await assert.rejects(
      executeRefreshWeather(
        { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, failingProvider, () => instant,
      ),
      /Weather provider is unavailable/,
    );

    const snapshotDocument = await firestore.doc("users/user-a/weatherSnapshots/current").get();
    const afterRisks = await firestore.collection("users/user-a/weatherRisks").get();
    const afterAlerts = await firestore.collection("users/user-a/weatherAlerts").get();
    assert.equal(snapshotDocument.get("stale"), true);
    assert.equal(snapshotDocument.get("observedAt").toDate().toISOString(), "2026-08-12T00:00:00.000Z");
    assert.deepEqual(afterRisks.docs.map((risk) => ({
      id: risk.id,
      transition: risk.get("transition"),
      revision: risk.get("revision"),
      active: risk.get("active"),
    })), riskState);
    assert.equal(afterAlerts.size, beforeAlerts.size);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("weather evaluation persists unavailable criteria ids and prunes deleted or non-owned plants", async () => {
  const app = initializeApp({ projectId }, "weather-unavailable-persistence");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await firestore.doc("users/user-a/personalPlants/plant-b").set({
      ownerUid: "user-a", displayName: "선인장", contentId: "missing-species",
    });
    await firestore.doc("users/user-a/personalPlants/foreign-plant").set({
      ownerUid: "user-b", displayName: "외부 식물", contentId: "missing-species",
    });

    const result = await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    assert.deepEqual(result.unavailablePlantIds, ["plant-b"]);
    assert.deepEqual(
      (await firestore.doc("users/user-a/weatherSnapshots/current").get()).get("unavailablePlantIds"),
      ["plant-b"],
    );

    await firestore.doc("users/user-a/personalPlants/plant-b").delete();
    const pruned = await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    assert.deepEqual(pruned.unavailablePlantIds, []);
    assert.deepEqual(
      (await firestore.doc("users/user-a/weatherSnapshots/current").get()).get("unavailablePlantIds"),
      [],
    );
  } finally {
    await clear(firestore);
    await firestore.doc("users/user-a/personalPlants/plant-b").delete();
    await firestore.doc("users/user-a/personalPlants/foreign-plant").delete();
    await deleteApp(app);
  }
});

for (const concurrentMutation of [
  {
    name: "plant deletion",
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("users/user-a/personalPlants/plant-a").delete();
    },
  },
  {
    name: "environment content change",
    mutate: async (firestore: FirebaseFirestore.Firestore) => {
      await firestore.doc("plantContents/species-a").update({ maximumTemperatureCelsius: 40, revision: 2 });
    },
  },
] as const) {
  test(`weather evaluation rejects concurrent ${concurrentMutation.name} before persistence`, async () => {
    const app = initializeApp({ projectId }, `weather-unavailable-race-${concurrentMutation.name.replaceAll(" ", "-")}`);
    const firestore = getFirestore(app);
    const store = new FirestoreWeatherStore(firestore);
    let providerEntered!: () => void;
    let releaseProvider!: () => void;
    const entered = new Promise<void>((resolve) => { providerEntered = resolve; });
    const release = new Promise<void>((resolve) => { releaseProvider = resolve; });
    const delayedProvider: WeatherProvider = {
      async current(region) {
        providerEntered();
        await release;
        return singleRiskProvider.current(region);
      },
      async search() { return []; },
    };
    try {
      await clear(firestore);
      await seedWeather(firestore);
      const pending = executeRefreshWeather(
        { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, delayedProvider, now,
      );
      await entered;
      await concurrentMutation.mutate(firestore);
      releaseProvider();

      await assert.rejects(pending, /Weather plant criteria changed during refresh/);
      assert.equal((await firestore.doc("users/user-a/weatherSnapshots/current").get()).exists, false);
      assert.equal((await firestore.collection("users/user-a/weatherAlerts").get()).size, 0);
    } finally {
      releaseProvider();
      await clear(firestore);
      await deleteApp(app);
    }
  });
}

test("manual to device switch aborts when consent is revoked while provider is paused", async () => {
  const app = initializeApp({ projectId }, "weather-consent-switch-race");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  const paused = pausedWeatherProvider("success");
  try {
    await clear(firestore);
    await seedManualWeather(firestore);
    const pending = executeRefreshWeather(
      { uid: "user-a" },
      {
        expectedOwnerUid: "user-a",
        location: { latitude: 37.56, longitude: 126.98 },
        switchToDeviceRegion: true,
      },
      store,
      paused.provider,
      now,
    );
    await paused.entered;
    await store.setLocationConsent("user-a", false, 2);
    paused.release();

    await assertConsentChanged(pending);
    await assertManualWeatherUnchanged(firestore, 2, false);
  } finally {
    paused.release();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("device provider failure after revoke returns consent changed without snapshot mutation", async () => {
  const app = initializeApp({ projectId }, "weather-consent-provider-failure-race");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  const paused = pausedWeatherProvider("failure");
  try {
    await clear(firestore);
    await seedManualWeather(firestore);
    const pending = executeRefreshWeather(
      { uid: "user-a" },
      {
        expectedOwnerUid: "user-a",
        location: { latitude: 37.56, longitude: 126.98 },
        switchToDeviceRegion: true,
      },
      store,
      paused.provider,
      now,
    );
    await paused.entered;
    await store.setLocationConsent("user-a", false, 2);
    paused.release();

    await assertConsentChanged(pending);
    await assertManualWeatherUnchanged(firestore, 2, false);
  } finally {
    paused.release();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("revocation response loss still blocks paused device switch commit", async () => {
  const app = initializeApp({ projectId }, "weather-consent-response-loss-race");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  const paused = pausedWeatherProvider("success");
  try {
    await clear(firestore);
    await seedManualWeather(firestore);
    const pending = executeRefreshWeather(
      { uid: "user-a" },
      {
        expectedOwnerUid: "user-a",
        location: { latitude: 37.56, longitude: 126.98 },
        switchToDeviceRegion: true,
      },
      store,
      paused.provider,
      now,
    );
    await paused.entered;
    await assert.rejects(async () => {
      await store.setLocationConsent("user-a", false, 2);
      throw new Error("deterministic response loss");
    }, /deterministic response loss/);
    assert.deepEqual(await store.setLocationConsent("user-a", false, 2), {
      commandGeneration: 2,
      granted: false,
    });
    paused.release();

    await assertConsentChanged(pending);
    await assertManualWeatherUnchanged(firestore, 2, false);
  } finally {
    paused.release();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("newer regrant rejects stale paused switch and a new switch uses the newer generation", async () => {
  const app = initializeApp({ projectId }, "weather-consent-regrant-race");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  const paused = pausedWeatherProvider("success");
  try {
    await clear(firestore);
    await seedManualWeather(firestore);
    const stale = executeRefreshWeather(
      { uid: "user-a" },
      {
        expectedOwnerUid: "user-a",
        location: { latitude: 37.56, longitude: 126.98 },
        switchToDeviceRegion: true,
      },
      store,
      paused.provider,
      now,
    );
    await paused.entered;
    await store.setLocationConsent("user-a", false, 2);
    await store.setLocationConsent("user-a", true, 3);
    paused.release();

    await assertConsentChanged(stale);
    await assertManualWeatherUnchanged(firestore, 3, true);

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
    const settings = await firestore.doc("users/user-a/weatherSettings/current").get();
    assert.equal(settings.get("manualRegion"), undefined);
    assert.equal(settings.get("deviceRegion.source"), "DEVICE");
    assert.equal(
      (await firestore.doc("users/user-a/weatherSnapshots/current").get()).get("regionName"),
      "현재 위치 주변",
    );
  } finally {
    paused.release();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("direct and scheduled device refresh reject current denied consent before provider", async () => {
  const app = initializeApp({ projectId }, "weather-device-refresh-no-consent");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  let providerCalls = 0;
  const countingProvider: WeatherProvider = {
    async current(region) {
      providerCalls += 1;
      return provider.current(region);
    },
    async search() { return []; },
  };
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await store.setLocationConsent("user-a", false, 2);
    await firestore.doc("users/user-a/weatherSettings/current").update({
      deviceRegion: {
        regionCode: "device-stale",
        regionName: "철회된 현재 위치",
        latitude: 37.55,
        longitude: 126.98,
        source: "DEVICE",
      },
    });

    await assert.rejects(
      executeRefreshWeather(
        { uid: "user-a" },
        {
          expectedOwnerUid: "user-a",
          location: { latitude: 37.56, longitude: 126.98 },
          switchToDeviceRegion: true,
        },
        store,
        countingProvider,
        now,
      ),
      (error) => error instanceof WeatherError && error.code === "failed-precondition",
    );
    await assert.rejects(
      executeScheduledWeatherRefresh(
        "user-a",
        store,
        countingProvider,
        new AbortController().signal,
        () => now,
      ),
      (error) => error instanceof WeatherError && error.code === "failed-precondition",
    );
    assert.equal(providerCalls, 0);
    assert.equal((await firestore.doc("users/user-a/weatherSnapshots/current").get()).exists, false);
    assert.equal((await firestore.collection("users/user-a/weatherAlerts").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("explicit device switch clears manual source atomically and later manual selection regains priority", async () => {
  const app = initializeApp({ projectId }, "weather-device-source-switch");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  const busan = {
    regionCode: "kr-busan",
    regionName: "부산",
    latitude: 35.18,
    longitude: 129.08,
    source: "MANUAL" as const,
  };
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await firestore.doc("users/user-a/weatherSettings/current").update({ manualRegion: busan });

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
    let settings = await firestore.doc("users/user-a/weatherSettings/current").get();
    assert.equal(settings.get("manualRegion"), undefined);
    assert.equal(settings.get("deviceRegion.source"), "DEVICE");
    assert.equal(
      (await firestore.doc("users/user-a/weatherSnapshots/current").get()).get("regionName"),
      "현재 위치 주변",
    );

    await store.setManualRegion("user-a", busan, 3);
    await executeRefreshWeather(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a" },
      store,
      provider,
      now,
    );
    settings = await firestore.doc("users/user-a/weatherSettings/current").get();
    assert.equal(settings.get("manualRegion.regionName"), "부산");
    assert.equal(
      (await firestore.doc("users/user-a/weatherSnapshots/current").get()).get("regionName"),
      "부산",
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("consent rejects MAX and arbitrary jumps before mutation then accepts exact revoke and replay", async () => {
  const app = initializeApp({ projectId }, "weather-consent-exact-next");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    for (const command of [
      { granted: true, commandGeneration: Number.MAX_SAFE_INTEGER },
      { granted: false, commandGeneration: 3 },
    ]) {
      await assert.rejects(
        executeSetLocationConsent(
          { uid: "user-a" },
          { expectedOwnerUid: "user-a", ...command },
          store,
        ),
        (error) => error instanceof WeatherError && error.code === "aborted",
      );
    }
    for (const commandGeneration of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
      await assert.rejects(
        executeSetLocationConsent(
          { uid: "user-a" },
          { expectedOwnerUid: "user-a", granted: false, commandGeneration },
          store,
        ),
        (error) => error instanceof WeatherError && error.code === "invalid-argument",
      );
    }
    let consent = await firestore.doc("users/user-a/consents/location").get();
    assert.equal(consent.get("commandGeneration"), 1);
    assert.equal(consent.get("granted"), true);

    assert.deepEqual(
      await executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: false, commandGeneration: 2 },
        store,
      ),
      { commandGeneration: 2, granted: false },
    );
    assert.deepEqual(
      await executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: false, commandGeneration: 2 },
        store,
      ),
      { commandGeneration: 2, granted: false },
    );
    await assert.rejects(
      executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: true, commandGeneration: 2 },
        store,
      ),
      (error) => error instanceof WeatherError && error.code === "aborted",
    );
    consent = await firestore.doc("users/user-a/consents/location").get();
    assert.equal(consent.get("commandGeneration"), 2);
    assert.equal(consent.get("granted"), false);
    assert.equal(
      (await firestore.doc("users/user-a/weatherSettings/current").get()).get("deviceRegion"),
      undefined,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("concurrent devices serialize one exact-next payload and conflict the altered command", async () => {
  const app = initializeApp({ projectId }, "weather-consent-concurrent-next");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    const outcomes = await Promise.allSettled([
      executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: true, commandGeneration: 2 },
        store,
      ),
      executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: false, commandGeneration: 2 },
        store,
      ),
    ]);
    assert.equal(outcomes.filter((outcome) => outcome.status === "fulfilled").length, 1);
    const rejected = outcomes.find((outcome) => outcome.status === "rejected");
    assert.ok(
      rejected?.status === "rejected" &&
      rejected.reason instanceof WeatherError &&
      rejected.reason.code === "aborted",
    );
    const consent = await firestore.doc("users/user-a/consents/location").get();
    const granted = consent.get("granted") === true;
    assert.deepEqual(
      await executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted, commandGeneration: 2 },
        store,
      ),
      { commandGeneration: 2, granted },
    );
    await assert.rejects(
      executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: !granted, commandGeneration: 2 },
        store,
      ),
      (error) => error instanceof WeatherError && error.code === "aborted",
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("legacy exhausted granted consent recovers to denied idempotently and blocks schedule", async () => {
  const app = initializeApp({ projectId }, "weather-consent-exhausted-granted");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  let providerCalls = 0;
  const countingProvider: WeatherProvider = {
    async current(region) {
      providerCalls += 1;
      return provider.current(region);
    },
    async search() { return []; },
  };
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await firestore.doc("users/user-a/consents/location").update({
      commandGeneration: Number.MAX_SAFE_INTEGER,
      granted: true,
    });
    await assert.rejects(
      executeSetLocationConsent(
        { uid: "user-a" },
        {
          expectedOwnerUid: "user-a",
          granted: false,
          commandGeneration: Number.MAX_SAFE_INTEGER,
        },
        store,
      ),
      (error) => error instanceof WeatherError && error.code === "aborted",
    );
    const recovery = {
      expectedOwnerUid: "user-a",
      granted: false,
      recoverLegacy: true,
    };
    assert.deepEqual(
      await executeSetLocationConsent({ uid: "user-a" }, recovery, store),
      { commandGeneration: 1, granted: false, recovered: true },
    );
    assert.deepEqual(
      await executeSetLocationConsent({ uid: "user-a" }, recovery, store),
      { commandGeneration: 1, granted: false, recovered: true },
    );
    await assert.rejects(
      executeScheduledWeatherRefresh(
        "user-a",
        store,
        countingProvider,
        new AbortController().signal,
        () => now,
      ),
      (error) => error instanceof WeatherError && error.code === "failed-precondition",
    );
    assert.equal(providerCalls, 0);
    assert.equal(
      (await firestore.doc("users/user-a/weatherSettings/current").get()).get("deviceRegion"),
      undefined,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("legacy exhausted denied consent recovery permits only canonical exact-next regrant", async () => {
  const app = initializeApp({ projectId }, "weather-consent-exhausted-denied");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await firestore.doc("users/user-a/consents/location").update({
      commandGeneration: Number.MAX_SAFE_INTEGER,
      granted: false,
    });
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
        { expectedOwnerUid: "user-a", granted: true, commandGeneration: 3 },
        store,
      ),
      (error) => error instanceof WeatherError && error.code === "aborted",
    );
    assert.deepEqual(
      await executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: true, commandGeneration: 2 },
        store,
      ),
      { commandGeneration: 2, granted: true },
    );
    await firestore.doc("users/user-a/consents/location").update({
      commandGeneration: 1.5,
      granted: true,
    });
    assert.deepEqual(
      await executeSetLocationConsent(
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", granted: false, recoverLegacy: true },
        store,
      ),
      { commandGeneration: 1, granted: false, recovered: true },
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("device provenance is generation bound and revoke converges before delivery", async () => {
  const app = initializeApp({ projectId }, "weather-location-revoke-provenance");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    const [snapshot, risks, alerts] = await Promise.all([
      firestore.doc("users/user-a/weatherSnapshots/current").get(),
      firestore.collection("users/user-a/weatherRisks").get(),
      firestore.collection("users/user-a/weatherAlerts").get(),
    ]);
    assert.equal(snapshot.get("source"), "DEVICE");
    assert.equal(snapshot.get("locationConsentGeneration"), 1);
    assert.ok(risks.docs.every((risk) =>
      risk.get("source") === "DEVICE" && risk.get("locationConsentGeneration") === 1
    ));
    assert.ok(alerts.docs.every((alert) =>
      alert.get("source") === "DEVICE" && alert.get("locationConsentGeneration") === 1
    ));
    await firestore.doc("users/user-a/weatherSettings/current").update({
      manualRegion: {
        regionCode: "kr-busan", regionName: "부산", latitude: 35.18,
        longitude: 129.08, source: "MANUAL",
      },
    });

    await store.setLocationConsent("user-a", false, 2);

    const [settingsAfter, snapshotAfter, risksAfter, alertsAfter] = await Promise.all([
      firestore.doc("users/user-a/weatherSettings/current").get(),
      firestore.doc("users/user-a/weatherSnapshots/current").get(),
      firestore.collection("users/user-a/weatherRisks").get(),
      firestore.collection("users/user-a/weatherAlerts").get(),
    ]);
    assert.equal(settingsAfter.get("deviceRegion"), undefined);
    assert.equal(settingsAfter.get("manualRegion.regionName"), "부산");
    assert.equal(snapshotAfter.exists, false);
    assert.equal(risksAfter.size, 0);
    assert.equal(alertsAfter.size, 0);

    let sends = 0;
    const messaging = {
      async sendEachForMulticast() {
        sends += 1;
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now);
    assert.equal(sends, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("claim after revoke rejects restored stale device generation", async () => {
  const app = initializeApp({ projectId }, "weather-location-revoke-stale-claim");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    const snapshot = await firestore.doc("users/user-a/weatherSnapshots/current").get();
    const risks = await firestore.collection("users/user-a/weatherRisks").get();
    const alerts = await firestore.collection("users/user-a/weatherAlerts").get();
    await store.setLocationConsent("user-a", false, 2);
    await firestore.doc("users/user-a/weatherSnapshots/current").set(snapshot.data()!);
    await Promise.all(risks.docs.map((risk) => risk.ref.set(risk.data())));
    await Promise.all(alerts.docs.map((alert) => alert.ref.set({ ...alert.data(), status: "PENDING" })));

    let sends = 0;
    const messaging = {
      async sendEachForMulticast() {
        sends += 1;
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now);

    assert.equal(sends, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

for (const stage of ["afterClaim", "beforeSendBoundary"] as const) {
  test(`device alert paused at ${stage} cannot cross acknowledged revoke`, async () => {
    const app = initializeApp({ projectId }, `weather-location-revoke-${stage}`);
    const firestore = getFirestore(app);
    const store = new FirestoreWeatherStore(firestore);
    try {
      await clear(firestore);
      await seedWeather(firestore);
      await executeRefreshWeather(
        { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
      );
      let sends = 0;
      const messaging = {
        async sendEachForMulticast() {
          sends += 1;
          return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
        },
      } as unknown as Messaging;
      let revoked = false;
      const revoke = async () => {
        if (revoked) return;
        revoked = true;
        await store.setLocationConsent("user-a", false, 2);
      };

      await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now, {
        ...(stage === "afterClaim" ? { afterClaim: revoke } : {}),
        ...(stage === "beforeSendBoundary" ? { beforeSendBoundary: revoke } : {}),
      });

      assert.equal(sends, 0);
      assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
    } finally {
      await clear(firestore);
      await deleteApp(app);
    }
  });
}

test("claim admission paused across revoke cannot send stale device data", async () => {
  const app = initializeApp({ projectId }, "weather-location-revoke-before-claim");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  let markEntered!: () => void;
  const entered = new Promise<void>((resolve) => { markEntered = resolve; });
  let releaseClaim!: () => void;
  const released = new Promise<void>((resolve) => { releaseClaim = resolve; });
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    let sends = 0;
    const messaging = {
      async sendEachForMulticast() {
        sends += 1;
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;
    const delivery = deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now, {
      beforeClaim: async () => {
        markEntered();
        await released;
      },
    });
    await entered;
    await store.setLocationConsent("user-a", false, 2);
    releaseClaim();
    await delivery;

    assert.equal(sends, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
  } finally {
    releaseClaim?.();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("bounded revoke reports incomplete and exact replay converges", async () => {
  const app = initializeApp({ projectId }, "weather-location-revoke-bounded-replay");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    for (let offset = 0; offset < 1601; offset += 400) {
      const batch = firestore.batch();
      for (let index = offset; index < Math.min(offset + 400, 1601); index += 1) {
        batch.set(firestore.doc(`users/user-a/weatherAlerts/device-${index}`), {
          ownerUid: "user-a",
          source: "DEVICE",
          locationConsentGeneration: 1,
          status: "PENDING",
          createdAt: now,
        });
      }
      await batch.commit();
    }

    await assert.rejects(
      store.setLocationConsent("user-a", false, 2),
      (error) =>
        error instanceof WeatherError &&
        error.code === "unavailable" &&
        error.message ===
          "Location consent cleanup is incomplete; retry the exact revoke command",
    );
    const consent = await firestore.doc("users/user-a/consents/location").get();
    assert.equal(consent.get("commandGeneration"), 2);
    assert.equal(consent.get("granted"), false);
    assert.equal((await firestore.collection("users/user-a/weatherAlerts").get()).size, 1);

    assert.deepEqual(await store.setLocationConsent("user-a", false, 2), {
      commandGeneration: 2,
      granted: false,
    });
    assert.equal((await firestore.collection("users/user-a/weatherAlerts").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("manual provenance survives location revoke and remains deliverable", async () => {
  const app = initializeApp({ projectId }, "weather-location-revoke-manual-survival");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedManualWeather(firestore);
    await executeRefreshWeather(
      { uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, singleRiskProvider, now,
    );
    await store.setLocationConsent("user-a", false, 2);

    const [snapshot, risks, alerts] = await Promise.all([
      firestore.doc("users/user-a/weatherSnapshots/current").get(),
      firestore.collection("users/user-a/weatherRisks").get(),
      firestore.collection("users/user-a/weatherAlerts").get(),
    ]);
    assert.equal(snapshot.get("source"), "MANUAL");
    assert.equal(snapshot.get("locationConsentGeneration"), null);
    assert.ok(risks.docs.every((risk) => risk.get("source") === "MANUAL"));
    assert.ok(alerts.docs.every((alert) => alert.get("source") === "MANUAL"));

    let sends = 0;
    const messaging = {
      async sendEachForMulticast() {
        sends += 1;
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a", 100, now);

    assert.equal(sends, 1);
    assert.equal((await alerts.docs[0]!.ref.get()).get("status"), "SENT");
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("weather consent revocation clears device region but preserves manual region", async () => {
  const app = initializeApp({ projectId }, "weather-consent-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);
    await firestore.doc("users/user-a/weatherSettings/current").update({
      globalAlertsEnabled: false,
      manualRegion: { regionCode: "kr-seoul", regionName: "서울", latitude: 37.55, longitude: 126.98, source: "MANUAL" },
    });
    await firestore.doc("users/user-a/weatherPlantSettings/plant-a").set({
      ownerUid: "user-a", plantId: "plant-a", enabled: false, revision: 1,
    });

    await store.setLocationConsent("user-a", false, 2);
    assert.deepEqual(await store.setLocationConsent("user-a", true, 3), {
      commandGeneration: 3,
      granted: true,
    });
    await assert.rejects(
      store.setLocationConsent("user-a", false, 2),
      (error) => error instanceof WeatherError && error.code === "aborted",
    );
    await store.setLocationConsent("user-a", false, 4);

    const settings = await firestore.doc("users/user-a/weatherSettings/current").get();
    assert.equal(settings.get("deviceRegion"), undefined);
    assert.equal(settings.get("manualRegion.regionName"), "서울");
    assert.equal(settings.get("globalAlertsEnabled"), false);
    assert.equal(
      (await firestore.doc("users/user-a/weatherPlantSettings/plant-a").get()).get("enabled"),
      false,
    );
    assert.equal((await store.loadContext("user-a")).locationConsent, false);
    assert.equal(
      (await firestore.doc("users/user-a/consents/location").get()).get("commandGeneration"),
      4,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("weather emulator keeps one alert per risk entry and cancels deleted targets before send", async () => {
  assert.equal(process.env.GCLOUD_PROJECT, projectId);
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const app = initializeApp({ projectId }, "weather-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  try {
    await clear(firestore);
    await seedWeather(firestore);

    await executeRefreshWeather({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, provider, now);
    await executeRefreshWeather({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store, provider, now);
    const alerts = await firestore.collection("users/user-a/weatherAlerts").get();
    assert.equal(alerts.size, 2);
    assert.deepEqual(alerts.docs.map((alert) => alert.get("transition")), [1, 1]);

    await firestore.doc("users/user-a/personalPlants/plant-a").delete();
    let sends = 0;
    const messaging = {
      async sendEachForMulticast() {
        sends += 1;
        return { successCount: 1, failureCount: 0, responses: [{ success: true }] };
      },
    } as unknown as Messaging;
    await deliverPendingWeatherAlerts(firestore, messaging, "user-a");
    assert.equal(sends, 0);
    const cancelled = await firestore.collection("users/user-a/weatherAlerts").where("status", "==", "CANCELLED").get();
    assert.equal(cancelled.size, 2);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

async function seedConfiguredUsers(
  firestore: FirebaseFirestore.Firestore,
  count: number,
): Promise<void> {
  for (let offset = 0; offset < count; offset += 400) {
    const batch = firestore.batch();
    for (let index = offset; index < Math.min(offset + 400, count); index += 1) {
      const ownerUid = `weather-user-${String(index).padStart(4, "0")}`;
      batch.set(firestore.doc(`users/${ownerUid}/weatherSettings/current`), {
        ownerUid,
        revision: 1,
      });
    }
    await batch.commit();
  }
}

async function clearRefreshScheduler(firestore: FirebaseFirestore.Firestore): Promise<void> {
  const settings = await firestore.collectionGroup("weatherSettings").get();
  for (let offset = 0; offset < settings.docs.length; offset += 400) {
    const batch = firestore.batch();
    settings.docs.slice(offset, offset + 400).forEach((document) => batch.delete(document.ref));
    await batch.commit();
  }
  await firestore.doc("notificationRuntime/weatherRefreshCursor").delete();
}

function pausedWeatherProvider(outcome: "success" | "failure"): Readonly<{
  provider: WeatherProvider;
  entered: Promise<void>;
  release: () => void;
}> {
  let markEntered!: () => void;
  const entered = new Promise<void>((resolve) => { markEntered = resolve; });
  let release!: () => void;
  const released = new Promise<void>((resolve) => { release = resolve; });
  return {
    entered,
    release,
    provider: {
      async current(region) {
        markEntered();
        await released;
        if (outcome === "failure") {
          throw new WeatherError("unavailable", "deterministic provider failure");
        }
        return provider.current(region);
      },
      async search() { return []; },
    },
  };
}

async function assertConsentChanged(pending: Promise<unknown>): Promise<void> {
  await assert.rejects(
    pending,
    (error) =>
      error instanceof WeatherError &&
      error.code === "aborted" &&
      error.message === "Weather location consent changed during refresh",
  );
}

async function assertManualWeatherUnchanged(
  firestore: FirebaseFirestore.Firestore,
  consentGeneration: number,
  consentGranted: boolean,
): Promise<void> {
  const [settings, consent, snapshot, risks, alerts] = await Promise.all([
    firestore.doc("users/user-a/weatherSettings/current").get(),
    firestore.doc("users/user-a/consents/location").get(),
    firestore.doc("users/user-a/weatherSnapshots/current").get(),
    firestore.collection("users/user-a/weatherRisks").get(),
    firestore.collection("users/user-a/weatherAlerts").get(),
  ]);
  assert.equal(settings.get("revision"), 1);
  assert.equal(settings.get("manualRegion.regionName"), "부산");
  assert.equal(settings.get("deviceRegion"), undefined);
  assert.equal(consent.get("commandGeneration"), consentGeneration);
  assert.equal(consent.get("granted"), consentGranted);
  assert.equal(snapshot.exists, false);
  assert.equal(risks.size, 0);
  assert.equal(alerts.size, 0);
}

async function seedManualWeather(firestore: FirebaseFirestore.Firestore): Promise<void> {
  await seedWeather(firestore);
  await firestore.doc("users/user-a/weatherSettings/current").set({
    ownerUid: "user-a",
    globalAlertsEnabled: true,
    manualRegion: {
      regionCode: "kr-busan",
      regionName: "부산",
      latitude: 35.18,
      longitude: 129.08,
      source: "MANUAL",
    },
    revision: 1,
  });
}

async function seedWeather(firestore: FirebaseFirestore.Firestore) {
  await firestore.doc("users/user-a").set({ ownerUid: "user-a", zoneId: "Asia/Seoul", revision: 1 });
  await firestore.doc("users/user-a/consents/location").set({
    ownerUid: "user-a",
    granted: true,
    commandGeneration: 1,
    revision: 1,
  });
  await firestore.doc("users/user-a/personalPlants/plant-a").set({ ownerUid: "user-a", displayName: "몬스테라", contentId: "species-a" });
  await firestore.doc("plantContents/species-a").set({ publicationState: "PUBLIC", revision: 1, minimumTemperatureCelsius: 18, maximumTemperatureCelsius: 30, minimumHumidityPercent: 40, maximumHumidityPercent: 70 });
  await firestore.doc("users/user-a/weatherSettings/current").set({ ownerUid: "user-a", globalAlertsEnabled: true, deviceRegion: { regionCode: "device-a", regionName: "현재 위치 주변", latitude: 37.55, longitude: 126.98, source: "DEVICE" }, revision: 1 });
  const endpoint = {
    ownerUid: "user-a",
    installationId: "endpoint-a",
    token: "token-a",
    notificationsEnabled: true,
    generation: 1,
  };
  await firestore.doc("users/user-a/notificationEndpoints/endpoint-a").set(endpoint);
  await firestore.doc("notificationEndpointOwners/endpoint-a").set({
    ...endpoint,
    state: "REGISTERED",
  });
}

async function clear(firestore: FirebaseFirestore.Firestore) {
  const paths = [
    "users/user-a/consents/location",
    "users/user-a/personalPlants/plant-a",
    "users/user-a/personalPlants/plant-b",
    "users/user-a/personalPlants/foreign-plant",
    "users/user-a/weatherSettings/current",
    "users/user-a/weatherPlantSettings/plant-a",
    "users/user-a/notificationEndpoints/endpoint-a",
    "notificationEndpointOwners/endpoint-a",
    "users/user-a/weatherSnapshots/current",
    "accountDeletionRequests/user-a",
    "users/user-a",
    "plantContents/species-a",
  ];
  const [risks, alerts, analyticsEvents, analyticsOperations] = await Promise.all([
    firestore.collection("users/user-a/weatherRisks").get(),
    firestore.collection("users/user-a/weatherAlerts").get(),
    firestore.collection("users/user-a/analyticsEvents").get(),
    firestore.collection("users/user-a/analyticsConsentOperations").get(),
  ]);
  await Promise.all(
    [...risks.docs, ...alerts.docs, ...analyticsEvents.docs, ...analyticsOperations.docs]
      .map((document) => document.ref.delete()),
  );
  await Promise.all([
    ...paths.map((path) => firestore.doc(path).delete()),
    firestore.doc("users/user-a/consents/analytics").delete(),
  ]);
}
