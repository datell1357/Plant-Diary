import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { FirestoreWeatherStore } from "./weather-runtime.js";
import {
  WEATHER_RETENTION_MILLIS,
  cleanupExpiredWeatherData,
} from "./weather-retention.js";

const projectId = "demo-planterior";
const HOUR_MILLIS = 60 * 60 * 1_000;
const observedAt = new Date("2026-08-12T00:00:00.000Z");

const plant = {
  plantId: "plant-a",
  plantName: "몬스테라",
  minimumTemperatureCelsius: 18,
  maximumTemperatureCelsius: 30,
  minimumHumidityPercent: 40,
  maximumHumidityPercent: 70,
} as const;

const manualRegion = {
  regionCode: "kr-seoul",
  regionName: "서울",
  latitude: 37.55,
  longitude: 126.98,
  source: "MANUAL" as const,
};

test("weather persistence stamps retention while preserving the three-hour freshness boundary", async () => {
  const app = initializeApp({ projectId }, "weather-retention-persistence");
  const firestore = getFirestore(app);
  const store = new FirestoreWeatherStore(firestore);
  const ownerUid = "weather-retention-persistence";
  try {
    await seedEvaluationOwner(firestore, ownerUid);
    const hotSnapshot = {
      ...manualRegion,
      temperatureCelsius: 35,
      humidityPercent: 55,
      precipitationMillimeters: 0,
      observedAt,
      zoneId: "Asia/Seoul",
    };
    await store.commitEvaluation({
      ownerUid,
      expectedRevision: 1,
      region: manualRegion,
      snapshot: hotSnapshot,
      risks: [{
        plantId: plant.plantId,
        plantName: plant.plantName,
        type: "HIGH_TEMPERATURE",
        reason: "더워요",
        action: "서늘한 곳으로 옮겨 주세요.",
      }],
      unavailablePlantIds: [],
      plants: [plant],
      stale: false,
      evaluatedAt: observedAt,
      globalAlertsEnabled: true,
      plantAlerts: new Map([[plant.plantId, true]]),
      switchToDeviceRegion: false,
      expectedLocationConsentGeneration: null,
    });

    const [snapshot, activeRisk, alert] = await Promise.all([
      firestore.doc(`users/${ownerUid}/weatherSnapshots/current`).get(),
      firestore.doc(`users/${ownerUid}/weatherRisks/plant-a_HIGH_TEMPERATURE`).get(),
      firestore.collection(`users/${ownerUid}/weatherAlerts`).limit(1).get(),
    ]);
    assert.equal(snapshot.get("freshUntil").toMillis(), observedAt.valueOf() + 3 * HOUR_MILLIS);
    assert.equal(snapshot.get("expiresAt").toMillis(), observedAt.valueOf() + WEATHER_RETENTION_MILLIS);
    assert.equal(activeRisk.get("expiresAt").toMillis(), observedAt.valueOf() + WEATHER_RETENTION_MILLIS);
    assert.equal(alert.docs[0]!.get("freshUntil").toMillis(), observedAt.valueOf() + 3 * HOUR_MILLIS);
    assert.equal(alert.docs[0]!.get("expiresAt").toMillis(), observedAt.valueOf() + 3 * HOUR_MILLIS);

    const resolvedObservedAt = new Date(observedAt.valueOf() + HOUR_MILLIS);
    await store.commitEvaluation({
      ownerUid,
      expectedRevision: 2,
      region: manualRegion,
      snapshot: {
        ...hotSnapshot,
        temperatureCelsius: 25,
        observedAt: resolvedObservedAt,
      },
      risks: [],
      unavailablePlantIds: [],
      plants: [plant],
      stale: false,
      evaluatedAt: resolvedObservedAt,
      globalAlertsEnabled: true,
      plantAlerts: new Map([[plant.plantId, true]]),
      switchToDeviceRegion: false,
      expectedLocationConsentGeneration: null,
    });
    const resolvedRisk = await activeRisk.ref.get();
    assert.equal(resolvedRisk.get("active"), false);
    assert.equal(
      resolvedRisk.get("expiresAt").toMillis(),
      resolvedObservedAt.valueOf() + WEATHER_RETENTION_MILLIS,
    );
  } finally {
    await clearOwner(firestore, ownerUid);
    await firestore.doc("plantContents/weather-retention-species").delete();
    await deleteApp(app);
  }
});

