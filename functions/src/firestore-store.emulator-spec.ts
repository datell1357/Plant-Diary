import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { ContractError, executeOwnerMutation, executeServerStateWrite } from "./contracts.js";
import { FirestoreMutationStore } from "./firestore-store.js";

const projectId = "demo-planterior";

test("real Firestore emulator preserves revision idempotency ownership and server timestamps", async () => {
  assert.equal(process.env.GCLOUD_PROJECT, projectId);
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST);
  assert.equal(process.env.GOOGLE_APPLICATION_CREDENTIALS, undefined);

  const app = initializeApp({ projectId }, "functions-contract-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreMutationStore(firestore);
  const plantPath = "users/user-a/personalPlants/emulator-plant";
  const operationOnePath = "users/user-a/operations/operation-emulator-0001";
  const operationTwoPath = "users/user-a/operations/operation-emulator-0002";
  const operationThreePath = "users/user-a/operations/operation-emulator-0003";
  const riskPath = "users/user-a/weatherRisks/emulator-risk";
  const cleanup = [plantPath, operationOnePath, operationTwoPath, operationThreePath, riskPath];

  try {
    await Promise.all(cleanup.map((path) => firestore.doc(path).delete()));
    const mutation = {
      expectedOwnerUid: "user-a",
      collection: "personalPlants",
      documentId: "emulator-plant",
      mutationType: "CREATE",
      expectedRevision: 0,
      idempotencyKey: "operation-emulator-0001",
      payload: { displayName: "몬스테라", registrationMethod: "MANUAL" },
    };
    assert.deepEqual(await executeOwnerMutation({ uid: "user-a" }, mutation, store), { kind: "applied", revision: 1 });
    assert.deepEqual(await executeOwnerMutation({ uid: "user-a" }, mutation, store), { kind: "duplicate", revision: 1 });
    await assert.rejects(
      () => executeOwnerMutation({ uid: "user-a" }, { ...mutation, payload: { ...mutation.payload, displayName: "변경" } }, store),
      ContractError,
    );
    assert.deepEqual(
      await executeOwnerMutation({ uid: "user-a" }, { ...mutation, expectedRevision: 0, idempotencyKey: "operation-emulator-0002" }, store),
      { kind: "conflict", actualRevision: 1 },
    );
    await firestore.doc(plantPath).update({ contentId: "unpublished-content" });
    assert.deepEqual(
      await executeOwnerMutation(
        { uid: "user-a" },
        {
          ...mutation,
          mutationType: "UPDATE",
          expectedRevision: 1,
          idempotencyKey: "operation-emulator-0003",
          payload: { location: "거실", note: null },
        },
        store,
      ),
      { kind: "applied", revision: 2 },
    );
    const plant = await firestore.doc(plantPath).get();
    assert.equal(plant.get("ownerUid"), "user-a");
    assert.equal(plant.get("revision"), 2);
    assert.equal(plant.get("displayName"), "몬스테라");
    assert.equal(plant.get("registrationMethod"), "MANUAL");
    assert.equal(plant.get("contentId"), "unpublished-content");
    assert.equal(plant.get("location"), "거실");
    assert.equal(plant.get("note"), null);
    assert.ok(plant.get("updatedAt") instanceof Timestamp);

    await executeServerStateWrite(
      { trusted: true },
      "user-a",
      {
        collection: "weatherRisks",
        documentId: "emulator-risk",
        payload: {
          plantId: "emulator-plant",
          plantName: "몬스테라",
          snapshotId: "snapshot-a",
          type: "DRY",
          reason: "적정 습도보다 낮아요.",
          action: null,
          detectedAt: "2026-08-12T00:00:00Z",
          observedAt: "2026-08-12T00:00:00Z",
          active: true,
          transition: 1,
          deliveredTransition: null,
        },
      },
      store,
    );
    const risk = await firestore.doc(riskPath).get();
    assert.equal(risk.get("ownerUid"), "user-a");
    assert.ok(risk.get("detectedAt") instanceof Timestamp);
    assert.ok(risk.get("updatedAt") instanceof Timestamp);
  } finally {
    await Promise.all(cleanup.map((path) => firestore.doc(path).delete()));
    await deleteApp(app);
  }
});

