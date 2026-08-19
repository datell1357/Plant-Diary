import assert from "node:assert/strict";
import { EventEmitter, once } from "node:events";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import {
  FirestoreNotificationSettingsStore,
  executeEnsureWateringNotificationSettings,
  executeRegisterNotificationEndpoint,
  executeUnregisterNotificationEndpoint,
  executeUpdateAccountProfile,
  executeUpdateWateringNotificationSettings,
} from "./notification-settings.js";

const projectId = "demo-planterior";
const installationId = "install-12345678";

const registration = (
  uid: string,
  generation: number,
  token: string,
  notificationsEnabled = true,
  installationSecret = "secret-a-1234567890",
  nextInstallationSecret = installationSecret,
) => ({
  expectedOwnerUid: uid,
  installationId,
  installationSecret,
  nextInstallationSecret,
  generation,
  token,
  platform: "ANDROID",
  notificationsEnabled,
});

test("endpoint transfer requires the current secret exact increment and secret rotation", async () => {
  const app = initializeApp({ projectId }, "notification-endpoint-generation-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreNotificationSettingsStore(firestore);

  try {
    await clear(firestore);
    await executeRegisterNotificationEndpoint({ uid: "user-a" }, registration("user-a", 1, "token-a1"), store);
    await executeRegisterNotificationEndpoint({ uid: "user-a" }, registration("user-a", 2, "token-a2"), store);
    await assert.rejects(
      () => executeRegisterNotificationEndpoint(
        { uid: "user-b" },
        registration("user-b", 3, "attacker", true, "wrong-secret-123456", "secret-b-1234567890"),
        store,
      ),
    );
    await assert.rejects(
      () => executeRegisterNotificationEndpoint(
        { uid: "user-a" },
        registration("user-a", Number.MAX_SAFE_INTEGER, "lockout"),
        store,
      ),
    );
    const revocation = {
      expectedOwnerUid: "user-a",
      installationId,
      installationSecret: "secret-a-1234567890",
      generation: 3,
    };
    assert.deepEqual(
      await executeUnregisterNotificationEndpoint({ uid: "user-a" }, revocation, store),
      { unregistered: true, status: "REVOKED" },
    );
    assert.deepEqual(
      await executeUnregisterNotificationEndpoint({ uid: "user-a" }, revocation, store),
      { unregistered: true, status: "REVOKED" },
    );
    const transfer = registration(
      "user-b",
      4,
      "token-b",
      true,
      "secret-a-1234567890",
      "secret-b-1234567890",
    );
    await executeRegisterNotificationEndpoint({ uid: "user-b" }, transfer, store);

    // Simulate a committed transfer whose callable response was lost. Only the exact old-secret
    // request is replayable after the stored proof has rotated.
    await executeRegisterNotificationEndpoint({ uid: "user-b" }, transfer, store);
    await assert.rejects(
      () => executeRegisterNotificationEndpoint(
        { uid: "user-b" },
        { ...transfer, token: "altered-replay" },
        store,
      ),
      (error: unknown) => (error as { code?: string }).code === "permission-denied",
    );
    assert.deepEqual(
      await executeUnregisterNotificationEndpoint(
        { uid: "user-b" },
        {
          expectedOwnerUid: "user-b",
          installationId,
          installationSecret: "secret-b-1234567890",
          generation: 5,
        },
        store,
      ),
      { unregistered: true, status: "REVOKED" },
    );
    await assert.rejects(
      () => executeRegisterNotificationEndpoint({ uid: "user-b" }, transfer, store),
      (error: unknown) => (error as { code?: string }).code === "permission-denied",
    );
    await assert.rejects(
      () => executeRegisterNotificationEndpoint(
        { uid: "user-a" },
        registration("user-a", 6, "previous-owner-attack", true, "secret-a-1234567890"),
        store,
      ),
    );

    const owner = await firestore.doc(`notificationEndpointOwners/${installationId}`).get();
    const endpoint = await firestore.doc(`users/user-b/notificationEndpoints/${installationId}`).get();
    assert.equal(owner.get("ownerUid"), "user-b");
    assert.equal(owner.get("generation"), 5);
    assert.equal(owner.get("state"), "UNREGISTERED");
    assert.equal(endpoint.exists, false);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("missing endpoint revocation is an idempotent success", async () => {
  const app = initializeApp({ projectId }, "notification-endpoint-missing-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreNotificationSettingsStore(firestore);
  const command = {
    expectedOwnerUid: "user-a",
    installationId,
    installationSecret: "secret-a-1234567890",
    generation: 2,
  };

  try {
    await clear(firestore);
    await firestore.doc(`users/user-a/notificationEndpoints/${installationId}`).set({
      ownerUid: "user-a", installationId, token: "orphan", generation: 1, notificationsEnabled: true,
    });
    assert.deepEqual(
      await executeUnregisterNotificationEndpoint({ uid: "user-a" }, command, store),
      { unregistered: true, status: "ALREADY_ABSENT" },
    );
    assert.deepEqual(
      await executeUnregisterNotificationEndpoint({ uid: "user-a" }, command, store),
      { unregistered: true, status: "ALREADY_ABSENT" },
    );
    assert.equal(
      (await firestore.doc(`users/user-a/notificationEndpoints/${installationId}`).get()).exists,
      false,
    );
    const tombstone = await firestore.doc(`notificationEndpointOwners/${installationId}`).get();
    assert.equal(tombstone.get("state"), "UNREGISTERED");
    assert.equal(tombstone.get("generation"), 2);

    // A register callable that started before logout may arrive after the revocation response.
    // Its stale generation must not recreate the endpoint.
    await executeRegisterNotificationEndpoint(
      { uid: "user-a" },
      registration("user-a", 1, "late-token"),
      store,
    );
    assert.equal(
      (await firestore.doc(`users/user-a/notificationEndpoints/${installationId}`).get()).exists,
      false,
    );
    assert.equal(
      (await firestore.doc(`notificationEndpointOwners/${installationId}`).get()).get("state"),
      "UNREGISTERED",
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("canonical defaults persist and mixed scheduled unscheduled plant preferences save atomically", async () => {
  const app = initializeApp({ projectId }, "notification-settings-independent-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreNotificationSettingsStore(firestore);

  try {
    await clear(firestore);
    await firestore.doc("users/user-a").set({ ownerUid: "user-a", zoneId: "Asia/Seoul" });
    await firestore.doc("users/user-a/personalPlants/scheduled").set({ ownerUid: "user-a", displayName: "몬스테라" });
    await firestore.doc("users/user-a/personalPlants/unscheduled").set({ ownerUid: "user-a", displayName: "선인장" });
    await firestore.doc("users/user-a/wateringSchedules/scheduled").set({
      ownerUid: "user-a",
      plantId: "scheduled",
      dueDate: "2026-08-12",
      zoneId: "Asia/Seoul",
      revision: 2,
    });

    await executeEnsureWateringNotificationSettings(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a" },
      store,
    );
    const defaults = await firestore.doc("users/user-a/notificationSettings/watering").get();
    assert.equal(defaults.get("defaultTime"), "09:00");
    assert.equal(defaults.get("zoneId"), "Asia/Seoul");

    await executeUpdateWateringNotificationSettings(
      { uid: "user-a" },
      {
        expectedOwnerUid: "user-a",
        expectedRevision: 1,
        defaultTime: "09:30",
        zoneId: "Asia/Seoul",
        globalEnabled: true,
        plants: [
          { plantId: "scheduled", enabled: false, timeOverride: null },
          { plantId: "unscheduled", enabled: true, timeOverride: "08:15" },
        ],
      },
      store,
    );

    const scheduled = await firestore.doc("users/user-a/notificationPlantSettings/scheduled").get();
    const unscheduled = await firestore.doc("users/user-a/notificationPlantSettings/unscheduled").get();
    assert.equal(scheduled.get("enabled"), false);
    assert.equal(unscheduled.get("timeOverride"), "08:15");
    assert.equal((await firestore.doc("users/user-a/wateringSchedules/unscheduled").get()).exists, false);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("settings update rejects a stale client plant set without partially committing", async () => {
  const app = initializeApp({ projectId }, "notification-settings-plant-set-conflict-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreNotificationSettingsStore(firestore);

  try {
    await clear(firestore);
    await firestore.doc("users/user-a").set({ ownerUid: "user-a", zoneId: "Asia/Seoul" });
    await firestore.doc("users/user-a/personalPlants/plant-a").set({
      ownerUid: "user-a", displayName: "몬스테라",
    });
    await executeEnsureWateringNotificationSettings(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a" },
      store,
    );

    // This addition represents a plant transaction winning while the settings editor is open.
    await firestore.doc("users/user-a/personalPlants/plant-b").set({
      ownerUid: "user-a", displayName: "선인장",
    });
    await assert.rejects(
      () => executeUpdateWateringNotificationSettings(
        { uid: "user-a" },
        {
          expectedOwnerUid: "user-a",
          expectedRevision: 1,
          defaultTime: "08:00",
          zoneId: "Asia/Seoul",
          globalEnabled: false,
          plants: [{ plantId: "plant-a", enabled: false, timeOverride: null }],
        },
        store,
      ),
      (error: unknown) => (error as { code?: string }).code === "aborted",
    );

    const settings = await firestore.doc("users/user-a/notificationSettings/watering").get();
    assert.equal(settings.get("revision"), 1);
    assert.equal(settings.get("wateringEnabled"), true);
    assert.equal(
      (await firestore.doc("users/user-a/notificationPlantSettings/plant-a").get()).exists,
      false,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("concurrent settings edits from one revision produce one typed conflict", async () => {
  const app = initializeApp({ projectId }, "notification-settings-conflict-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreNotificationSettingsStore(firestore);
  let releaseBlockedTransaction = () => {};
  let releaseCompetingTransaction = () => {};

  try {
    await clear(firestore);
    await firestore.doc("users/user-a").set({ ownerUid: "user-a", zoneId: "Asia/Seoul" });
    await firestore.doc("users/user-a/personalPlants/plant-a").set({
      ownerUid: "user-a", displayName: "몬스테라",
    });
    await firestore.doc("users/user-a/wateringSchedules/plant-a").set({
      ownerUid: "user-a", plantId: "plant-a", dueDate: "2026-08-12", zoneId: "Asia/Seoul", revision: 1,
    });
    await executeEnsureWateringNotificationSettings(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a" },
      store,
    );
    const command = (
      targetStore: FirestoreNotificationSettingsStore,
      defaultTime: string,
      enabled: boolean,
    ) =>
      executeUpdateWateringNotificationSettings(
        { uid: "user-a" },
        {
          expectedOwnerUid: "user-a",
          expectedRevision: 1,
          defaultTime,
          zoneId: "Asia/Seoul",
          globalEnabled: true,
          plants: [{ plantId: "plant-a", enabled, timeOverride: null }],
        },
        targetStore,
      );
    const transactionEvents = new EventEmitter();
    const blockedTransactionRelease = new Promise<void>((resolve) => {
      releaseBlockedTransaction = resolve;
    });
    const competingTransactionRelease = new Promise<void>((resolve) => {
      releaseCompetingTransaction = resolve;
    });
    const blockedStore = new FirestoreNotificationSettingsStore(firestore, {
      beforeWateringSettingsCommit: async () => {
        transactionEvents.emit("first-read-complete");
        await blockedTransactionRelease;
      },
    });
    const competingStore = new FirestoreNotificationSettingsStore(firestore, {
      beforeWateringSettingsTransaction: async () => {
        transactionEvents.emit("competing-call-started");
        await competingTransactionRelease;
      },
    });
    const firstRead = once(transactionEvents, "first-read-complete", {
      signal: AbortSignal.timeout(30_000),
    });
    const blockedResult = command(blockedStore, "08:00", false).then(
      (value) => ({ status: "fulfilled" as const, value }),
      (reason: unknown) => ({ status: "rejected" as const, reason }),
    );
    await firstRead;
    const competingCallStarted = once(transactionEvents, "competing-call-started", {
      signal: AbortSignal.timeout(30_000),
    });
    const competingResult = command(competingStore, "10:00", true).then(
      (value) => ({ status: "fulfilled" as const, value }),
      (reason: unknown) => ({ status: "rejected" as const, reason }),
    );
    await competingCallStarted;

    releaseBlockedTransaction();
    assert.equal((await blockedResult).status, "fulfilled");
    releaseCompetingTransaction();
    const rejected = await competingResult;

    assert.equal(rejected.status, "rejected");
    assert.equal(
      rejected.status === "rejected" && (rejected.reason as { code?: string }).code,
      "aborted",
    );
    const settings = await firestore.doc("users/user-a/notificationSettings/watering").get();
    const preference = await firestore.doc("users/user-a/notificationPlantSettings/plant-a").get();
    assert.equal(settings.get("revision"), 2);
    assert.equal(settings.get("defaultTime"), "08:00");
    assert.equal(preference.get("enabled"), false);
  } finally {
    releaseBlockedTransaction();
    releaseCompetingTransaction();
    await clear(firestore);
    await deleteApp(app);
  }
});

test("account timezone and all notification candidates commit atomically and retry after failure", async () => {
  const app = initializeApp({ projectId }, "account-timezone-atomic-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreNotificationSettingsStore(firestore);
  const command = {
    expectedOwnerUid: "user-a",
    displayName: "A",
    providers: ["GOOGLE"],
    zoneId: "America/Los_Angeles",
  };

  try {
    await clear(firestore);
    await firestore.doc("users/user-a").set({
      ownerUid: "user-a", displayName: "A", providers: ["GOOGLE"], zoneId: "Asia/Seoul", revision: 1,
    });
    await firestore.doc("users/user-a/notificationSettings/watering").set({
      ownerUid: "user-a", wateringEnabled: true, weatherEnabled: false, defaultTime: "09:00", zoneId: "Asia/Seoul", revision: 1,
    });
    await firestore.doc("users/user-a/notificationPlantSettings/plant-a").set({
      ownerUid: "user-a", plantId: "plant-a", enabled: true, timeOverride: null, revision: 1,
    });
    await firestore.doc("users/user-a/wateringSchedules/plant-a").set({
      ownerUid: "user-a", plantId: "plant-a", dueDate: "2026-08-12", zoneId: "Asia/Seoul", revision: 0,
    });

    await assert.rejects(
      () => executeUpdateAccountProfile({ uid: "user-a" }, command, store),
    );
    assert.equal((await firestore.doc("users/user-a").get()).get("zoneId"), "Asia/Seoul");
    assert.equal(
      (await firestore.doc("users/user-a/notificationSettings/watering").get()).get("zoneId"),
      "Asia/Seoul",
    );

    await firestore.doc("users/user-a/wateringSchedules/plant-a").update({ revision: 1 });
    await executeUpdateAccountProfile({ uid: "user-a" }, command, store);

    const account = await firestore.doc("users/user-a").get();
    const settings = await firestore.doc("users/user-a/notificationSettings/watering").get();
    const schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(account.get("zoneId"), "America/Los_Angeles");
    assert.equal(settings.get("zoneId"), "America/Los_Angeles");
    assert.equal(schedule.get("zoneId"), "America/Los_Angeles");
    assert.equal(
      schedule.get("nextNotificationAt").toDate().toISOString(),
      "2026-08-12T16:00:00.000Z",
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("authoritative account timezone change recomputes due candidate and schedule timezone is ignored", async () => {
  const app = initializeApp({ projectId }, "notification-settings-timezone-change-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreNotificationSettingsStore(firestore);

  try {
    await clear(firestore);
    await firestore.doc("users/user-a").set({ ownerUid: "user-a", zoneId: "Asia/Seoul" });
    await firestore.doc("users/user-a/personalPlants/scheduled").set({ ownerUid: "user-a", displayName: "몬스테라" });
    await firestore.doc("users/user-a/wateringSchedules/scheduled").set({
      ownerUid: "user-a", plantId: "scheduled", dueDate: "2026-08-12", zoneId: "stale/ignored", revision: 1,
    });
    await executeEnsureWateringNotificationSettings({ uid: "user-a" }, { expectedOwnerUid: "user-a" }, store);
    await executeUpdateWateringNotificationSettings(
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", expectedRevision: 1, defaultTime: "09:00", zoneId: "Asia/Seoul", globalEnabled: true, plants: [{ plantId: "scheduled", enabled: true, timeOverride: null }] },
      store,
    );
    const seoul = (await firestore.doc("users/user-a/wateringSchedules/scheduled").get()).get("nextNotificationAt").toDate().toISOString();

    await firestore.doc("users/user-a").update({ zoneId: "America/Los_Angeles" });
    await store.reconcileWateringTimezone("user-a");
    const migratedSchedule = await firestore.doc("users/user-a/wateringSchedules/scheduled").get();
    const losAngeles = migratedSchedule.get("nextNotificationAt").toDate().toISOString();

    assert.notEqual(seoul, losAngeles);
    assert.equal(migratedSchedule.get("zoneId"), "America/Los_Angeles");
    assert.equal((await firestore.doc("users/user-a/notificationSettings/watering").get()).get("zoneId"), "America/Los_Angeles");
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

async function clear(firestore: ReturnType<typeof getFirestore>): Promise<void> {
  for (const uid of ["user-a", "user-b"]) {
    for (const collection of [
      "personalPlants", "wateringSchedules", "notificationSettings", "notificationPlantSettings", "notificationEndpoints",
    ]) {
      const snapshot = await firestore.collection(`users/${uid}/${collection}`).get();
      await Promise.all(snapshot.docs.map((document) => document.ref.delete()));
    }
    await firestore.doc(`users/${uid}`).delete();
  }
  await firestore.doc(`notificationEndpointOwners/${installationId}`).delete();
}