test("cleanup deletes active stale snapshots, active and resolved risks, and terminal alerts at the exact boundary for manual and device sources", async () => {
  const app = initializeApp({ projectId }, "weather-retention-boundary");
  const firestore = getFirestore(app);
  const owners = ["weather-retention-manual", "weather-retention-device"] as const;
  const boundary = new Date(observedAt.valueOf() + WEATHER_RETENTION_MILLIS);
  try {
    await Promise.all(owners.map((ownerUid, index) => seedExpiredState(
      firestore,
      ownerUid,
      index === 0 ? "MANUAL" : "DEVICE",
      index === 0,
    )));

    assert.deepEqual(
      await cleanupExpiredWeatherData(
        firestore,
        Timestamp.fromMillis(boundary.valueOf() - 1),
        100,
      ),
      { scanned: 0, deleted: 0, terminalized: 0, preserved: 0, hasMore: false, failures: [] },
    );

    const atBoundary = await cleanupExpiredWeatherData(
      firestore,
      Timestamp.fromDate(boundary),
      100,
    );
    assert.deepEqual(atBoundary, {
      scanned: 6,
      deleted: 6,
      terminalized: 0,
      preserved: 0,
      hasMore: false,
      failures: [],
    });
    for (const ownerUid of owners) {
      const [snapshot, risks, alerts] = await Promise.all([
        firestore.doc(`users/${ownerUid}/weatherSnapshots/current`).get(),
        firestore.collection(`users/${ownerUid}/weatherRisks`).get(),
        firestore.collection(`users/${ownerUid}/weatherAlerts`).get(),
      ]);
      assert.equal(snapshot.exists, false);
      assert.equal(risks.empty, true);
      assert.equal(alerts.empty, true);
    }
  } finally {
    await Promise.all(owners.map((ownerUid) => clearOwner(firestore, ownerUid)));
    await deleteApp(app);
  }
});

test("cleanup preserves pending and claimed alerts during live leases then terminalizes safely without deleting", async () => {
  const app = initializeApp({ projectId }, "weather-retention-leases");
  const firestore = getFirestore(app);
  const ownerUid = "weather-retention-leases";
  const cleanupAt = new Date("2026-08-12T04:00:00.000Z");
  const leaseExpiresAt = new Date(cleanupAt.valueOf() + 60_000);
  try {
    await Promise.all([
      seedAlert(firestore, ownerUid, "pending", "PENDING", cleanupAt, leaseExpiresAt),
      seedAlert(firestore, ownerUid, "claimed", "CLAIMED", cleanupAt, leaseExpiresAt),
      seedAlert(firestore, ownerUid, "send-boundary", "SEND_MAY_HAVE_OCCURRED", cleanupAt, leaseExpiresAt),
    ]);

    const liveLease = await cleanupExpiredWeatherData(
      firestore,
      Timestamp.fromDate(cleanupAt),
      100,
    );
    assert.deepEqual(liveLease, {
      scanned: 3,
      deleted: 0,
      terminalized: 0,
      preserved: 3,
      hasMore: false,
      failures: [],
    });

    const afterLease = new Date(leaseExpiresAt.valueOf() + 1);
    const terminalized = await cleanupExpiredWeatherData(
      firestore,
      Timestamp.fromDate(afterLease),
      100,
    );
    assert.deepEqual(terminalized, {
      scanned: 3,
      deleted: 0,
      terminalized: 3,
      preserved: 0,
      hasMore: false,
      failures: [],
    });
    const alerts = await firestore.collection(`users/${ownerUid}/weatherAlerts`).get();
    const statuses = new Map(alerts.docs.map((document) => [document.id, document.get("status")]));
    assert.equal(statuses.get("pending"), "CANCELLED");
    assert.equal(statuses.get("claimed"), "CANCELLED");
    assert.equal(statuses.get("send-boundary"), "SENT_AMBIGUOUS");
    for (const alert of alerts.docs) {
      assert.equal(alert.get("terminalAt").toMillis(), afterLease.valueOf());
      assert.equal(
        alert.get("expiresAt").toMillis(),
        afterLease.valueOf() + WEATHER_RETENTION_MILLIS,
      );
    }

    const terminalBoundary = new Date(afterLease.valueOf() + WEATHER_RETENTION_MILLIS);
    const beforeBoundary = await cleanupExpiredWeatherData(
      firestore,
      Timestamp.fromMillis(terminalBoundary.valueOf() - 1),
      100,
    );
    assert.equal(beforeBoundary.deleted, 0);
    const atBoundary = await cleanupExpiredWeatherData(
      firestore,
      Timestamp.fromDate(terminalBoundary),
      100,
    );
    assert.equal(atBoundary.deleted, 3);
  } finally {
    await clearOwner(firestore, ownerUid);
    await deleteApp(app);
  }
});

