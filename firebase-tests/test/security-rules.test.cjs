const fs = require("node:fs");
const path = require("node:path");
const { assertFails, assertSucceeds, initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const { Timestamp, collection, deleteDoc, doc, getDoc, getDocs, query, setDoc, updateDoc, where } = require("firebase/firestore");
const { ref, uploadBytes, getBytes } = require("firebase/storage");
const { before, after, beforeEach, describe, it } = require("mocha");

const projectId = "demo-planterior";
let env;
const write = (ownerUid, revision = 1, expectedRevision = 0, idempotencyKey = "op-valid-0001") => ({ ownerUid, revision, expectedRevision, idempotencyKey, updatedAt: "2026-08-12T00:00:00Z" });
const ts = (value) => Timestamp.fromDate(new Date(value));
const collectionFixture = (collectionName) => {
  if (collectionName === "personalPlants") return { displayName: "몬스테라", registrationMethod: "MANUAL" };
  if (collectionName === "wateringSchedules") return { plantId: "plant-a", dueDate: "2026-08-12", reminderTime: "09:00", zoneId: "Asia/Seoul", enabled: true };
  if (collectionName === "wateringRecords") return { plantId: "plant-a", wateredDate: "2026-08-12", recordedAt: ts("2026-08-12T00:00:00Z") };
  if (collectionName === "notificationSettings") return { wateringEnabled: true, weatherEnabled: true, defaultTime: "09:00", zoneId: "Asia/Seoul" };
  if (collectionName === "weatherSnapshots") return { regionCode: "11B10101", temperatureCelsius: 27, humidityPercent: 55, precipitationMillimeters: 0, observedAt: ts("2026-08-12T00:00:00Z"), expiresAt: ts("2026-08-12T01:00:00Z") };
  if (collectionName === "weatherRisks") return { plantId: "plant-a", snapshotId: "snapshot-a", type: "DRY", detectedAt: ts("2026-08-12T00:00:00Z"), active: true };
  if (collectionName === "miniHomes") return { name: "우리 집" };
  if (collectionName === "placements") return { normalizedX: 0.5, normalizedY: 0.5, zIndex: 1 };
  if (collectionName === "ownedItems") return { itemId: "item-a", acquiredAt: ts("2026-08-12T00:00:00Z"), applied: false };
  if (collectionName === "shareLinks") return { miniHomeId: "home-a", sourceRevision: 1, snapshotPath: "share-images/user-a/share-a/share.png", createdAt: ts("2026-08-12T00:00:00Z"), expiresAt: ts("2026-09-12T00:00:00Z"), revokedAt: null };
  if (collectionName === "consents") return { type: "LOCATION", granted: true, recordedAt: ts("2026-08-12T00:00:00Z") };
  if (collectionName === "deletionRequests") return { status: "RECEIVED", requestedAt: ts("2026-08-12T00:00:00Z"), scheduledFor: ts("2026-08-19T00:00:00Z"), completedAt: null };
  if (collectionName === "notificationDeliveries") return { status: "PENDING", scheduledFor: ts("2026-08-12T00:00:00Z"), deliveredAt: null, deduplicationKey: "delivery-0001" };
  if (collectionName === "identificationRequests") return { temporaryOriginalPath: "identification-originals/user-a/request-a/original.webp", createdAt: ts("2026-08-12T00:00:00Z"), expiresAt: ts("2026-08-13T00:00:00Z") };
  throw new Error(`Missing fixture for ${collectionName}`);
};
const serverCollections = new Set(["weatherSnapshots", "weatherRisks", "ownedItems", "shareLinks", "deletionRequests", "notificationDeliveries"]);

describe("Planterior Firebase ownership contract", () => {
  before(async () => {
    env = await initializeTestEnvironment({
      projectId,
      firestore: { rules: fs.readFileSync(path.resolve(__dirname, "../../firestore.rules"), "utf8") },
      storage: { rules: fs.readFileSync(path.resolve(__dirname, "../../storage.rules"), "utf8") }
    });
  });
  beforeEach(async () => env.clearFirestore());
  after(async () => { if (env) await env.cleanup(); });

  it("allows owner CRUD and denies unauthenticated and foreign users without leaking data", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const foreign = env.authenticatedContext("user-b").firestore();
    const anonymous = env.unauthenticatedContext().firestore();
    const userRoot = doc(owner, "users/user-a");
    await assertSucceeds(setDoc(userRoot, { ...write("user-a"), zoneId: "Asia/Seoul" }));
    await assertSucceeds(getDoc(userRoot));
    await assertFails(getDoc(doc(foreign, "users/user-a")));
    await assertFails(getDoc(doc(anonymous, "users/user-a")));
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/root-spoof"), write("user-b")));
    const target = doc(owner, "users/user-a/personalPlants/plant-a");
    await assertFails(setDoc(target, { ...write("user-a"), ...collectionFixture("personalPlants") }));
    await env.withSecurityRulesDisabled(async (admin) =>
      setDoc(doc(admin.firestore(), "users/user-a/personalPlants/plant-a"), { ...write("user-a"), ...collectionFixture("personalPlants") })
    );
    await assertSucceeds(getDoc(target));
    await assertFails(deleteDoc(target));
    await assertFails(getDoc(doc(foreign, "users/user-a/personalPlants/plant-a")));
    await assertFails(updateDoc(doc(foreign, "users/user-a/personalPlants/plant-a"), { displayName: "탈취" }));
    await assertFails(getDoc(doc(anonymous, "users/user-a/personalPlants/plant-a")));
  });

  it("enforces owner isolation for every approved user subcollection", async () => {
    const collections = [
      "personalPlants", "wateringRecords", "wateringSchedules", "notificationSettings",
      "weatherSnapshots", "weatherRisks", "miniHomes", "placements", "ownedItems",
      "shareLinks", "consents", "deletionRequests", "notificationDeliveries", "identificationRequests"
    ];
    const owner = env.authenticatedContext("user-a").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const foreign = env.authenticatedContext("user-b").firestore();
    const anonymous = env.unauthenticatedContext().firestore();
    for (const collectionName of collections) {
      const path = `users/user-a/${collectionName}/fixture`;
      const writer = serverCollections.has(collectionName) ? server : owner;
      if (collectionName === "personalPlants") {
        await assertFails(setDoc(doc(writer, path), { ...write("user-a"), ...collectionFixture(collectionName) }));
        await env.withSecurityRulesDisabled(async (admin) =>
          setDoc(doc(admin.firestore(), path), { ...write("user-a"), ...collectionFixture(collectionName) })
        );
      } else {
        await assertSucceeds(setDoc(doc(writer, path), { ...write("user-a"), ...collectionFixture(collectionName) }));
      }
      await assertSucceeds(getDoc(doc(owner, path)));
      await assertFails(getDoc(doc(foreign, path)));
      await assertFails(setDoc(doc(foreign, path), { ...write("user-b"), ...collectionFixture(collectionName) }));
      await assertFails(getDoc(doc(anonymous, path)));
    }
  });

  it("makes idempotency operation documents create-only", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const operation = doc(server, "users/user-a/operations/op-stable-0001");
    await assertFails(setDoc(doc(owner, "users/user-a/operations/op-owner-forged"), write("user-a", 1, 0, "op-owner-forged")));
    await assertSucceeds(setDoc(operation, { ...write("user-a", 1, 0, "op-stable-0001"), documentPath: "users/user-a/personalPlants/plant-a", requestHash: "a".repeat(64), updatedAt: ts("2026-08-12T00:00:00Z") }));
    await assertFails(setDoc(operation, write("user-a", 1, 0, "op-stable-0001")));
    await assertFails(setDoc(doc(server, "users/user-a/operations/path-mismatch"), write("user-a", 1, 0, "other-operation")));
  });

  it("rejects owner spoofing malformed revisions and unstable operation IDs", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/spoof"), write("user-b")));
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/bad-revision"), write("user-a", -1)));
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/bad-operation"), write("user-a", 1, 0, "../foreign")));
    await assertFails(setDoc(doc(owner, "users/user-a/wateringSchedules/bad-date"), { ...write("user-a"), dueDate: "2026-99-99", reminderTime: "09:00", zoneId: "Asia/Seoul" }));
    await assertFails(setDoc(doc(owner, "users/user-a/wateringSchedules/bad-time"), { ...write("user-a"), dueDate: "2026-08-12", reminderTime: "29:00", zoneId: "Asia/Seoul" }));
    await assertFails(setDoc(doc(owner, "users/user-a/unknownCollection/unknown"), write("user-a")));
    await env.withSecurityRulesDisabled(async (admin) => setDoc(doc(admin.firestore(), "users/user-a/unknownCollection/server-only"), { secret: true }));
    await assertFails(getDoc(doc(owner, "users/user-a/unknownCollection/server-only")));
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/bad-instant"), { ...write("user-a"), updatedAt: "not-an-instant" }));
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/plant-a"), { ...write("user-a"), ...collectionFixture("personalPlants") }));
    await env.withSecurityRulesDisabled(async (admin) =>
      setDoc(doc(admin.firestore(), "users/user-a/personalPlants/plant-a"), { ...write("user-a"), ...collectionFixture("personalPlants") })
    );
    await assertFails(updateDoc(doc(owner, "users/user-a/personalPlants/plant-a"), { revision: 2, expectedRevision: 1, idempotencyKey: "op-valid-0002" }));
  });

  it("reads only public content and separates contentAdmin from opsAdmin", async () => {
    const contentAdmin = env.authenticatedContext("content-admin", { contentAdmin: true }).firestore();
    const opsAdmin = env.authenticatedContext("ops-admin", { opsAdmin: true }).firestore();
    const user = env.authenticatedContext("user-a").firestore();
    await assertSucceeds(setDoc(doc(contentAdmin, "plantContents/public"), { publicationState: "PUBLIC", revision: 1 }));
    await assertSucceeds(setDoc(doc(contentAdmin, "plantContents/private"), { publicationState: "DRAFT", revision: 1 }));
    await assertSucceeds(getDoc(doc(user, "plantContents/public")));
    await assertFails(getDoc(doc(user, "plantContents/private")));
    const anonymous = env.unauthenticatedContext().firestore();
    await assertSucceeds(getDoc(doc(anonymous, "plantContents/public")));
    await assertFails(getDoc(doc(anonymous, "plantContents/private")));
    await assertFails(getDoc(doc(opsAdmin, "plantContents/private")));
    await assertFails(setDoc(doc(opsAdmin, "plantContents/no-role-escalation"), { publicationState: "PUBLIC", revision: 1 }));
    await assertFails(setDoc(doc(user, "plantContents/user-write"), { publicationState: "PUBLIC", revision: 1 }));
    await assertFails(setDoc(doc(contentAdmin, "plantContents/malformed"), { publicationState: "UNREVIEWED", revision: -1 }));
    await assertSucceeds(getDocs(query(collection(user, "plantContents"), where("publicationState", "==", "PUBLIC"))));
    await assertFails(getDocs(query(collection(user, "plantContents"), where("publicationState", "==", "DRAFT"))));
    await assertFails(getDocs(collection(user, "plantContents")));
    await assertSucceeds(setDoc(doc(contentAdmin, "riskContents/public"), { publicationState: "PUBLIC", revision: 1 }));
    await assertFails(setDoc(doc(opsAdmin, "riskContents/ops-write"), { publicationState: "PUBLIC", revision: 1 }));
    await assertSucceeds(setDoc(doc(contentAdmin, "shopItems/public"), { publicationState: "PUBLIC", revision: 1 }));
    await assertFails(setDoc(doc(user, "shopItems/user-write"), { publicationState: "PUBLIC", revision: 1 }));
    await env.withSecurityRulesDisabled(async (admin) => setDoc(doc(admin.firestore(), "auditLogs/audit-1"), { action: "publish" }));
    await assertSucceeds(getDoc(doc(opsAdmin, "auditLogs/audit-1")));
    await assertFails(getDoc(doc(contentAdmin, "auditLogs/audit-1")));
    await assertFails(setDoc(doc(opsAdmin, "auditLogs/client-write"), { action: "spoof" }));
    const server = env.authenticatedContext("service", { server: true }).firestore();
    await assertSucceeds(setDoc(doc(server, "auditLogs/server-write"), { action: "sync" }));
  });

  it("binds all photo storage prefixes to auth uid and metadata owner", async () => {
    const owner = env.authenticatedContext("user-a").storage();
    const foreign = env.authenticatedContext("user-b").storage();
    const anonymous = env.unauthenticatedContext().storage();
    const target = ref(owner, "plant-photos/user-a/plant-a/photo.jpg");
    const bytes = new Uint8Array([1, 2, 3]);
    await assertSucceeds(uploadBytes(target, bytes, { contentType: "image/jpeg", customMetadata: { ownerUid: "user-a" } }));
    await assertSucceeds(getBytes(target));
    await assertFails(getBytes(ref(foreign, "plant-photos/user-a/plant-a/photo.jpg")));
    await assertFails(getBytes(ref(anonymous, "plant-photos/user-a/plant-a/photo.jpg")));
    await assertFails(uploadBytes(ref(owner, "plant-photos/user-b/plant-a/spoof.jpg"), bytes, { contentType: "image/jpeg", customMetadata: { ownerUid: "user-a" } }));
    await assertFails(uploadBytes(ref(owner, "identification-originals/user-a/request-a/bad.txt"), bytes, { contentType: "text/plain", customMetadata: { ownerUid: "user-a" } }));
    await assertFails(uploadBytes(ref(owner, "share-images/user-a/share-a/spoof.png"), bytes, { contentType: "image/png", customMetadata: { ownerUid: "user-b" } }));
    const original = ref(owner, "identification-originals/user-a/request-a/original.webp");
    const share = ref(owner, "share-images/user-a/share-a/share.png");
    await assertSucceeds(uploadBytes(original, bytes, { contentType: "image/webp", customMetadata: { ownerUid: "user-a" } }));
    await assertSucceeds(uploadBytes(share, bytes, { contentType: "image/png", customMetadata: { ownerUid: "user-a" } }));
    await assertFails(getBytes(ref(foreign, "identification-originals/user-a/request-a/original.webp")));
    await assertFails(getBytes(ref(foreign, "share-images/user-a/share-a/share.png")));
    await assertFails(uploadBytes(ref(owner, "plant-photos/user-a/../traversal.jpg"), bytes, { contentType: "image/jpeg", customMetadata: { ownerUid: "user-a" } }));
  });
});
