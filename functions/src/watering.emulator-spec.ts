import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { FirestoreWateringCompletionStore } from "./firestore-watering-store.js";
import { WateringError, executeWateringCompletion } from "./watering.js";

const projectId = "demo-planterior";
const now = new Date("2026-08-12T00:30:00Z");
const request = {
  expectedOwnerUid: "user-a",
  collection: "wateringCompletions",
  documentId: "plant-a",
  mutationType: "UPDATE",
  expectedRevision: 4,
  idempotencyKey: "watering-operation-stable",
  payload: {},
};
const paths = [
  "users/user-a",
  "users/user-a/personalPlants/plant-a",
  "users/user-a/wateringSchedules/plant-a",
  "users/user-a/notificationSettings/watering",
  "users/user-a/notificationPlantSettings/plant-a",
  "users/user-a/wateringRecords/watering-operation-stable",
  "users/user-a/operations/watering-operation-stable",
  "plantContents/species-a",
];

test("emulator transaction writes one immutable receipt and duplicate ignores mutable current state", async () => {
  assert.equal(process.env.GCLOUD_PROJECT, projectId);
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  const app = initializeApp({ projectId }, "watering-emulator-success");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringCompletionStore(firestore);

  try {
    await clear(firestore);
    await seed(firestore, 10, "PUBLIC");

    assert.deepEqual(
      await executeWateringCompletion({ uid: "user-a" }, request, store, now),
      { kind: "applied", revision: 5 },
    );

    const [plant, schedule, record, operation] = await Promise.all([
      firestore.doc("users/user-a/personalPlants/plant-a").get(),
      firestore.doc("users/user-a/wateringSchedules/plant-a").get(),
      firestore.doc("users/user-a/wateringRecords/watering-operation-stable").get(),
      firestore.doc("users/user-a/operations/watering-operation-stable").get(),
    ]);
    assert.equal(plant.get("lastWateredDate"), "2026-08-12");
    assert.equal(plant.get("revision"), 5);
    assert.equal(schedule.get("dueDate"), "2026-08-22");
    assert.equal(schedule.get("revision"), 3);
    assert.equal(schedule.get("reminderTime"), undefined);
    assert.equal(schedule.get("enabled"), undefined);
    assert.equal(schedule.get("notificationCandidateActive"), true);
    assert.ok(schedule.get("nextNotificationAt") instanceof Timestamp);
    assert.equal(
      (await firestore.doc("users/user-a/notificationPlantSettings/plant-a").get()).get("timeOverride"),
      "09:00",
    );
    assert.equal(record.get("wateredDate"), "2026-08-12");
    assert.ok(record.get("recordedAt") instanceof Timestamp);
    assert.deepEqual(
      {
        wateredDate: operation.get("wateredDate"),
        dueDate: operation.get("dueDate"),
        recordId: operation.get("recordId"),
        plantRevision: operation.get("plantRevision"),
        scheduleRevision: operation.get("scheduleRevision"),
        zoneId: operation.get("zoneId"),
      },
      {
        wateredDate: "2026-08-12",
        dueDate: "2026-08-22",
        recordId: "watering-operation-stable",
        plantRevision: 5,
        scheduleRevision: 3,
        zoneId: "Asia/Seoul",
      },
    );
    assert.ok(operation.get("recordedAt") instanceof Timestamp);
    assert.match(operation.get("requestHash"), /^[a-f0-9]{64}$/);
    const immutableReceipt = operation.data();

    await firestore.doc("users/user-a/personalPlants/plant-a").update({
      lastWateredDate: "2030-01-01",
      revision: 99,
    });
    await firestore.doc("users/user-a/wateringSchedules/plant-a").update({
      dueDate: "2030-01-11",
      revision: 99,
    });
    await assert.rejects(
      () =>
        executeWateringCompletion(
          { uid: "user-a" },
          { ...request, payload: { wateredDate: "2026-08-11" } },
          store,
          now,
        ),
      (error: unknown) => error instanceof WateringError && error.code === "invalid-argument",
    );

    assert.deepEqual(
      await executeWateringCompletion({ uid: "user-a" }, request, store, now),
      { kind: "duplicate", revision: 5 },
    );
    assert.deepEqual(
      (await firestore.doc("users/user-a/operations/watering-operation-stable").get()).data(),
      immutableReceipt,
    );
    assert.equal((await firestore.collection("users/user-a/wateringRecords").get()).size, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("first completion creates an authoritative due schedule without notification defaults", async () => {
  const app = initializeApp({ projectId }, "watering-emulator-no-preferences");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringCompletionStore(firestore);

  try {
    await clear(firestore);
    await seed(firestore, 10, "PUBLIC", false);

    assert.deepEqual(
      await executeWateringCompletion({ uid: "user-a" }, request, store, now),
      { kind: "applied", revision: 5 },
    );

    const schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(schedule.get("dueDate"), "2026-08-22");
    assert.equal(schedule.get("revision"), 1);
    assert.equal(schedule.get("reminderTime"), undefined);
    assert.equal(schedule.get("enabled"), undefined);
    assert.equal(schedule.get("notificationCandidateActive"), false);
    assert.equal(schedule.get("nextNotificationAt"), undefined);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("atomic validation failure preserves old schedule and the same request safely retries", async () => {
  const app = initializeApp({ projectId }, "watering-emulator-retry");
  const firestore = getFirestore(app);
  const store = new FirestoreWateringCompletionStore(firestore);

  try {
    await clear(firestore);
    await seed(firestore, 10, "PRIVATE");

    await assert.rejects(
      () => executeWateringCompletion({ uid: "user-a" }, request, store, now),
      (error: unknown) => error instanceof WateringError && error.code === "failed-precondition",
    );
    await firestore.doc("plantContents/species-a").update({
      publicationState: "PUBLIC",
      wateringIntervalDays: FieldValue.delete(),
    });
    await assert.rejects(
      () => executeWateringCompletion({ uid: "user-a" }, request, store, now),
      (error: unknown) => error instanceof WateringError && error.code === "failed-precondition",
    );
    let plant = await firestore.doc("users/user-a/personalPlants/plant-a").get();
    let schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(plant.get("lastWateredDate"), "2026-08-01");
    assert.equal(plant.get("revision"), 4);
    assert.equal(schedule.get("dueDate"), "2026-08-11");
    assert.equal(schedule.get("revision"), 2);
    assert.equal((await firestore.collection("users/user-a/wateringRecords").get()).size, 0);
    assert.equal((await firestore.collection("users/user-a/operations").get()).size, 0);

    await firestore.doc("plantContents/species-a").update({ wateringIntervalDays: 10 });
    assert.deepEqual(
      await executeWateringCompletion({ uid: "user-a" }, request, store, now),
      { kind: "applied", revision: 5 },
    );
    plant = await firestore.doc("users/user-a/personalPlants/plant-a").get();
    schedule = await firestore.doc("users/user-a/wateringSchedules/plant-a").get();
    assert.equal(plant.get("lastWateredDate"), "2026-08-12");
    assert.equal(schedule.get("dueDate"), "2026-08-22");
    assert.equal((await firestore.collection("users/user-a/wateringRecords").get()).size, 1);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

async function clear(firestore: ReturnType<typeof getFirestore>): Promise<void> {
  await Promise.all(paths.map((path) => firestore.doc(path).delete()));
}

async function seed(
  firestore: ReturnType<typeof getFirestore>,
  interval: number | undefined,
  publicationState: "PUBLIC" | "PRIVATE",
  withSchedule = true,
): Promise<void> {
  await firestore.doc("users/user-a").set({ zoneId: "Asia/Seoul" });
  await firestore.doc("plantContents/species-a").set({
    publicationState,
    ...(interval === undefined ? {} : { wateringIntervalDays: interval }),
  });
  await firestore.doc("users/user-a/personalPlants/plant-a").set({
    ownerUid: "user-a",
    displayName: "몬스테라",
    contentId: "species-a",
    registrationMethod: "IDENTIFIED",
    lastWateredDate: "2026-08-01",
    revision: 4,
    expectedRevision: 3,
    idempotencyKey: "previous-plant-operation",
    updatedAt: Timestamp.fromDate(new Date("2026-08-01T00:00:00Z")),
  });
  if (withSchedule) {
    await firestore.doc("users/user-a/notificationSettings/watering").set({
      ownerUid: "user-a",
      wateringEnabled: true,
      defaultTime: "08:30",
      zoneId: "Asia/Seoul",
      revision: 1,
    });
    await firestore.doc("users/user-a/notificationPlantSettings/plant-a").set({
      ownerUid: "user-a",
      plantId: "plant-a",
      enabled: true,
      timeOverride: "09:00",
      revision: 1,
    });
    await firestore.doc("users/user-a/wateringSchedules/plant-a").set({
      ownerUid: "user-a",
      plantId: "plant-a",
      dueDate: "2026-08-11",
      reminderTime: "09:00",
      enabled: true,
      zoneId: "Asia/Seoul",
      revision: 2,
      expectedRevision: 1,
      idempotencyKey: "previous-schedule-operation",
      updatedAt: Timestamp.fromDate(new Date("2026-08-01T00:00:00Z")),
    });
  }
}