test("registration and manual last-watered edits create and reschedule the authoritative watering schedule", async () => {
  const app = initializeApp({ projectId }, "functions-schedule-mutation-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreMutationStore(firestore);
  const paths = [
    "users/user-a",
    "users/user-a/personalPlants/scheduled-plant",
    "users/user-a/wateringSchedules/scheduled-plant",
    "users/user-a/notificationSettings/watering",
    "users/user-a/notificationPlantSettings/scheduled-plant",
    "users/user-a/operations/schedule-create-0001",
    "users/user-a/operations/schedule-update-0002",
    "plantContents/species-scheduled",
  ];

  try {
    await Promise.all(paths.map((path) => firestore.doc(path).delete()));
    await firestore.doc("users/user-a").set({ ownerUid: "user-a", zoneId: "Asia/Seoul" });
    await firestore.doc("plantContents/species-scheduled").set({
      publicationState: "PUBLIC",
      wateringIntervalDays: 10,
    });
    await firestore.doc("users/user-a/notificationSettings/watering").set({
      ownerUid: "user-a",
      wateringEnabled: true,
      defaultTime: "09:00",
      zoneId: "Asia/Seoul",
      revision: 1,
    });
    await firestore.doc("users/user-a/notificationPlantSettings/scheduled-plant").set({
      ownerUid: "user-a",
      plantId: "scheduled-plant",
      enabled: true,
      timeOverride: null,
      revision: 1,
    });
    const create = {
      expectedOwnerUid: "user-a",
      collection: "personalPlants",
      documentId: "scheduled-plant",
      mutationType: "CREATE",
      expectedRevision: 0,
      idempotencyKey: "schedule-create-0001",
      payload: {
        displayName: "몬스테라",
        registrationMethod: "MANUAL",
        contentId: "species-scheduled",
        lastWateredDate: "2026-08-01",
      },
    };

    await executeOwnerMutation({ uid: "user-a" }, create, store);
    let schedule = await firestore.doc("users/user-a/wateringSchedules/scheduled-plant").get();
    assert.equal(schedule.get("dueDate"), "2026-08-11");
    assert.equal(schedule.get("ownerUid"), "user-a");
    assert.equal(schedule.get("notificationCandidateActive"), true);
    assert.ok(schedule.get("nextNotificationAt") instanceof Timestamp);

    await executeOwnerMutation(
      { uid: "user-a" },
      {
        ...create,
        mutationType: "UPDATE",
        expectedRevision: 1,
        idempotencyKey: "schedule-update-0002",
        payload: { lastWateredDate: "2026-08-05" },
      },
      store,
    );
    schedule = await firestore.doc("users/user-a/wateringSchedules/scheduled-plant").get();
    assert.equal(schedule.get("dueDate"), "2026-08-15");
    assert.equal(schedule.get("revision"), 2);
  } finally {
    await Promise.all(paths.map((path) => firestore.doc(path).delete()));
    await deleteApp(app);
  }
});

test("server-backed account limit accepts 200 plants and rejects the 201st", async () => {
  const app = initializeApp({ projectId }, "personal-plant-limit-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreMutationStore(firestore);
  const plants = firestore.collection("users/limit-user/personalPlants");

  try {
    const batch = firestore.batch();
    for (let index = 0; index < 200; index += 1) {
      batch.set(plants.doc(`plant-${index}`), {
        ownerUid: "limit-user",
        displayName: `Plant ${index}`,
        registrationMethod: "MANUAL",
        revision: 1,
      });
    }
    await batch.commit();

    await assert.rejects(
      () => executeOwnerMutation(
        { uid: "limit-user" },
        {
          expectedOwnerUid: "limit-user",
          collection: "personalPlants",
          documentId: "plant-200",
          mutationType: "CREATE",
          expectedRevision: 0,
          idempotencyKey: "operation-limit-0201",
          payload: { displayName: "One too many", registrationMethod: "MANUAL" },
        },
        store,
      ),
      (error: unknown) =>
        error instanceof ContractError && error.code === "resource-exhausted",
    );
    assert.equal((await plants.get()).size, 200);
  } finally {
    const documents = await plants.get();
    await Promise.all(documents.docs.map((document) => document.ref.delete()));
    const operations = await firestore.collection("users/limit-user/operations").get();
    await Promise.all(operations.docs.map((document) => document.ref.delete()));
    await deleteApp(app);
  }
});