test("cleanup is globally bounded, paginated, and idempotent", async () => {
  const app = initializeApp({ projectId }, "weather-retention-pagination");
  const firestore = getFirestore(app);
  const ownerUid = "weather-retention-pagination";
  const boundary = new Date(observedAt.valueOf() + WEATHER_RETENTION_MILLIS);
  try {
    const writes = Array.from({ length: 5 }, (_, index) =>
      firestore.doc(`users/${ownerUid}/weatherRisks/risk-${index}`).set({
        ownerUid,
        active: index % 2 === 0,
        source: index % 2 === 0 ? "MANUAL" : "DEVICE",
        observedAt: Timestamp.fromDate(observedAt),
        expiresAt: Timestamp.fromDate(boundary),
      })
    );
    await Promise.all(writes);

    const results = [];
    for (let page = 0; page < 3; page += 1) {
      results.push(await cleanupExpiredWeatherData(
        firestore,
        Timestamp.fromDate(boundary),
        2,
      ));
    }
    assert.deepEqual(results.map((result) => result.deleted), [2, 2, 1]);
    assert.deepEqual(results.map((result) => result.scanned), [2, 2, 1]);
    assert.deepEqual(results.map((result) => result.hasMore), [true, true, false]);
    assert.ok(results.every((result) => result.failures.length === 0));
    assert.equal((await firestore.collection(`users/${ownerUid}/weatherRisks`).get()).size, 0);
    assert.deepEqual(
      await cleanupExpiredWeatherData(firestore, Timestamp.fromDate(boundary), 2),
      { scanned: 0, deleted: 0, terminalized: 0, preserved: 0, hasMore: false, failures: [] },
    );
  } finally {
    await clearOwner(firestore, ownerUid);
    await deleteApp(app);
  }
});

async function seedEvaluationOwner(
  firestore: FirebaseFirestore.Firestore,
  ownerUid: string,
): Promise<void> {
  await Promise.all([
    firestore.doc(`users/${ownerUid}`).set({ ownerUid, zoneId: "Asia/Seoul", revision: 1 }),
    firestore.doc(`users/${ownerUid}/personalPlants/plant-a`).set({
      ownerUid,
      displayName: plant.plantName,
      contentId: "weather-retention-species",
    }),
    firestore.doc("plantContents/weather-retention-species").set({
      publicationState: "PUBLIC",
      revision: 1,
      minimumTemperatureCelsius: plant.minimumTemperatureCelsius,
      maximumTemperatureCelsius: plant.maximumTemperatureCelsius,
      minimumHumidityPercent: plant.minimumHumidityPercent,
      maximumHumidityPercent: plant.maximumHumidityPercent,
    }),
    firestore.doc(`users/${ownerUid}/weatherSettings/current`).set({
      ownerUid,
      globalAlertsEnabled: true,
      manualRegion,
      revision: 1,
    }),
  ]);
}

async function seedExpiredState(
  firestore: FirebaseFirestore.Firestore,
  ownerUid: string,
  source: "MANUAL" | "DEVICE",
  active: boolean,
): Promise<void> {
  const expiresAt = Timestamp.fromMillis(observedAt.valueOf() + WEATHER_RETENTION_MILLIS);
  const provenance = {
    source,
    locationConsentGeneration: source === "DEVICE" ? 3 : null,
  };
  await Promise.all([
    firestore.doc(`users/${ownerUid}/weatherSnapshots/current`).set({
      ownerUid,
      observedAt: Timestamp.fromDate(observedAt),
      expiresAt,
      stale: true,
      ...provenance,
    }),
    firestore.doc(`users/${ownerUid}/weatherRisks/risk`).set({
      ownerUid,
      observedAt: Timestamp.fromDate(observedAt),
      expiresAt,
      active,
      ...provenance,
    }),
    firestore.doc(`users/${ownerUid}/weatherAlerts/terminal`).set({
      ownerUid,
      status: active ? "SENT" : "CANCELLED",
      terminalAt: Timestamp.fromDate(observedAt),
      expiresAt,
      ...provenance,
    }),
  ]);
}

async function seedAlert(
  firestore: FirebaseFirestore.Firestore,
  ownerUid: string,
  alertId: string,
  status: string,
  expiresAt: Date,
  leaseExpiresAt: Date,
): Promise<void> {
  await firestore.doc(`users/${ownerUid}/weatherAlerts/${alertId}`).set({
    ownerUid,
    status,
    expiresAt: Timestamp.fromMillis(expiresAt.valueOf() - 1),
    leaseExpiresAt: Timestamp.fromDate(leaseExpiresAt),
    createdAt: Timestamp.fromDate(observedAt),
    source: "MANUAL",
    locationConsentGeneration: null,
  });
}

async function clearOwner(
  firestore: FirebaseFirestore.Firestore,
  ownerUid: string,
): Promise<void> {
  const collections = [
    "personalPlants",
    "weatherSettings",
    "weatherSnapshots",
    "weatherRisks",
    "weatherAlerts",
  ];
  const snapshots = await Promise.all(collections.map((collection) =>
    firestore.collection(`users/${ownerUid}/${collection}`).get()
  ));
  await Promise.all(snapshots.flatMap((snapshot) =>
    snapshot.docs.map((document) => document.ref.delete())
  ));
  await firestore.doc(`users/${ownerUid}`).delete();
}
