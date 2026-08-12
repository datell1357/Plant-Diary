import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { executeOwnerMutation, executeServerStateWrite } from "./contracts.js";
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
  const riskPath = "users/user-a/weatherRisks/emulator-risk";
  const cleanup = [plantPath, operationOnePath, operationTwoPath, riskPath];

  try {
    await Promise.all(cleanup.map((path) => firestore.doc(path).delete()));
    const mutation = {
      collection: "personalPlants",
      documentId: "emulator-plant",
      expectedRevision: 0,
      idempotencyKey: "operation-emulator-0001",
      payload: { displayName: "몬스테라", registrationMethod: "MANUAL" },
    };
    assert.deepEqual(await executeOwnerMutation({ uid: "user-a" }, mutation, store), { kind: "applied", revision: 1 });
    assert.deepEqual(await executeOwnerMutation({ uid: "user-a" }, mutation, store), { kind: "duplicate", revision: 1 });
    assert.deepEqual(
      await executeOwnerMutation({ uid: "user-a" }, { ...mutation, expectedRevision: 0, idempotencyKey: "operation-emulator-0002" }, store),
      { kind: "conflict", actualRevision: 1 },
    );
    const plant = await firestore.doc(plantPath).get();
    assert.equal(plant.get("ownerUid"), "user-a");
    assert.equal(plant.get("revision"), 1);
    assert.ok(plant.get("updatedAt") instanceof Timestamp);

    await executeServerStateWrite(
      { trusted: true },
      "user-a",
      {
        collection: "weatherRisks",
        documentId: "emulator-risk",
        payload: {
          plantId: "emulator-plant",
          snapshotId: "snapshot-a",
          type: "DRY",
          action: null,
          detectedAt: "2026-08-12T00:00:00Z",
          active: true,
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
