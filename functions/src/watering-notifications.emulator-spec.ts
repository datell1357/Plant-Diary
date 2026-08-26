import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { FirestoreNotificationSettingsStore } from "./notification-settings.js";
import {
  FirestoreWateringDeliveryStore,
  NOTIFICATION_RETENTION_MILLIS,
  cleanupExpiredNotificationRecords,
  executeConfirmNotificationOpened,
  runWateringDeliveryScan,
  selectWateringAttempt,
  type DueWateringAttempt,
  type EndpointDeliveryResult,
  type WateringDeliveryStore,
  type WateringEndpointTarget,
  type WateringPushSender,
} from "./watering-notifications.js";
import type { ServerAnalyticsOperation } from "./server-analytics.js";

const projectId = "demo-planterior";

class RecordingSender implements WateringPushSender {
  readonly attempts: DueWateringAttempt[] = [];

  async send(attempt: DueWateringAttempt, endpoints: readonly WateringEndpointTarget[]): Promise<readonly EndpointDeliveryResult[]> {
    this.attempts.push(attempt);
    return endpoints.map((endpoint) => ({ ...endpoint, success: true, permanent: false }));
  }
}

test("emulator expiring claim retries and immutable SENT receipt finalizes dedupe", async () => {
  const app = initializeApp({ projectId }, "watering-claim-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();
  const analyticsEvents: ServerAnalyticsOperation[] = [];
  const analytics = async (event: ServerAnalyticsOperation) => {
    if (event.eventName === "WATERING_NOTIFICATION_SENT") {
      const history = await firestore
        .collection(`users/${event.ownerUid}/notificationHistory`)
        .get();
      assert.equal(history.docs[0]?.get("status"), "SENT");
    } else {
      const history = await firestore
        .doc(`users/${event.ownerUid}/notificationHistory/${event.operationIdentifier}`)
        .get();
      assert.equal(history.get("destinationOpened"), true);
    }
    analyticsEvents.push(event);
    return { kind: "recorded" as const, eventId: "event", replayed: false };
  };

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await firestore.doc("notificationDeliveryClaims/stale-claim").set({
      deduplicationKey: "unrelated",
      state: "CLAIMED",
      claimId: "old",
      expiresAt: Timestamp.fromDate(new Date("2026-08-11T00:00:00Z")),
    });

    const now = new Date("2026-08-12T00:00:00Z");
    await runWateringDeliveryScan(store, sender, now, 100, analytics);
    await runWateringDeliveryScan(
      store,
      sender,
      new Date("2026-08-12T00:01:00Z"),
      100,
      analytics,
    );

    assert.equal(sender.attempts.length, 1);
    const receipts = await firestore.collection("users/user-a/notificationDeliveries").get();
    assert.equal(receipts.size, 1);
    assert.equal(receipts.docs[0]?.get("status"), "SENT");
    assert.equal(receipts.docs[0]?.get("endpointResults"), undefined);
    const diagnostics = await firestore.collection("notificationDeliveryDiagnostics").get();
    assert.deepEqual(diagnostics.docs[0]?.get("endpointResults")[0].endpointIds, ["install-a"]);
    const history = await firestore.collection("users/user-a/notificationHistory").get();
    assert.equal(history.size, 1);
    assert.equal(history.docs[0]?.get("status"), "SENT");
    assert.equal(history.docs[0]?.get("destinationOpened"), false);
    assert.equal(history.docs[0]?.get("endpointResults"), undefined);
    for (const document of [receipts.docs[0]!, diagnostics.docs[0]!, history.docs[0]!]) {
      assert.equal(
        document.get("expiresAt").toMillis() - document.get("terminalAt").toMillis(),
        NOTIFICATION_RETENTION_MILLIS,
      );
    }
    await executeConfirmNotificationOpened(
      firestore,
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", deliveryId: history.docs[0]!.id },
      new Date("2026-08-12T00:02:00Z"),
      analytics,
    );
    await executeConfirmNotificationOpened(
      firestore,
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", deliveryId: history.docs[0]!.id },
      new Date("2026-08-12T00:03:00Z"),
      analytics,
    );
    const opened = await history.docs[0]!.ref.get();
    assert.equal(opened.get("destinationOpened"), true);
    assert.ok(opened.get("openedAt") instanceof Timestamp);
    assert.deepEqual(
      analyticsEvents.map((event) => event.eventName),
      [
        "WATERING_NOTIFICATION_SENT",
        "WATERING_NOTIFICATION_OPENED",
        "WATERING_NOTIFICATION_OPENED",
      ],
    );
    assert.equal(analyticsEvents[1]?.operationIdentifier, history.docs[0]!.id);
    assert.equal(analyticsEvents[2]?.operationIdentifier, history.docs[0]!.id);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("global disable at send boundary retires claim without FCM or history", async () => {
  const app = initializeApp({ projectId }, "watering-send-boundary-global-disable-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeOnly(firestore, store, "user-a");
    await settingsStore.updateWateringSettings({
      ownerUid: "user-a",
      expectedRevision: 1,
      defaultTime: "09:00",
      zoneId: "Asia/Seoul",
      globalEnabled: false,
      plants: [{ plantId: "plant-a", enabled: true, timeOverride: null }],
    });

    const allowed = await store.markSendMayHaveOccurred(
      delivery.attempt,
      delivery.claimId,
      delivery.endpoints,
    );
    if (allowed) await sender.send(delivery.attempt, delivery.endpoints);

    assert.equal(allowed, false);
    assert.equal(sender.attempts.length, 0);
    assert.equal((await firestore.collection("notificationDeliveryClaims").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
    assert.deepEqual(
      (await firestore.doc("notificationEndpointOwners/install-a").get()).get(
        "activeSendLeases",
      ),
      {},
    );
    const schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(schedule.get("notificationCandidateActive"), false);
    assert.equal(schedule.get("nextNotificationAt"), undefined);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("per-plant disable at send boundary retires claim without FCM or history", async () => {
  const app = initializeApp({ projectId }, "watering-send-boundary-plant-disable-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeOnly(firestore, store, "user-a");
    await settingsStore.updateWateringSettings({
      ownerUid: "user-a",
      expectedRevision: 1,
      defaultTime: "09:00",
      zoneId: "Asia/Seoul",
      globalEnabled: true,
      plants: [{ plantId: "plant-a", enabled: false, timeOverride: null }],
    });

    const allowed = await store.markSendMayHaveOccurred(
      delivery.attempt,
      delivery.claimId,
      delivery.endpoints,
    );
    if (allowed) await sender.send(delivery.attempt, delivery.endpoints);

    assert.equal(allowed, false);
    assert.equal(sender.attempts.length, 0);
    assert.equal((await firestore.collection("notificationDeliveryClaims").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("schedule deletion or deactivation at send boundary never calls FCM", async () => {
  const app = initializeApp({ projectId }, "watering-send-boundary-schedule-state-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await seed(firestore, "user-b", true, true);
    const deleted = await authorizeOnly(firestore, store, "user-a");
    const deactivated = await authorizeOnly(firestore, store, "user-b");
    await firestore.doc("users/user-a/wateringSchedules/plant-a").delete();
    await firestore.doc("users/user-b/wateringSchedules/plant-a").update({
      notificationCandidateActive: false,
      nextNotificationAt: Timestamp.fromDate(new Date("2026-08-20T00:00:00Z")),
      revision: 3,
    });

    for (const delivery of [deleted, deactivated]) {
      const allowed = await store.markSendMayHaveOccurred(
        delivery.attempt,
        delivery.claimId,
        delivery.endpoints,
      );
      if (allowed) await sender.send(delivery.attempt, delivery.endpoints);
      assert.equal(allowed, false);
    }

    assert.equal(sender.attempts.length, 0);
    assert.equal((await firestore.collection("notificationDeliveryClaims").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
    assert.equal((await firestore.collection("users/user-b/notificationHistory").get()).size, 0);
    assert.equal((await firestore.doc("users/user-a/wateringSchedules/plant-a").get()).exists, false);
    assert.equal(
      (await firestore.doc("users/user-b/wateringSchedules/plant-a").get()).get(
        "notificationCandidateActive",
      ),
      false,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("settings revision time or timezone change at send boundary invalidates authorization", async () => {
  const app = initializeApp({ projectId }, "watering-send-boundary-settings-version-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    for (const uid of ["user-a", "user-b", "user-c"]) {
      await seed(firestore, uid, true, true);
    }
    const revisionChanged = await authorizeOnly(firestore, store, "user-a");
    const timeChanged = await authorizeOnly(firestore, store, "user-b");
    const timezoneChanged = await authorizeOnly(firestore, store, "user-c");
    await firestore.doc("users/user-a/notificationSettings/watering").update({ revision: 2 });
    await firestore.doc("users/user-b/notificationSettings/watering").update({
      defaultTime: "08:00",
    });
    await firestore.doc("users/user-c/notificationSettings/watering").update({
      zoneId: "America/Los_Angeles",
    });

    for (const delivery of [revisionChanged, timeChanged, timezoneChanged]) {
      const allowed = await store.markSendMayHaveOccurred(
        delivery.attempt,
        delivery.claimId,
        delivery.endpoints,
      );
      if (allowed) await sender.send(delivery.attempt, delivery.endpoints);
      assert.equal(allowed, false);
    }

    assert.equal(sender.attempts.length, 0);
    assert.equal((await firestore.collection("notificationDeliveryClaims").get()).size, 0);
    for (const uid of ["user-a", "user-b", "user-c"]) {
      assert.equal((await firestore.collection(`users/${uid}/notificationHistory`).get()).size, 0);
    }
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("endpoint revocation at send boundary invalidates lease and prevents FCM", async () => {
  const app = initializeApp({ projectId }, "watering-send-boundary-endpoint-revocation-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeOnly(firestore, store, "user-a");
    await firestore.doc("users/user-a/notificationEndpoints/install-a").delete();
    await firestore.doc("notificationEndpointOwners/install-a").update({
      state: "UNREGISTERED",
      generation: 2,
      activeSendLeases: {},
    });

    const allowed = await store.markSendMayHaveOccurred(
      delivery.attempt,
      delivery.claimId,
      delivery.endpoints,
    );
    if (allowed) await sender.send(delivery.attempt, delivery.endpoints);

    assert.equal(allowed, false);
    assert.equal(sender.attempts.length, 0);
    assert.equal((await firestore.collection("notificationDeliveryClaims").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("unchanged send-boundary authorization calls FCM once", async () => {
  const app = initializeApp({ projectId }, "watering-send-boundary-happy-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeOnly(firestore, store, "user-a");

    const allowed = await store.markSendMayHaveOccurred(
      delivery.attempt,
      delivery.claimId,
      delivery.endpoints,
    );
    assert.equal(allowed, true);
    const results = await sender.send(delivery.attempt, delivery.endpoints);
    await store.finalizeSent(delivery.attempt, delivery.claimId, results);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"));

    assert.equal(sender.attempts.length, 1);
    assert.equal((await firestore.collection("notificationDeliveryClaims").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("default time change after authorization advances with authoritative next-day time", async () => {
  const app = initializeApp({ projectId }, "watering-finalize-default-time-race-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeAndSend(firestore, store, sender, "user-a");
    await settingsStore.updateWateringSettings({
      ownerUid: "user-a",
      expectedRevision: 1,
      defaultTime: "08:00",
      zoneId: "Asia/Seoul",
      globalEnabled: true,
      plants: [{ plantId: "plant-a", enabled: true, timeOverride: null }],
    });

    await store.finalizeSent(delivery.attempt, delivery.claimId, delivery.results);
    const schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(
      schedule.get("nextNotificationAt").toDate().toISOString(),
      "2026-08-12T23:00:00.000Z",
    );
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 1);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"));
    assert.equal(sender.attempts.length, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("per-plant override change after authorization advances with authoritative override", async () => {
  const app = initializeApp({ projectId }, "watering-finalize-override-race-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeAndSend(firestore, store, sender, "user-a");
    await settingsStore.updateWateringSettings({
      ownerUid: "user-a",
      expectedRevision: 1,
      defaultTime: "09:00",
      zoneId: "Asia/Seoul",
      globalEnabled: true,
      plants: [{ plantId: "plant-a", enabled: true, timeOverride: "08:30" }],
    });

    await store.finalizeSent(delivery.attempt, delivery.claimId, delivery.results);
    const schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(
      schedule.get("nextNotificationAt").toDate().toISOString(),
      "2026-08-12T23:30:00.000Z",
    );
    assert.equal(sender.attempts.length, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("timezone change after authorization advances using authoritative zone", async () => {
  const app = initializeApp({ projectId }, "watering-finalize-timezone-race-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeAndSend(firestore, store, sender, "user-a");
    await settingsStore.updateAccountProfile({
      ownerUid: "user-a",
      displayName: "A",
      providers: ["GOOGLE"],
      zoneId: "America/Los_Angeles",
    });

    await store.finalizeSent(delivery.attempt, delivery.claimId, delivery.results);
    const schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(schedule.get("zoneId"), "America/Los_Angeles");
    assert.equal(
      schedule.get("nextNotificationAt").toDate().toISOString(),
      "2026-08-13T16:00:00.000Z",
    );
    assert.equal(sender.attempts.length, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("schedule disable or deletion after authorization is never recreated by SENT finalization", async () => {
  const app = initializeApp({ projectId }, "watering-finalize-disable-delete-race-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await seed(firestore, "user-b", true, true);
    const disabled = await authorizeAndSend(firestore, store, sender, "user-a");
    const deleted = await authorizeAndSend(firestore, store, sender, "user-b");
    await settingsStore.updateWateringSettings({
      ownerUid: "user-a",
      expectedRevision: 1,
      defaultTime: "09:00",
      zoneId: "Asia/Seoul",
      globalEnabled: false,
      plants: [{ plantId: "plant-a", enabled: true, timeOverride: null }],
    });
    await firestore.doc("users/user-b/wateringSchedules/plant-a").delete();

    await store.finalizeSent(disabled.attempt, disabled.claimId, disabled.results);
    await store.finalizeSent(deleted.attempt, deleted.claimId, deleted.results);

    const disabledSchedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(disabledSchedule.get("notificationCandidateActive"), false);
    assert.equal(disabledSchedule.get("nextNotificationAt"), undefined);
    assert.equal((await firestore.doc("users/user-b/wateringSchedules/plant-a").get()).exists, false);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 1);
    assert.equal((await firestore.collection("users/user-b/notificationHistory").get()).size, 1);
    assert.equal(sender.attempts.length, 2);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("FAILED and transport-ambiguous finalization merge authoritative schedule versions", async () => {
  const app = initializeApp({ projectId }, "watering-terminal-status-race-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await seed(firestore, "user-b", true, true);
    const failed = await authorizeAndSend(firestore, store, sender, "user-a");
    const ambiguous = await authorizeAndSend(firestore, store, sender, "user-b");
    for (const uid of ["user-a", "user-b"]) {
      await settingsStore.updateWateringSettings({
        ownerUid: uid,
        expectedRevision: 1,
        defaultTime: "08:00",
        zoneId: "Asia/Seoul",
        globalEnabled: true,
        plants: [{ plantId: "plant-a", enabled: true, timeOverride: null }],
      });
    }
    const failures = failed.results.map((result) => ({
      ...result,
      success: false,
      permanent: false,
      errorCode: "messaging/server-unavailable",
    }));

    await store.releaseClaim(failed.attempt, failed.claimId, failures);
    await store.markSendAmbiguous(ambiguous.attempt, ambiguous.claimId);

    for (const uid of ["user-a", "user-b"]) {
      const schedule = await firestore.doc(`users/${uid}/wateringSchedules/plant-a`).get();
      assert.equal(
        schedule.get("nextNotificationAt").toDate().toISOString(),
        "2026-08-12T23:00:00.000Z",
      );
    }
    assert.equal(
      (await firestore.collection("users/user-a/notificationHistory").get()).docs[0]?.get("status"),
      "FAILED",
    );
    assert.equal(
      (await firestore.collection("users/user-b/notificationHistory").get()).docs[0]?.get("status"),
      "DELIVERED_AMBIGUOUS",
    );
    assert.equal(sender.attempts.length, 2);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("expired ambiguous recovery advances with current settings without resending", async () => {
  const app = initializeApp({ projectId }, "watering-recovered-finalize-race-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await authorizeAndSend(firestore, store, sender, "user-a");
    await settingsStore.updateWateringSettings({
      ownerUid: "user-a",
      expectedRevision: 1,
      defaultTime: "08:00",
      zoneId: "Asia/Seoul",
      globalEnabled: true,
      plants: [{ plantId: "plant-a", enabled: true, timeOverride: null }],
    });

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"));
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:12:00Z"));

    const schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(
      schedule.get("nextNotificationAt").toDate().toISOString(),
      "2026-08-12T23:00:00.000Z",
    );
    assert.equal(
      (await firestore.collection("users/user-a/notificationHistory").get()).docs[0]?.get("status"),
      "DELIVERED_AMBIGUOUS",
    );
    assert.equal(sender.attempts.length, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("unchanged authorization advances normally and remains deduplicated", async () => {
  const app = initializeApp({ projectId }, "watering-finalize-unchanged-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeAndSend(firestore, store, sender, "user-a");
    await store.finalizeSent(delivery.attempt, delivery.claimId, delivery.results);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"));

    const schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(
      schedule.get("nextNotificationAt").toDate().toISOString(),
      "2026-08-13T00:00:00.000Z",
    );
    assert.equal(sender.attempts.length, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("expired pre-send authorization is reclaimed after process death without a duplicate call", async () => {
  const app = initializeApp({ projectId }, "watering-pre-send-process-death-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const now = new Date("2026-08-12T00:00:00Z");
    const candidate = (await store.listCandidates(now, 1))[0]!;
    const attempt = selectWateringAttempt(candidate, now)!;
    const claimId = await store.claim(attempt, new Date("2026-08-12T00:10:00Z"));
    assert.ok(claimId);
    const endpoints = await store.eligibleEndpoints("user-a");
    assert.ok(await store.revalidateAndMarkSending(attempt, claimId, endpoints));
    const claimRef = firestore.doc(
      `notificationDeliveryClaims/${createHash("sha256").update(attempt.deduplicationKey).digest("hex")}`,
    );
    assert.equal((await claimRef.get()).get("state"), "AUTHORIZED_PRE_SEND");

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:01:00Z"));
    assert.equal(sender.attempts.length, 0);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"));
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:12:00Z"));

    assert.equal(sender.attempts.length, 1);
    assert.equal((await firestore.collection("users/user-a/notificationDeliveries").get()).size, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("expired may-have-sent claim terminalizes ambiguous and remains OPENED-confirmable", async () => {
  const app = initializeApp({ projectId }, "watering-post-send-process-death-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();
  const claimId = "123e4567-e89b-12d3-a456-426614174100";
  const deduplicationKey = "user-a:plant-a:2026-08-12:0";

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await firestore
      .doc(
        `notificationDeliveryClaims/${createHash("sha256").update(deduplicationKey).digest("hex")}`,
      )
      .set({
        ownerUid: "user-a",
        plantId: "plant-a",
        dueDate: "2026-08-12",
        attempt: 0,
        deduplicationKey,
        state: "SEND_MAY_HAVE_OCCURRED",
        claimId,
        claimedAt: Timestamp.fromDate(new Date("2026-08-12T00:00:00Z")),
        sendMayHaveOccurredAt: Timestamp.fromDate(new Date("2026-08-12T00:00:01Z")),
        expiresAt: Timestamp.fromDate(new Date("2026-08-12T00:10:00Z")),
      });

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:01:00Z"));
    assert.equal(sender.attempts.length, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"));
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:12:00Z"));
    const history = await firestore.doc(`users/user-a/notificationHistory/${claimId}`).get();
    assert.equal(sender.attempts.length, 0);
    assert.equal(history.get("status"), "DELIVERED_AMBIGUOUS");
    assert.equal(history.get("failureKind"), "LEASE_EXPIRED_AFTER_SEND_START");

    await executeConfirmNotificationOpened(
      firestore,
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", deliveryId: claimId },
    );
    assert.equal((await history.ref.get()).get("destinationOpened"), true);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("global disable cannot hide expired post-send orphan from recovery", async () => {
  const app = initializeApp({ projectId }, "watering-orphan-global-disable-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeOnly(firestore, store, "user-a");
    assert.equal(
      await store.markSendMayHaveOccurred(
        delivery.attempt,
        delivery.claimId,
        delivery.endpoints,
      ),
      true,
    );
    await settingsStore.updateWateringSettings({
      ownerUid: "user-a",
      expectedRevision: 1,
      defaultTime: "09:00",
      zoneId: "Asia/Seoul",
      globalEnabled: false,
      plants: [{ plantId: "plant-a", enabled: true, timeOverride: null }],
    });

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"), 3);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:12:00Z"), 3);

    const history = await firestore.collection("users/user-a/notificationHistory").get();
    assert.equal(history.size, 1);
    assert.equal(history.docs[0]?.get("status"), "DELIVERED_AMBIGUOUS");
    assert.equal(history.docs[0]?.get("failureKind"), "LEASE_EXPIRED_AFTER_SEND_START");
    assert.equal(sender.attempts.length, 0);
    const terminalClaim = await firestore.collection("notificationDeliveryClaims").get();
    assert.equal(terminalClaim.docs[0]?.get("state"), "SEND_UNKNOWN");
    assert.deepEqual(
      (await firestore.doc("notificationEndpointOwners/install-a").get()).get(
        "activeSendLeases",
      ),
      {},
    );
    const schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(schedule.get("notificationCandidateActive"), false);
    assert.equal(schedule.get("nextNotificationAt"), undefined);
    await executeConfirmNotificationOpened(
      firestore,
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", deliveryId: history.docs[0]!.id },
    );
    assert.equal((await history.docs[0]!.ref.get()).get("destinationOpened"), true);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("per-plant disable cannot hide expired post-send orphan from recovery", async () => {
  const app = initializeApp({ projectId }, "watering-orphan-plant-disable-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const settingsStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeOnly(firestore, store, "user-a");
    assert.equal(
      await store.markSendMayHaveOccurred(
        delivery.attempt,
        delivery.claimId,
        delivery.endpoints,
      ),
      true,
    );
    await settingsStore.updateWateringSettings({
      ownerUid: "user-a",
      expectedRevision: 1,
      defaultTime: "09:00",
      zoneId: "Asia/Seoul",
      globalEnabled: true,
      plants: [{ plantId: "plant-a", enabled: false, timeOverride: null }],
    });

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"), 3);

    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 1);
    assert.equal(sender.attempts.length, 0);
    assert.equal(
      (await firestore.doc("users/user-a/wateringSchedules/plant-a").get()).get(
        "notificationCandidateActive",
      ),
      false,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("schedule deletion cannot hide expired post-send orphan or recreate schedule", async () => {
  const app = initializeApp({ projectId }, "watering-orphan-schedule-delete-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeOnly(firestore, store, "user-a");
    assert.equal(
      await store.markSendMayHaveOccurred(
        delivery.attempt,
        delivery.claimId,
        delivery.endpoints,
      ),
      true,
    );
    await firestore.doc("users/user-a/wateringSchedules/plant-a").delete();

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"), 3);

    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 1);
    assert.equal((await firestore.doc("users/user-a/wateringSchedules/plant-a").get()).exists, false);
    assert.equal(sender.attempts.length, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("deleted owner retires expired orphan without creating owner history", async () => {
  const app = initializeApp({ projectId }, "watering-orphan-owner-delete-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const delivery = await authorizeOnly(firestore, store, "user-a");
    assert.equal(
      await store.markSendMayHaveOccurred(
        delivery.attempt,
        delivery.claimId,
        delivery.endpoints,
      ),
      true,
    );
    await deleteUserData(firestore, "user-a");
    await firestore.doc("notificationDeliveryClaims/malformed-owner").set({
      ownerUid: "../invalid",
      state: "SEND_MAY_HAVE_OCCURRED",
      expiresAt: Timestamp.fromDate(new Date("2026-08-12T00:10:00Z")),
      claimId: "00000000-0000-4000-8000-000000000999",
    });

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"), 3);

    assert.equal((await firestore.collection("notificationDeliveryClaims").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
    assert.equal(sender.attempts.length, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("notification retention cleanup is bounded idempotent and ignores expired live leases", async () => {
  const app = initializeApp({ projectId }, "watering-retention-cleanup-emulator");
  const firestore = getFirestore(app);
  const now = Timestamp.fromDate(new Date("2026-09-16T00:00:00Z"));
  const future = Timestamp.fromMillis(now.toMillis() + 1);
  const terminalAt = Timestamp.fromDate(new Date("2026-08-12T00:00:00Z"));

  try {
    await clear(firestore);
    const writes = firestore.batch();
    writes.set(firestore.doc("users/user-a"), { ownerUid: "user-a" });
    writes.set(firestore.doc("users/user-a/notificationHistory/expired"), {
      ownerUid: "user-a", terminalAt, expiresAt: now,
    });
    writes.set(firestore.doc("users/user-a/notificationHistory/future"), {
      ownerUid: "user-a", terminalAt, expiresAt: future,
    });
    writes.set(firestore.doc("users/user-a/notificationDeliveries/expired"), {
      ownerUid: "user-a", terminalAt, expiresAt: now,
    });
    writes.set(firestore.doc("notificationDeliveryDiagnostics/expired"), {
      ownerUid: "user-a", terminalAt, expiresAt: now,
    });
    writes.set(firestore.doc("notificationDeliveryClaims/failed-expired"), {
      ownerUid: "user-a", state: "FAILED", terminalAt, expiresAt: now,
    });
    writes.set(firestore.doc("notificationDeliveryClaims/live-expired-lease"), {
      ownerUid: "user-a", state: "AUTHORIZED_PRE_SEND", expiresAt: now,
    });
    writes.set(firestore.doc("notificationDeliveryClaims/failed-future"), {
      ownerUid: "user-a", state: "FAILED", terminalAt, expiresAt: future,
    });
    await writes.commit();

    const first = await cleanupExpiredNotificationRecords(firestore, now, 1);
    const replay = await cleanupExpiredNotificationRecords(firestore, now, 1);

    assert.deepEqual(first, { scanned: 4, deleted: 4, failures: [] });
    assert.deepEqual(replay, { scanned: 0, deleted: 0, failures: [] });
    assert.equal(
      (await firestore.doc("notificationDeliveryClaims/live-expired-lease").get()).exists,
      true,
    );
    assert.equal(
      (await firestore.doc("notificationDeliveryClaims/failed-future").get()).exists,
      true,
    );
    assert.equal(
      (await firestore.doc("users/user-a/notificationHistory/future").get()).exists,
      true,
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("orphan recovery is page bounded advances cursor and cannot starve later claims", async () => {
  const app = initializeApp({ projectId }, "watering-orphan-pagination-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await assert.rejects(() =>
      store.recoverExpiredClaims(new Date("2026-08-12T00:11:00Z"), 0),
    );
    await assert.rejects(() =>
      store.recoverExpiredClaims(new Date("2026-08-12T00:11:00Z"), 501),
    );
    for (let index = 0; index < 7; index += 1) {
      const uid = `orphan-user-${index}`;
      await seed(firestore, uid, true, true);
      await firestore.doc(`users/${uid}/wateringSchedules/plant-a`).update({
        notificationCandidateActive: false,
      });
      await seedOrphanClaim(firestore, uid, index, "SEND_MAY_HAVE_OCCURRED");
    }

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"), 3);
    assert.equal(await notificationHistoryCount(firestore), 3);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:12:00Z"), 3);
    assert.equal(await notificationHistoryCount(firestore), 6);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:13:00Z"), 3);
    assert.equal(await notificationHistoryCount(firestore), 7);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:14:00Z"), 3);

    assert.equal(await notificationHistoryCount(firestore), 7);
    assert.equal(sender.attempts.length, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("targeted tap recovers the 201st expired post-send claim at thirty minutes", async () => {
  const app = initializeApp({ projectId }, "watering-targeted-open-pagination-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();
  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const target = await authorizeOnly(firestore, store, "user-a");
    assert.equal(
      await store.markSendMayHaveOccurred(
        target.attempt,
        target.claimId,
        target.endpoints,
      ),
      true,
    );
    const targetDeliveryId = target.claimId;
    const targetClaim = firestore.doc(
      `notificationDeliveryClaims/${createHash("sha256")
        .update(target.attempt.deduplicationKey)
        .digest("hex")}`,
    );
    await targetClaim.update({
      expiresAt: Timestamp.fromDate(new Date("2026-08-12T00:10:01Z")),
    });
    await firestore.doc("users/user-a/wateringSchedules/plant-a").delete();
    await Promise.all(
      Array.from({ length: 200 }, (_, index) =>
        seedOrphanClaim(firestore, "user-a", index, "SEND_MAY_HAVE_OCCURRED"),
      ),
    );

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:15:00Z"), 100);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:30:00Z"), 100);
    const targetHistoryRef = firestore.doc(
      `users/user-a/notificationHistory/${targetDeliveryId}`,
    );
    assert.equal((await targetHistoryRef.get()).exists, false);

    await executeConfirmNotificationOpened(
      firestore,
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", deliveryId: targetDeliveryId },
      new Date("2026-08-12T00:30:00Z"),
    );
    const firstOpen = await targetHistoryRef.get();
    const firstOpenedAt = firstOpen.get("openedAt");
    await executeConfirmNotificationOpened(
      firestore,
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", deliveryId: targetDeliveryId },
      new Date("2026-08-12T00:30:01Z"),
    );
    const repeatedOpen = await targetHistoryRef.get();

    assert.equal(firstOpen.get("status"), "SENT");
    assert.equal(firstOpen.get("destinationOpened"), true);
    assert.equal(firstOpen.get("failureKind"), "LEASE_EXPIRED_AFTER_SEND_START");
    assert.ok(firstOpen.get("deliveryConfirmedAt") instanceof Timestamp);
    assert.ok(firstOpen.get("ambiguousAt") instanceof Timestamp);
    assert.ok(firstOpenedAt instanceof Timestamp);
    assert.equal(repeatedOpen.get("revision"), 2);
    assert.equal(repeatedOpen.get("openedAt").toMillis(), firstOpenedAt.toMillis());
    assert.equal((await targetClaim.get()).get("state"), "SEND_UNKNOWN");
    assert.equal(
      (await firestore.doc("users/user-a/wateringSchedules/plant-a").get()).exists,
      false,
    );
    assert.deepEqual(
      (await firestore.doc("notificationEndpointOwners/install-a").get()).get(
        "activeSendLeases",
      ),
      {},
    );
    assert.equal(await notificationHistoryCount(firestore), 201);
    assert.equal(sender.attempts.length, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("targeted tap keeps live claims retryable and never forges pre-send delivery", async () => {
  const app = initializeApp({ projectId }, "watering-targeted-open-state-emulator");
  const firestore = getFirestore(app);

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const live = await seedOrphanClaim(
      firestore,
      "user-a",
      301,
      "SEND_MAY_HAVE_OCCURRED",
      new Date("2026-08-12T00:40:00Z"),
    );
    const livePreSend = await seedOrphanClaim(
      firestore,
      "user-a",
      304,
      "AUTHORIZED_PRE_SEND",
      new Date("2026-08-12T00:40:00Z"),
    );
    const claimed = await seedOrphanClaim(firestore, "user-a", 302, "CLAIMED");
    const authorized = await seedOrphanClaim(
      firestore,
      "user-a",
      303,
      "AUTHORIZED_PRE_SEND",
    );

    for (const suffix of [301, 304]) {
      await assert.rejects(
        () => executeConfirmNotificationOpened(
          firestore,
          { uid: "user-a" },
          {
            expectedOwnerUid: "user-a",
            deliveryId: `00000000-0000-4000-8000-${String(suffix).padStart(12, "0")}`,
          },
          new Date("2026-08-12T00:30:00Z"),
        ),
        (error: unknown) => error instanceof Error &&
          "code" in error && error.code === "aborted",
      );
    }
    for (const suffix of [302, 303]) {
      await assert.rejects(
        () => executeConfirmNotificationOpened(
          firestore,
          { uid: "user-a" },
          {
            expectedOwnerUid: "user-a",
            deliveryId: `00000000-0000-4000-8000-${String(suffix).padStart(12, "0")}`,
          },
          new Date("2026-08-12T00:30:00Z"),
        ),
        (error: unknown) => error instanceof Error &&
          "code" in error && error.code === "failed-precondition",
      );
    }

    assert.equal((await live.get()).get("state"), "SEND_MAY_HAVE_OCCURRED");
    assert.equal((await livePreSend.get()).get("state"), "AUTHORIZED_PRE_SEND");
    assert.equal((await claimed.get()).exists, false);
    assert.equal((await authorized.get()).exists, false);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("targeted tap isolates wrong owners and retires malformed claims", async () => {
  const app = initializeApp({ projectId }, "watering-targeted-open-isolation-emulator");
  const firestore = getFirestore(app);
  const foreignDeliveryId = "00000000-0000-4000-8000-000000000401";
  const malformedDeliveryId = "00000000-0000-4000-8000-000000000402";
  const missingDeliveryId = "00000000-0000-4000-8000-000000000403";
  const deletedOwnerDeliveryId = "00000000-0000-4000-8000-000000000405";

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await seed(firestore, "user-b", true, true);
    await seed(firestore, "user-c", true, true);
    const foreign = await seedOrphanClaim(
      firestore,
      "user-b",
      401,
      "SEND_MAY_HAVE_OCCURRED",
    );
    const deletedOwner = await seedOrphanClaim(
      firestore,
      "user-c",
      405,
      "SEND_MAY_HAVE_OCCURRED",
    );
    await deleteUserData(firestore, "user-c");
    const malformed = firestore.doc("notificationDeliveryClaims/malformed-targeted-open");
    await malformed.set({
      ownerUid: "user-a",
      claimId: malformedDeliveryId,
      state: "SENDING",
      expiresAt: Timestamp.fromDate(new Date("2026-08-12T00:10:00Z")),
    });

    for (const deliveryId of [foreignDeliveryId, missingDeliveryId]) {
      await assert.rejects(
        () => executeConfirmNotificationOpened(
          firestore,
          { uid: "user-a" },
          { expectedOwnerUid: "user-a", deliveryId },
          new Date("2026-08-12T00:30:00Z"),
        ),
        (error: unknown) => error instanceof Error &&
          "code" in error && error.code === "not-found",
      );
    }
    await assert.rejects(
      () => executeConfirmNotificationOpened(
        firestore,
        { uid: "user-c" },
        { expectedOwnerUid: "user-c", deliveryId: deletedOwnerDeliveryId },
        new Date("2026-08-12T00:30:00Z"),
      ),
      (error: unknown) => error instanceof Error &&
        "code" in error && error.code === "not-found",
    );
    await assert.rejects(
      () => executeConfirmNotificationOpened(
        firestore,
        { uid: "user-a" },
        { expectedOwnerUid: "user-a", deliveryId: malformedDeliveryId },
        new Date("2026-08-12T00:30:00Z"),
      ),
      (error: unknown) => error instanceof Error &&
        "code" in error && error.code === "failed-precondition",
    );

    assert.equal((await foreign.get()).exists, true);
    assert.equal((await deletedOwner.get()).exists, false);
    assert.equal((await malformed.get()).exists, false);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("targeted tap recovers an expired legacy SENDING claim", async () => {
  const app = initializeApp({ projectId }, "watering-targeted-open-legacy-emulator");
  const firestore = getFirestore(app);
  const deliveryId = "00000000-0000-4000-8000-000000000404";

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await seedOrphanClaim(firestore, "user-a", 404, "SENDING");

    await executeConfirmNotificationOpened(
      firestore,
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", deliveryId },
      new Date("2026-08-12T00:30:00Z"),
    );

    const history = await firestore.doc(`users/user-a/notificationHistory/${deliveryId}`).get();
    assert.equal(history.get("status"), "SENT");
    assert.equal(history.get("destinationOpened"), true);
    assert.ok(history.get("ambiguousAt") instanceof Timestamp);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("live orphan lease remains untouched until expiry", async () => {
  const app = initializeApp({ projectId }, "watering-orphan-live-lease-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await firestore.doc("users/user-a/wateringSchedules/plant-a").update({
      notificationCandidateActive: false,
    });
    const claimRef = await seedOrphanClaim(
      firestore,
      "user-a",
      100,
      "SEND_MAY_HAVE_OCCURRED",
      new Date("2026-08-12T00:20:00Z"),
    );

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"), 3);

    assert.equal((await claimRef.get()).get("state"), "SEND_MAY_HAVE_OCCURRED");
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
    assert.equal(sender.attempts.length, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("expired pre-send claims are reclaimed without history or sender calls", async () => {
  const app = initializeApp({ projectId }, "watering-orphan-pre-send-reclaim-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await firestore.doc("users/user-a/wateringSchedules/plant-a").update({
      notificationCandidateActive: false,
    });
    await seedOrphanClaim(firestore, "user-a", 101, "CLAIMED");
    await seedOrphanClaim(firestore, "user-a", 102, "AUTHORIZED_PRE_SEND");

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"), 3);

    assert.equal((await firestore.collection("notificationDeliveryClaims").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
    assert.equal(sender.attempts.length, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("legacy SENDING orphan becomes one terminal ambiguous history", async () => {
  const app = initializeApp({ projectId }, "watering-orphan-legacy-sending-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await firestore.doc("users/user-a/wateringSchedules/plant-a").update({
      notificationCandidateActive: false,
    });
    await seedOrphanClaim(firestore, "user-a", 103, "SENDING");

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:11:00Z"), 3);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:12:00Z"), 3);

    const history = await firestore.collection("users/user-a/notificationHistory").get();
    assert.equal(history.size, 1);
    assert.equal(history.docs[0]?.get("status"), "DELIVERED_AMBIGUOUS");
    assert.equal(sender.attempts.length, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("history persistence failure recovers after lease expiry without resending", async () => {
  const app = initializeApp({ projectId }, "watering-history-write-recovery-emulator");
  const firestore = getFirestore(app);
  const delegate = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();
  const failingHistoryStore: WateringDeliveryStore = {
    recoverExpiredClaims: delegate.recoverExpiredClaims.bind(delegate),
    listCandidates: delegate.listCandidates.bind(delegate),
    claim: delegate.claim.bind(delegate),
    eligibleEndpoints: delegate.eligibleEndpoints.bind(delegate),
    revalidateAndMarkSending: delegate.revalidateAndMarkSending.bind(delegate),
    markSendMayHaveOccurred: delegate.markSendMayHaveOccurred.bind(delegate),
    markSendAmbiguous: delegate.markSendAmbiguous.bind(delegate),
    releaseSendAuthorization: delegate.releaseSendAuthorization.bind(delegate),
    async markFinalizationAmbiguous() {
      throw new Error("notification history unavailable");
    },
    async finalizeSent() {
      throw new Error("receipt finalization unavailable");
    },
    releaseClaim: delegate.releaseClaim.bind(delegate),
    deleteEndpoints: delegate.deleteEndpoints.bind(delegate),
  };

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await assert.rejects(() =>
      runWateringDeliveryScan(
        failingHistoryStore,
        sender,
        new Date("2026-08-12T00:00:00Z"),
      ),
    );
    assert.equal(sender.attempts.length, 1);
    assert.equal((await firestore.collection("users/user-a/notificationHistory").get()).size, 0);
    const orphanedClaim = await firestore.collection("notificationDeliveryClaims").get();
    assert.equal(orphanedClaim.docs[0]?.get("state"), "SEND_MAY_HAVE_OCCURRED");

    await runWateringDeliveryScan(delegate, sender, new Date("2026-08-12T00:11:00Z"));
    await runWateringDeliveryScan(delegate, sender, new Date("2026-08-12T00:12:00Z"));
    const history = await firestore.collection("users/user-a/notificationHistory").get();
    assert.equal(sender.attempts.length, 1);
    assert.equal(history.size, 1);
    assert.equal(history.docs[0]?.get("status"), "DELIVERED_AMBIGUOUS");
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("transport-unknown send remains deduped and can be confirmed OPENED by a tap", async () => {
  const app = initializeApp({ projectId }, "watering-transport-unknown-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender: WateringPushSender = {
    async send() {
      throw new Error("FCM response was lost");
    },
  };

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);

    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:00:00Z"));
    const history = await firestore.collection("users/user-a/notificationHistory").get();
    assert.equal(history.size, 1);
    assert.equal(history.docs[0]?.get("status"), "DELIVERED_AMBIGUOUS");
    assert.equal(history.docs[0]?.get("failureKind"), "TRANSPORT_UNKNOWN");
    assert.ok(history.docs[0]?.get("deliveryConfirmedAt") instanceof Timestamp);
    assert.ok(history.docs[0]?.get("ambiguousAt") instanceof Timestamp);

    await executeConfirmNotificationOpened(
      firestore,
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", deliveryId: history.docs[0]!.id },
    );
    const opened = await history.docs[0]!.ref.get();
    assert.equal(opened.get("status"), "SENT");
    assert.equal(opened.get("destinationOpened"), true);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("FCM success with receipt finalization failure remains at-most-once and becomes OPENED", async () => {
  const app = initializeApp({ projectId }, "watering-finalization-ambiguous-emulator");
  const firestore = getFirestore(app);
  const delegate = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();
  const store: WateringDeliveryStore = {
    recoverExpiredClaims: delegate.recoverExpiredClaims.bind(delegate),
    listCandidates: delegate.listCandidates.bind(delegate),
    claim: delegate.claim.bind(delegate),
    eligibleEndpoints: delegate.eligibleEndpoints.bind(delegate),
    revalidateAndMarkSending: delegate.revalidateAndMarkSending.bind(delegate),
    markSendMayHaveOccurred: delegate.markSendMayHaveOccurred.bind(delegate),
    markSendAmbiguous: delegate.markSendAmbiguous.bind(delegate),
    releaseSendAuthorization: delegate.releaseSendAuthorization.bind(delegate),
    markFinalizationAmbiguous: delegate.markFinalizationAmbiguous.bind(delegate),
    async finalizeSent() {
      throw new Error("receipt transaction unavailable before commit");
    },
    releaseClaim: delegate.releaseClaim.bind(delegate),
    deleteEndpoints: delegate.deleteEndpoints.bind(delegate),
  };

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);

    const first = await runWateringDeliveryScan(
      store,
      sender,
      new Date("2026-08-12T00:00:00Z"),
    );
    const history = await firestore.collection("users/user-a/notificationHistory").get();
    assert.equal(first.failed, 1);
    assert.equal(sender.attempts.length, 1);
    assert.equal(history.size, 1);
    assert.equal(history.docs[0]?.get("status"), "DELIVERED_AMBIGUOUS");

    await executeConfirmNotificationOpened(
      firestore,
      { uid: "user-a" },
      { expectedOwnerUid: "user-a", deliveryId: history.docs[0]!.id },
    );
    const opened = await history.docs[0]!.ref.get();
    assert.equal(opened.get("status"), "SENT");
    assert.equal(opened.get("destinationOpened"), true);

    await runWateringDeliveryScan(
      store,
      sender,
      new Date("2026-08-12T00:06:00Z"),
    );
    assert.equal(sender.attempts.length, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("explicit FCM rejection writes append-only FAILED history without endpoint data", async () => {
  const app = initializeApp({ projectId }, "watering-failed-history-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender: WateringPushSender = {
    async send(_attempt, endpoints) {
      return endpoints.map((endpoint) => ({
        ...endpoint,
        success: false,
        permanent: false,
        errorCode: "messaging/server-unavailable",
      }));
    },
  };

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:00:00Z"));

    const history = await firestore.collection("users/user-a/notificationHistory").get();
    assert.equal(history.size, 1);
    assert.equal(history.docs[0]?.get("status"), "FAILED");
    assert.ok(history.docs[0]?.get("failedAt") instanceof Timestamp);
    assert.equal(history.docs[0]?.get("endpointResults"), undefined);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("emulator completion or disable after claim and token lookup releases without send", async () => {
  const app = initializeApp({ projectId }, "watering-post-claim-race-emulator");
  const firestore = getFirestore(app);
  const delegate = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    let changed = false;
    const racing: WateringDeliveryStore = {
      ...delegate,
      recoverExpiredClaims: delegate.recoverExpiredClaims.bind(delegate),
      listCandidates: delegate.listCandidates.bind(delegate),
      claim: delegate.claim.bind(delegate),
      async eligibleEndpoints(ownerUid: string) {
        const endpoints = await delegate.eligibleEndpoints(ownerUid);
        await firestore.doc("users/user-a/wateringSchedules/plant-a").update({ dueDate: "2026-08-22" });
        changed = true;
        return endpoints;
      },
      revalidateAndMarkSending: delegate.revalidateAndMarkSending.bind(delegate),
      markSendMayHaveOccurred: delegate.markSendMayHaveOccurred.bind(delegate),
      markSendAmbiguous: delegate.markSendAmbiguous.bind(delegate),
      releaseSendAuthorization: delegate.releaseSendAuthorization.bind(delegate),
      markFinalizationAmbiguous: delegate.markFinalizationAmbiguous.bind(delegate),
      finalizeSent: delegate.finalizeSent.bind(delegate),
      releaseClaim: delegate.releaseClaim.bind(delegate),
      deleteEndpoints: delegate.deleteEndpoints.bind(delegate),
    };

    await runWateringDeliveryScan(racing, sender, new Date("2026-08-12T00:00:00Z"));

    assert.equal(changed, true);
    assert.equal(sender.attempts.length, 0);
    assert.equal((await firestore.collection("users/user-a/notificationDeliveries").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("endpoint revocation after lookup is revalidated before FCM send", async () => {
  const app = initializeApp({ projectId }, "watering-endpoint-revocation-race-emulator");
  const firestore = getFirestore(app);
  const delegate = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const racing: WateringDeliveryStore = {
      recoverExpiredClaims: delegate.recoverExpiredClaims.bind(delegate),
      listCandidates: delegate.listCandidates.bind(delegate),
      claim: delegate.claim.bind(delegate),
      async eligibleEndpoints(ownerUid: string) {
        const endpoints = await delegate.eligibleEndpoints(ownerUid);
        await firestore.doc("users/user-a/notificationEndpoints/install-a").delete();
        await firestore.doc("notificationEndpointOwners/install-a").update({
          state: "UNREGISTERED",
          generation: 2,
        });
        return endpoints;
      },
      revalidateAndMarkSending: delegate.revalidateAndMarkSending.bind(delegate),
      markSendMayHaveOccurred: delegate.markSendMayHaveOccurred.bind(delegate),
      markSendAmbiguous: delegate.markSendAmbiguous.bind(delegate),
      releaseSendAuthorization: delegate.releaseSendAuthorization.bind(delegate),
      markFinalizationAmbiguous: delegate.markFinalizationAmbiguous.bind(delegate),
      finalizeSent: delegate.finalizeSent.bind(delegate),
      releaseClaim: delegate.releaseClaim.bind(delegate),
      deleteEndpoints: delegate.deleteEndpoints.bind(delegate),
    };

    await runWateringDeliveryScan(racing, sender, new Date("2026-08-12T00:00:00Z"));

    assert.equal(sender.attempts.length, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("unregister cannot succeed between endpoint validation and sender send", async () => {
  const app = initializeApp({ projectId }, "watering-unregister-send-lease-emulator");
  const firestore = getFirestore(app);
  const delegate = new FirestoreWateringDeliveryStore(firestore);
  const endpointStore = new FirestoreNotificationSettingsStore(firestore);
  const sender = new RecordingSender();
  const secret = "send-lease-secret-123456";
  let blockedCode: string | undefined;

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    await firestore.doc("notificationEndpointOwners/install-a").update({
      secretHash: createHash("sha256").update(secret).digest("hex"),
    });
    const revocation = {
      ownerUid: "user-a",
      installationId: "install-a",
      installationSecret: secret,
      generation: 2,
    };
    const racing: WateringDeliveryStore = {
      recoverExpiredClaims: delegate.recoverExpiredClaims.bind(delegate),
      listCandidates: delegate.listCandidates.bind(delegate),
      claim: delegate.claim.bind(delegate),
      eligibleEndpoints: delegate.eligibleEndpoints.bind(delegate),
      async revalidateAndMarkSending(attempt, claimId, endpoints) {
        const authorized = await delegate.revalidateAndMarkSending(attempt, claimId, endpoints);
        try {
          await endpointStore.unregisterEndpoint(revocation);
        } catch (error) {
          blockedCode = (error as { code?: string }).code;
        }
        return authorized;
      },
      markSendMayHaveOccurred: delegate.markSendMayHaveOccurred.bind(delegate),
      markSendAmbiguous: delegate.markSendAmbiguous.bind(delegate),
      releaseSendAuthorization: delegate.releaseSendAuthorization.bind(delegate),
      markFinalizationAmbiguous: delegate.markFinalizationAmbiguous.bind(delegate),
      finalizeSent: delegate.finalizeSent.bind(delegate),
      releaseClaim: delegate.releaseClaim.bind(delegate),
      deleteEndpoints: delegate.deleteEndpoints.bind(delegate),
    };

    await runWateringDeliveryScan(racing, sender, new Date("2026-08-12T00:00:00Z"));
    assert.equal(blockedCode, "aborted");
    assert.equal(sender.attempts.length, 1);

    assert.equal(await endpointStore.unregisterEndpoint(revocation), "REVOKED");
    await runWateringDeliveryScan(racing, sender, new Date("2026-08-12T00:06:00Z"));
    assert.equal(sender.attempts.length, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("emulator settings disable after claim and token lookup releases without send", async () => {
  const app = initializeApp({ projectId }, "watering-post-claim-disable-emulator");
  const firestore = getFirestore(app);
  const delegate = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const racing: WateringDeliveryStore = {
      ...delegate,
      recoverExpiredClaims: delegate.recoverExpiredClaims.bind(delegate),
      listCandidates: delegate.listCandidates.bind(delegate),
      claim: delegate.claim.bind(delegate),
      async eligibleEndpoints(ownerUid: string) {
        const endpoints = await delegate.eligibleEndpoints(ownerUid);
        await firestore.doc("users/user-a/notificationSettings/watering").update({ wateringEnabled: false });
        await firestore.doc("users/user-a/wateringSchedules/plant-a").update({ notificationCandidateActive: false });
        return endpoints;
      },
      revalidateAndMarkSending: delegate.revalidateAndMarkSending.bind(delegate),
      markSendMayHaveOccurred: delegate.markSendMayHaveOccurred.bind(delegate),
      markSendAmbiguous: delegate.markSendAmbiguous.bind(delegate),
      releaseSendAuthorization: delegate.releaseSendAuthorization.bind(delegate),
      markFinalizationAmbiguous: delegate.markFinalizationAmbiguous.bind(delegate),
      finalizeSent: delegate.finalizeSent.bind(delegate),
      releaseClaim: delegate.releaseClaim.bind(delegate),
      deleteEndpoints: delegate.deleteEndpoints.bind(delegate),
    };

    await runWateringDeliveryScan(racing, sender, new Date("2026-08-12T00:00:00Z"));

    assert.equal(sender.attempts.length, 0);
    assert.equal((await firestore.collection("users/user-a/notificationDeliveries").get()).size, 0);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("emulator denied endpoint capability never consumes attempt and duplicate tokens are grouped", async () => {
  const app = initializeApp({ projectId }, "watering-capability-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, false);
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:00:00Z"));
    assert.equal(sender.attempts.length, 0);

    await firestore.doc("users/user-a/notificationEndpoints/install-a").update({ notificationsEnabled: true });
    await firestore.doc("notificationEndpointOwners/install-a").update({ notificationsEnabled: true });
    const duplicateEndpoint = {
      ownerUid: "user-a", installationId: "install-b", token: "token-user-a", platform: "ANDROID", notificationsEnabled: true, generation: 1,
    };
    await firestore.doc("users/user-a/notificationEndpoints/install-b").set(duplicateEndpoint);
    await firestore.doc("notificationEndpointOwners/install-b").set({
      ...duplicateEndpoint,
      state: "REGISTERED",
    });
    await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:06:00Z"));

    assert.equal(sender.attempts.length, 1);
    const diagnostics = (await firestore.collection("notificationDeliveryDiagnostics").get())
      .docs[0];
    assert.deepEqual(diagnostics?.get("endpointResults")[0].endpointIds.sort(), [
      "install-a",
      "install-b",
    ]);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("old invalid-token result cannot delete a newly rotated endpoint", async () => {
  const app = initializeApp({ projectId }, "watering-token-cleanup-race-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);

  try {
    await clear(firestore);
    await seed(firestore, "user-a", true, true);
    const oldTarget = (await store.eligibleEndpoints("user-a"))[0]!;
    await firestore.doc("users/user-a/notificationEndpoints/install-a").update({
      generation: 2,
      token: "rotated-token",
    });
    await firestore.doc("notificationEndpointOwners/install-a").update({
      generation: 2,
      token: "rotated-token",
    });

    await store.deleteEndpoints("user-a", [
      { ...oldTarget, success: false, permanent: true },
    ]);

    const endpoint = await firestore.doc("users/user-a/notificationEndpoints/install-a").get();
    assert.equal(endpoint.get("generation"), 2);
    assert.equal(endpoint.get("token"), "rotated-token");
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("bounded scan advances beyond hostile no-endpoint pages", async () => {
  const app = initializeApp({ projectId }, "watering-hostile-pages-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);
  const sender = new RecordingSender();

  try {
    await clear(firestore);
    for (let index = 0; index < 6; index += 1) {
      await seed(firestore, `user-${index}`, true, index >= 4);
    }
    const now = new Date("2026-08-12T00:00:00Z");
    await runWateringDeliveryScan(store, sender, now, 2);
    await runWateringDeliveryScan(store, sender, now, 2);
    await runWateringDeliveryScan(store, sender, now, 2);

    assert.equal(sender.attempts.length, 2);
    assert.deepEqual(sender.attempts.map((attempt) => attempt.ownerUid).sort(), ["user-4", "user-5"]);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("emulator due candidate query is indexed and bounded", async () => {
  const app = initializeApp({ projectId }, "watering-bounded-query-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringDeliveryStore(firestore);

  try {
    await clear(firestore);
    for (let index = 0; index < 6; index += 1) await seed(firestore, `user-${index}`, true, true);

    const candidates = await store.listCandidates(new Date("2026-08-12T00:00:00Z"), 3);

    assert.equal(candidates.length, 3);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

async function authorizeOnly(
  firestore: ReturnType<typeof getFirestore>,
  store: FirestoreWateringDeliveryStore,
  ownerUid: string,
): Promise<Readonly<{
  attempt: DueWateringAttempt;
  claimId: string;
  endpoints: readonly WateringEndpointTarget[];
}>> {
  const now = new Date("2026-08-12T00:00:00Z");
  const candidates = await store.listCandidates(now, 100);
  const candidate = candidates.find((item) => item.ownerUid === ownerUid);
  assert.ok(candidate);
  const attempt = selectWateringAttempt(candidate, now);
  assert.ok(attempt);
  const claimId = await store.claim(attempt, new Date("2026-08-12T00:10:00Z"));
  assert.ok(claimId);
  const endpoints = await store.eligibleEndpoints(ownerUid);
  const authorized = await store.revalidateAndMarkSending(attempt, claimId, endpoints);
  assert.ok(authorized);
  return { attempt, claimId, endpoints: authorized };
}

async function authorizeAndSend(
  firestore: ReturnType<typeof getFirestore>,
  store: FirestoreWateringDeliveryStore,
  sender: RecordingSender,
  ownerUid: string,
): Promise<Readonly<{
  attempt: DueWateringAttempt;
  claimId: string;
  results: readonly EndpointDeliveryResult[];
}>> {
  const delivery = await authorizeOnly(firestore, store, ownerUid);
  assert.equal(
    await store.markSendMayHaveOccurred(
      delivery.attempt,
      delivery.claimId,
      delivery.endpoints,
    ),
    true,
  );
  const results = await sender.send(delivery.attempt, delivery.endpoints);
  return { attempt: delivery.attempt, claimId: delivery.claimId, results };
}

async function seedOrphanClaim(
  firestore: ReturnType<typeof getFirestore>,
  uid: string,
  suffix: number,
  state: "CLAIMED" | "AUTHORIZED_PRE_SEND" | "SEND_MAY_HAVE_OCCURRED" | "SENDING",
  expiresAt = new Date("2026-08-12T00:10:00Z"),
) {
  const claimId = `00000000-0000-4000-8000-${String(suffix).padStart(12, "0")}`;
  const plantId = `plant-${suffix}`;
  const deduplicationKey = `${uid}:${plantId}:2026-08-12:0`;
  const claimRef = firestore.doc(
    `notificationDeliveryClaims/${createHash("sha256")
      .update(deduplicationKey)
      .digest("hex")}`,
  );
  await claimRef.set({
    ownerUid: uid,
    plantId,
    dueDate: "2026-08-12",
    attempt: 0,
    deduplicationKey,
    state,
    claimId,
    claimedAt: Timestamp.fromDate(new Date("2026-08-12T00:00:00Z")),
    expiresAt: Timestamp.fromDate(expiresAt),
  });
  return claimRef;
}

async function notificationHistoryCount(
  firestore: ReturnType<typeof getFirestore>,
): Promise<number> {
  const users = await firestore.collection("users").get();
  const histories = await Promise.all(
    users.docs.map((user) => user.ref.collection("notificationHistory").get()),
  );
  return histories.reduce((total, history) => total + history.size, 0);
}

async function deleteUserData(
  firestore: ReturnType<typeof getFirestore>,
  uid: string,
): Promise<void> {
  const user = firestore.doc(`users/${uid}`);
  for (const collection of [
    "personalPlants",
    "notificationSettings",
    "notificationPlantSettings",
    "wateringSchedules",
    "notificationEndpoints",
    "notificationDeliveries",
    "notificationHistory",
  ]) {
    const snapshot = await user.collection(collection).get();
    await Promise.all(snapshot.docs.map((document) => document.ref.delete()));
  }
  await user.delete();
}

async function seed(
  firestore: ReturnType<typeof getFirestore>,
  uid: string,
  enabled: boolean,
  notificationsEnabled: boolean,
): Promise<void> {
  await firestore.doc(`users/${uid}`).set({
    ownerUid: uid,
    displayName: uid,
    providers: ["GOOGLE"],
    zoneId: "Asia/Seoul",
    revision: 1,
  });
  await firestore.doc(`users/${uid}/personalPlants/plant-a`).set({ ownerUid: uid, displayName: "몬스테라" });
  await firestore.doc(`users/${uid}/notificationSettings/watering`).set({
    ownerUid: uid, wateringEnabled: enabled, defaultTime: "09:00", zoneId: "Asia/Seoul", revision: 1,
  });
  await firestore.doc(`users/${uid}/notificationPlantSettings/plant-a`).set({
    ownerUid: uid, plantId: "plant-a", enabled: true, timeOverride: null, revision: 1,
  });
  await firestore.doc(`users/${uid}/wateringSchedules/plant-a`).set({
    ownerUid: uid,
    plantId: "plant-a",
    dueDate: "2026-08-12",
    zoneId: "stale-ignored",
    notificationCandidateActive: true,
    nextNotificationAt: Timestamp.fromDate(new Date("2026-08-12T00:00:00Z")),
    revision: 2,
  });
  const endpointId = uid === "user-a" ? "install-a" : `install-${uid}`;
  const endpoint = {
    ownerUid: uid,
    installationId: endpointId,
    token: `token-${uid}`,
    platform: "ANDROID",
    notificationsEnabled,
    generation: 1,
  };
  await firestore.doc(`users/${uid}/notificationEndpoints/${endpointId}`).set(endpoint);
  await firestore.doc(`notificationEndpointOwners/${endpointId}`).set({
    ...endpoint,
    state: "REGISTERED",
    secretHash: `secret-${uid}`,
  });
}

async function clear(firestore: ReturnType<typeof getFirestore>): Promise<void> {
  const users = await firestore.collection("users").get();
  for (const user of users.docs) {
    for (const collection of [
      "personalPlants", "notificationSettings", "notificationPlantSettings", "wateringSchedules", "notificationEndpoints", "notificationDeliveries", "notificationHistory",
    ]) {
      const snapshot = await user.ref.collection(collection).get();
      await Promise.all(snapshot.docs.map((document) => document.ref.delete()));
    }
    await user.ref.delete();
  }
  for (const collection of [
    "notificationDeliveryClaims",
    "notificationEndpointOwners",
    "notificationRuntime",
    "notificationDeliveryDiagnostics",
  ]) {
    const snapshot = await firestore.collection(collection).get();
    await Promise.all(snapshot.docs.map((document) => document.ref.delete()));
  }
}
