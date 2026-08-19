const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { assertFails, assertSucceeds, initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const { Timestamp, collection, doc, getDoc, getDocs, query, setDoc, where } = require("firebase/firestore");
const { after, before, beforeEach, describe, it } = require("mocha");

const projectId = "demo-planterior";
const write = (ownerUid) => ({
  ownerUid,
  revision: 1,
  expectedRevision: 0,
  idempotencyKey: "operation-0001",
  updatedAt: "2026-08-12T00:00:00Z",
});
const ts = (value) => Timestamp.fromDate(new Date(value));
let env;

describe("strict server-derived Firebase contract", () => {
  it("keeps the public risk content query composite index in valid JSON", () => {
    const indexes = JSON.parse(fs.readFileSync(path.resolve(__dirname, "../../firestore.indexes.json"), "utf8"));
    const riskIndex = indexes.indexes.find((index) => index.collectionGroup === "riskContents");
    assert.deepEqual(riskIndex, {
      collectionGroup: "riskContents",
      queryScope: "COLLECTION",
      fields: [
        { fieldPath: "publicationState", order: "ASCENDING" },
        { fieldPath: "plantContentId", order: "ASCENDING" },
      ],
    });
  });

  it("declares the bounded expired delivery-claim recovery index", () => {
    const indexes = JSON.parse(
      fs.readFileSync(path.resolve(__dirname, "../../firestore.indexes.json"), "utf8"),
    );
    const recoveryIndex = indexes.indexes.find(
      (index) => index.collectionGroup === "notificationDeliveryClaims",
    );
    assert.deepEqual(recoveryIndex, {
      collectionGroup: "notificationDeliveryClaims",
      queryScope: "COLLECTION",
      fields: [
        { fieldPath: "state", order: "ASCENDING" },
        { fieldPath: "expiresAt", order: "ASCENDING" },
        { fieldPath: "__name__", order: "ASCENDING" },
      ],
    });
  });

  it("declares the bounded watering due-candidate composite index", () => {
    const indexes = JSON.parse(
      fs.readFileSync(path.resolve(__dirname, "../../firestore.indexes.json"), "utf8"),
    );
    const wateringIndex = indexes.indexes.find(
      (index) => index.collectionGroup === "wateringSchedules"
        && index.fields.some((field) => field.fieldPath === "nextNotificationAt"),
    );
    assert.deepEqual(wateringIndex, {
      collectionGroup: "wateringSchedules",
      queryScope: "COLLECTION_GROUP",
      fields: [
        { fieldPath: "notificationCandidateActive", order: "ASCENDING" },
        { fieldPath: "nextNotificationAt", order: "ASCENDING" },
        { fieldPath: "__name__", order: "ASCENDING" },
      ],
    });
  });

  before(async () => {
    env = await initializeTestEnvironment({
      projectId,
      firestore: { rules: fs.readFileSync(path.resolve(__dirname, "../../firestore.rules"), "utf8") },
    });
  });
  beforeEach(async () => env.clearFirestore());
  after(async () => { if (env) await env.cleanup(); });

  it("keeps delivery claims and recovery cursors isolated from clients", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    await env.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "notificationDeliveryClaims/claim-a"), {
        ownerUid: "user-a",
        state: "CLAIMED",
        expiresAt: ts("2026-08-12T00:10:00Z"),
      });
      await setDoc(doc(admin.firestore(), "notificationRuntime/wateringClaimRecovery"), {
        documentPath: "notificationDeliveryClaims/claim-a",
      });
    });
    await assertFails(getDoc(doc(owner, "notificationDeliveryClaims/claim-a")));
    await assertFails(getDocs(query(
      collection(owner, "notificationDeliveryClaims"),
      where("ownerUid", "==", "user-a"),
      where("claimId", "==", "claim-a"),
    )));
    await assertFails(setDoc(doc(owner, "notificationDeliveryClaims/claim-b"), {
      ownerUid: "user-a",
      state: "CLAIMED",
      expiresAt: ts("2026-08-12T00:10:00Z"),
    }));
    await assertFails(getDoc(doc(owner, "notificationRuntime/wateringClaimRecovery")));
    await assertFails(setDoc(doc(owner, "notificationRuntime/wateringClaimRecovery"), {
      documentPath: "notificationDeliveryClaims/claim-b",
    }));
  });

  it("denies owner-forged outcomes and permits only valid trusted-server state", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const fixtures = {
      notificationDeliveries: { ...write("user-a"), plantId: "plant-a", dueDate: "2026-08-12", attempt: 0, status: "SENT", scheduledFor: ts("2026-08-12T00:00:00Z"), deliveredAt: ts("2026-08-12T00:01:00Z"), deduplicationKey: "user-a:plant-a:2026-08-12:0" },
      weatherRisks: { ...write("user-a"), plantId: "plant-a", snapshotId: "snapshot-a", type: "DRY", action: "mist", detectedAt: ts("2026-08-12T00:00:00Z"), active: true },
      deletionRequests: { ...write("user-a"), status: "COMPLETED", requestedAt: ts("2026-08-01T00:00:00Z"), scheduledFor: ts("2026-08-08T00:00:00Z"), completedAt: ts("2026-08-08T00:01:00Z") },
      ownedItems: { ...write("user-a"), itemId: "item-a", acquiredAt: ts("2026-08-12T00:00:00Z"), applied: true },
    };
    for (const [collection, fixture] of Object.entries(fixtures)) {
      const relative = `users/user-a/${collection}/fixture`;
      await assertFails(setDoc(doc(owner, relative), fixture));
      await assertSucceeds(setDoc(doc(server, relative), fixture));
      await assertSucceeds(getDoc(doc(owner, relative)));
    }
  });

  it("rejects unknown states impossible dates and malformed temporal ordering", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    await assertFails(setDoc(doc(owner, "users/user-a/wateringRecords/feb-31"), { ...write("user-a"), plantId: "plant-a", wateredDate: "2026-02-31", recordedAt: ts("2026-08-12T00:00:00Z") }));
    await assertFails(setDoc(doc(owner, "users/user-a/wateringSchedules/april-31"), { ...write("user-a"), plantId: "plant-a", dueDate: "2026-04-31", reminderTime: "09:00", zoneId: "Asia/Seoul", enabled: true }));
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/state"), { ...write("user-a"), displayName: "plant", registrationMethod: "FORGED" }));
    await assertFails(setDoc(doc(owner, "users/user-a/consents/state"), { ...write("user-a"), type: "EVERYTHING", granted: true, recordedAt: ts("2026-08-12T00:00:00Z") }));
    await assertFails(setDoc(doc(server, "users/user-a/weatherRisks/state"), { ...write("user-a"), plantId: "plant-a", snapshotId: "snapshot-a", type: "UNKNOWN", action: null, detectedAt: ts("2026-08-12T00:00:00Z"), active: true }));
    await assertFails(setDoc(doc(server, "users/user-a/notificationDeliveries/state"), { ...write("user-a"), plantId: "plant-a", dueDate: "2026-08-12", attempt: 0, status: "DELIVEREDISH", scheduledFor: ts("2026-08-12T00:00:00Z"), deliveredAt: null, deduplicationKey: "user-a:plant-a:2026-08-12:0" }));
    await assertFails(setDoc(doc(server, "users/user-a/deletionRequests/order"), { ...write("user-a"), status: "COMPLETED", requestedAt: ts("2026-08-10T00:00:00Z"), scheduledFor: ts("2026-08-09T00:00:00Z"), completedAt: ts("2026-08-08T00:00:00Z") }));
    await assertFails(setDoc(doc(server, "users/user-a/weatherRisks/extra"), { ...write("user-a"), plantId: "plant-a", snapshotId: "snapshot-a", type: "DRY", action: null, detectedAt: ts("2026-08-12T00:00:00Z"), active: true, privileged: true }));
  });

  it("reads a Timestamp-shaped public share before expiry and denies it after expiry", async () => {
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const anonymous = env.unauthenticatedContext().firestore();
    const shape = (expiresAt) => ({ publicationState: "PUBLIC", sourceRevision: 1, snapshotPath: "share-images/user-a/share-a/share.png", expiresAt, revokedAt: null });
    await assertSucceeds(setDoc(doc(server, "publicShares/future"), shape(ts("2099-01-01T00:00:00Z"))));
    await assertSucceeds(getDoc(doc(anonymous, "publicShares/future")));
    await assertSucceeds(setDoc(doc(server, "publicShares/expired"), shape(ts("2020-01-01T00:00:00Z"))));
    await assertFails(getDoc(doc(anonymous, "publicShares/expired")));
    await assertFails(setDoc(doc(server, "publicShares/string"), shape("2099-01-01T00:00:00Z")));
  });
});
