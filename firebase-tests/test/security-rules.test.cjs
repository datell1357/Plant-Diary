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
const catalogDigest = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";
const mediaPath = (itemId, digest = catalogDigest) => `catalog-assets/${itemId}/${digest}.webp`;
const collectionFixture = (collectionName) => {
  if (collectionName === "personalPlants") return { displayName: "몬스테라", registrationMethod: "MANUAL" };
  if (collectionName === "wateringSchedules") return { plantId: "plant-a", dueDate: "2026-08-12", zoneId: "Asia/Seoul", notificationCandidateActive: true, nextNotificationAt: ts("2026-08-12T00:00:00Z") };
  if (collectionName === "wateringRecords") return { plantId: "plant-a", wateredDate: "2026-08-12", recordedAt: ts("2026-08-12T00:00:00Z") };
  if (collectionName === "notificationSettings") return { wateringEnabled: true, weatherEnabled: true, defaultTime: "09:00", zoneId: "Asia/Seoul" };
  if (collectionName === "notificationPlantSettings") return { plantId: "plant-a", enabled: true, timeOverride: "09:00" };
  if (collectionName === "weatherSettings") return { globalAlertsEnabled: true };
  if (collectionName === "weatherPlantSettings") return { plantId: "plant-a", enabled: true };
  if (collectionName === "weatherSnapshots") return { regionCode: "11B10101", regionName: "서울", temperatureCelsius: 27, humidityPercent: 55, precipitationMillimeters: 0, observedAt: ts("2026-08-12T00:00:00Z"), expiresAt: ts("2026-08-12T03:00:00Z"), zoneId: "Asia/Seoul", stale: false, unavailablePlantIds: ["plant-a"] };
  if (collectionName === "weatherRisks") return { plantId: "plant-a", plantName: "몬스테라", snapshotId: "current", type: "DRY", reason: "건조해요", detectedAt: ts("2026-08-12T00:00:00Z"), observedAt: ts("2026-08-12T00:00:00Z"), active: true, transition: 1, deliveredTransition: null };
  if (collectionName === "miniHomes") {
    return {
      name: "우리 집",
      placedPlantCount: 1,
      placementCount: 1,
      placementIds: ["placement-a"],
      requestHash: "a".repeat(64),
    };
  }
  if (collectionName === "placements") return { miniHomeId: "home-a", layoutRevision: 1, plantId: "plant-a", itemId: null, normalizedX: 0.5, normalizedY: 0.5, zIndex: 0 };
  if (collectionName === "ownedItems") return { itemId: "item-a", acquiredAt: ts("2026-08-12T00:00:00Z"), applied: false };
  if (collectionName === "shareLinks") return { miniHomeId: "home-a", sourceRevision: 1, snapshotPath: "share-images/user-a/share-a/share.png", createdAt: ts("2026-08-12T00:00:00Z"), expiresAt: ts("2026-09-12T00:00:00Z"), revokedAt: null };
  if (collectionName === "consents") return { type: "LOCATION", granted: true, recordedAt: ts("2026-08-12T00:00:00Z") };
  if (collectionName === "deletionRequests") return { status: "RECEIVED", requestedAt: ts("2026-08-12T00:00:00Z"), scheduledFor: ts("2026-08-19T00:00:00Z"), completedAt: null };
  if (collectionName === "notificationDeliveries") return { plantId: "plant-a", dueDate: "2026-08-12", attempt: 0, status: "SENT", scheduledFor: ts("2026-08-12T00:00:00Z"), deliveredAt: ts("2026-08-12T00:00:00Z"), deduplicationKey: "user-a:plant-a:2026-08-12:0" };
  if (collectionName === "notificationHistory") return { plantId: "plant-a", dueDate: "2026-08-12", attempt: 0, status: "SENT", deliveryConfirmedAt: ts("2026-08-12T00:00:00Z"), destinationOpened: false, deduplicationKey: "user-a:plant-a:2026-08-12:0" };
  if (collectionName === "identificationRequests") return { temporaryOriginalPath: "identification-originals/user-a/request-a/original.webp", createdAt: ts("2026-08-12T00:00:00Z"), expiresAt: ts("2026-08-13T00:00:00Z") };
  throw new Error(`Missing fixture for ${collectionName}`);
};
const serverCollections = new Set(["wateringRecords", "wateringSchedules", "notificationSettings", "notificationPlantSettings", "weatherSettings", "weatherPlantSettings", "weatherSnapshots", "weatherRisks", "miniHomes", "placements", "ownedItems", "shareLinks", "deletionRequests", "notificationDeliveries", "notificationHistory"]);

describe("Planterior Firebase ownership contract", () => {
  before(async () => {
    env = await initializeTestEnvironment({
      projectId,
      firestore: { rules: fs.readFileSync(path.resolve(__dirname, "../../firestore.rules"), "utf8") },
      storage: { rules: fs.readFileSync(path.resolve(__dirname, "../../storage.rules"), "utf8") }
    });
  });
  beforeEach(async () => {
    await env.clearFirestore();
    await env.clearStorage();
  });
  after(async () => { if (env) await env.cleanup(); });

  it("allows owner CRUD and denies unauthenticated and foreign users without leaking data", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const foreign = env.authenticatedContext("user-b").firestore();
    const anonymous = env.unauthenticatedContext().firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const userRoot = doc(owner, "users/user-a");
    await assertFails(setDoc(userRoot, { ...write("user-a"), zoneId: "Asia/Seoul" }));
    await assertSucceeds(
      setDoc(doc(server, "users/user-a"), { ...write("user-a"), zoneId: "Asia/Seoul" }),
    );
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
      "personalPlants", "wateringRecords", "wateringSchedules", "notificationSettings", "notificationPlantSettings",
      "weatherSettings", "weatherPlantSettings", "weatherSnapshots", "weatherRisks", "miniHomes", "placements", "ownedItems",
      "shareLinks", "consents", "deletionRequests", "notificationDeliveries", "notificationHistory", "identificationRequests"
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

  it("requires the transactional mini-home boundary and validates layout documents", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const home = { ...write("user-a"), ...collectionFixture("miniHomes") };
    const placement = { ...write("user-a"), ...collectionFixture("placements") };
    await assertFails(setDoc(doc(owner, "users/user-a/miniHomes/home-a"), home));
    await assertFails(setDoc(doc(owner, "users/user-a/placements/placement-a"), placement));
    await assertSucceeds(setDoc(doc(server, "users/user-a/miniHomes/home-a"), home));
    await assertSucceeds(setDoc(doc(server, "users/user-a/placements/placement-a"), placement));
    await assertFails(setDoc(doc(server, "users/user-a/placements/both-targets"), {
      ...placement,
      plantId: "plant-a",
      itemId: "item-a",
    }));
    await assertFails(setDoc(doc(server, "users/user-a/placements/outside"), {
      ...placement,
      normalizedX: 1.1,
    }));
  });

  it("keeps the generation-protected location consent command server-owned", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const reference = doc(owner, "users/user-a/consents/location");
    const consent = {
      ...write("user-a"),
      type: "LOCATION",
      granted: true,
      commandGeneration: 1,
      recordedAt: ts("2026-08-12T00:00:00Z"),
    };

    await assertFails(setDoc(reference, consent));
    await assertFails(setDoc(reference, {
      ...consent,
      commandGeneration: Number.MAX_SAFE_INTEGER,
    }));
    await assertSucceeds(setDoc(doc(server, "users/user-a/consents/location"), consent));
    await assertSucceeds(setDoc(doc(server, "users/user-a/consents/location"), {
      ...consent,
      ...write("user-a", 2, 1, "weather-consent-legacy-recovery"),
      granted: false,
      legacyRecovery: true,
    }));
    await assertFails(updateDoc(reference, {
      granted: true,
      commandGeneration: Number.MAX_SAFE_INTEGER,
    }));
    await assertFails(updateDoc(reference, { granted: false }));
    await assertFails(deleteDoc(reference));
    await assertFails(setDoc(doc(owner, "users/user-a/consents/analytics"), {
      ...write("user-a"),
      type: "ANALYTICS",
      granted: false,
      recordedAt: ts("2026-08-12T00:00:00Z"),
      legacyRecovery: true,
    }));
    await assertSucceeds(getDoc(reference));
  });

  it("bounds authoritative unavailable weather criteria ids", async () => {
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const reference = doc(server, "users/user-a/weatherSnapshots/current");
    const valid = { ...write("user-a"), ...collectionFixture("weatherSnapshots") };

    await assertSucceeds(setDoc(reference, valid));
    await assertFails(setDoc(reference, { ...valid, revision: 2, expectedRevision: 1, unavailablePlantIds: "plant-a" }));
    await assertFails(setDoc(reference, {
      ...valid,
      revision: 2,
      expectedRevision: 1,
      unavailablePlantIds: Array.from({ length: 201 }, (_, index) => `plant-${index}`),
    }));
  });

  it("keeps weather alert delivery server-owned and owner-readable", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const foreign = env.authenticatedContext("user-b").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const alert = {
      ownerUid: "user-a", plantId: "plant-a", plantName: "몬스테라", riskId: "plant-a_DRY",
      riskType: "DRY", transition: 1, action: "분무해 주세요", status: "PENDING",
      createdAt: ts("2026-08-12T00:00:00Z"), updatedAt: ts("2026-08-12T00:00:00Z"),
    };
    await assertFails(setDoc(doc(owner, "users/user-a/weatherAlerts/alert-a"), alert));
    await assertSucceeds(setDoc(doc(server, "users/user-a/weatherAlerts/alert-a"), alert));
    await assertSucceeds(getDoc(doc(owner, "users/user-a/weatherAlerts/alert-a")));
    await assertFails(getDoc(doc(foreign, "users/user-a/weatherAlerts/alert-a")));
  });

  it("requires the transactional server boundary for watering records schedules and notification settings", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    await assertFails(
      setDoc(doc(owner, "users/user-a/wateringRecords/direct-record"), {
        ...write("user-a"),
        ...collectionFixture("wateringRecords"),
      }),
    );
    await assertFails(
      setDoc(doc(owner, "users/user-a/wateringSchedules/direct-schedule"), {
        ...write("user-a"),
        ...collectionFixture("wateringSchedules"),
      }),
    );
    await assertFails(
      setDoc(doc(owner, "users/user-a/notificationSettings/watering"), {
        ...write("user-a"),
        ...collectionFixture("notificationSettings"),
      }),
    );
    await assertFails(
      setDoc(doc(owner, "users/user-a/notificationPlantSettings/plant-a"), {
        ...write("user-a"),
        ...collectionFixture("notificationPlantSettings"),
      }),
    );
    await assertSucceeds(
      setDoc(doc(server, "users/user-a/wateringRecords/server-record"), {
        ...write("user-a"),
        ...collectionFixture("wateringRecords"),
      }),
    );
    await assertSucceeds(
      setDoc(doc(server, "users/user-a/wateringSchedules/server-schedule"), {
        ...write("user-a"),
        ...collectionFixture("wateringSchedules"),
      }),
    );
    await assertSucceeds(
      setDoc(doc(server, "users/user-a/wateringSchedules/server-schedule-without-notification"), {
        ...write("user-a"),
        plantId: "plant-a",
        dueDate: "2026-08-22",
        zoneId: "Asia/Seoul",
      }),
    );
    await assertSucceeds(
      setDoc(doc(server, "users/user-a/wateringSchedules/server-schedule-disabled"), {
        ...write("user-a"),
        plantId: "plant-a",
        dueDate: "2026-08-22",
        zoneId: "Asia/Seoul",
        notificationCandidateActive: false,
      }),
    );
    await assertSucceeds(
      setDoc(doc(server, "users/user-a/notificationSettings/watering"), {
        ...write("user-a"),
        ...collectionFixture("notificationSettings"),
      }),
    );
    await assertSucceeds(
      setDoc(doc(server, "users/user-a/notificationPlantSettings/plant-a"), {
        ...write("user-a"),
        ...collectionFixture("notificationPlantSettings"),
      }),
    );
  });

  it("makes generic watering and notification delivery receipts immutable and schema bound", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const operation = doc(server, "users/user-a/operations/op-stable-0001");
    await assertFails(setDoc(doc(owner, "users/user-a/operations/op-owner-forged"), write("user-a", 1, 0, "op-owner-forged")));
    await assertSucceeds(setDoc(operation, { ...write("user-a", 1, 0, "op-stable-0001"), documentPath: "users/user-a/personalPlants/plant-a", requestHash: "a".repeat(64), updatedAt: ts("2026-08-12T00:00:00Z") }));
    await assertFails(setDoc(operation, write("user-a", 1, 0, "op-stable-0001")));
    await assertFails(setDoc(doc(server, "users/user-a/operations/path-mismatch"), write("user-a", 1, 0, "other-operation")));

    const receipt = {
      ...write("user-a", 5, 4, "watering-operation-stable"),
      documentPath: "users/user-a/personalPlants/plant-a",
      requestHash: "b".repeat(64),
      wateredDate: "2026-08-12",
      dueDate: "2026-08-22",
      recordId: "watering-operation-stable",
      plantRevision: 5,
      scheduleRevision: 3,
      recordedAt: ts("2026-08-12T00:00:00Z"),
      zoneId: "Asia/Seoul",
    };
    const receiptRef = doc(server, "users/user-a/operations/watering-operation-stable");
    await assertSucceeds(setDoc(receiptRef, receipt));
    await assertSucceeds(getDoc(doc(owner, "users/user-a/operations/watering-operation-stable")));
    await assertFails(setDoc(receiptRef, { ...receipt, dueDate: "2026-08-23" }));
    await assertFails(setDoc(doc(server, "users/user-a/operations/watering-malformed"), {
      ...receipt,
      idempotencyKey: "watering-malformed",
      recordId: "different-record",
    }));

    const deliveryRef = doc(server, "users/user-a/notificationDeliveries/delivery-stable");
    const delivery = { ...write("user-a", 1, 0, "delivery-stable"), ...collectionFixture("notificationDeliveries") };
    await assertSucceeds(setDoc(deliveryRef, delivery));
    await assertFails(setDoc(deliveryRef, { ...delivery, status: "FAILED", deliveredAt: null }));
    await assertFails(deleteDoc(deliveryRef));

    const historyRef = doc(server, "users/user-a/notificationHistory/history-stable");
    const history = { ...write("user-a", 1, 0, "history-stable"), ...collectionFixture("notificationHistory") };
    await assertSucceeds(setDoc(historyRef, history));
    await assertSucceeds(getDoc(doc(owner, "users/user-a/notificationHistory/history-stable")));
    await assertFails(updateDoc(doc(owner, "users/user-a/notificationHistory/history-stable"), {
      destinationOpened: true,
      openedAt: ts("2026-08-12T00:01:00Z"),
    }));
    const other = env.authenticatedContext("user-b").firestore();
    await assertFails(getDoc(doc(other, "users/user-a/notificationHistory/history-stable")));
    await assertFails(setDoc(historyRef, {
      ...history,
      revision: 2,
      expectedRevision: 1,
      idempotencyKey: "history-stable-open",
      endpointResults: [{ endpointId: "secret" }],
    }));

    const ambiguousRef = doc(server, "users/user-a/notificationHistory/history-ambiguous");
    const ambiguous = {
      ...write("user-a", 1, 0, "history-ambiguous"),
      ...collectionFixture("notificationHistory"),
      status: "DELIVERED_AMBIGUOUS",
      ambiguousAt: ts("2026-08-12T00:00:30Z"),
    };
    await assertSucceeds(setDoc(ambiguousRef, ambiguous));
    const missingConfirmationRef = doc(server, "users/user-a/notificationHistory/history-ambiguous-missing-confirmation");
    const { deliveryConfirmedAt: _omitted, ...withoutDeliveryConfirmation } = ambiguous;
    await assertFails(setDoc(missingConfirmationRef, {
      ...withoutDeliveryConfirmation,
      idempotencyKey: "history-ambiguous-missing-confirmation",
    }));
    await assertFails(updateDoc(doc(owner, "users/user-a/notificationHistory/history-ambiguous"), {
      status: "SENT",
      destinationOpened: true,
      openedAt: ts("2026-08-12T00:01:00Z"),
    }));
    await assertSucceeds(setDoc(ambiguousRef, {
      ...ambiguous,
      status: "SENT",
      destinationOpened: true,
      openedAt: ts("2026-08-12T00:01:00Z"),
      revision: 2,
      expectedRevision: 1,
      idempotencyKey: "history-ambiguous-open",
    }));
  });

  it("rejects owner spoofing malformed revisions and unstable operation IDs", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/spoof"), write("user-b")));
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/bad-revision"), write("user-a", -1)));
    await assertFails(setDoc(doc(owner, "users/user-a/personalPlants/bad-operation"), write("user-a", 1, 0, "../foreign")));
    await assertFails(setDoc(doc(owner, "users/user-a/wateringSchedules/bad-date"), { ...write("user-a"), plantId: "plant-a", dueDate: "2026-99-99", zoneId: "Asia/Seoul" }));
    await assertFails(setDoc(doc(owner, "users/user-a/notificationPlantSettings/bad-time"), { ...write("user-a"), plantId: "plant-a", enabled: true, timeOverride: "29:00" }));
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
    await assertSucceeds(setDoc(doc(contentAdmin, "plantContents/public"), { publicationState: "PUBLIC", revision: 1, wateringIntervalDays: 10 }));
    await assertSucceeds(setDoc(doc(contentAdmin, "plantContents/private"), { publicationState: "DRAFT", revision: 1 }));
    await assertFails(setDoc(doc(contentAdmin, "plantContents/interval-zero"), { publicationState: "PUBLIC", revision: 1, wateringIntervalDays: 0 }));
    await assertFails(setDoc(doc(contentAdmin, "plantContents/interval-high"), { publicationState: "PUBLIC", revision: 1, wateringIntervalDays: 366 }));
    await assertFails(setDoc(doc(contentAdmin, "plantContents/interval-fraction"), { publicationState: "PUBLIC", revision: 1, wateringIntervalDays: 7.5 }));
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
    await assertSucceeds(setDoc(doc(contentAdmin, "riskContents/public"), { plantContentId: "species-a", symptom: "잎 처짐", possibleCause: "흙 마름", action: "흙을 확인한다", publicationState: "PUBLIC", revision: 1 }));
    await assertSucceeds(setDoc(doc(contentAdmin, "riskContents/private"), { plantContentId: "species-a", symptom: "비공개", possibleCause: "비공개 원인", action: "비공개 행동", publicationState: "PRIVATE", revision: 2 }));
    await assertSucceeds(getDocs(query(collection(user, "riskContents"), where("publicationState", "==", "PUBLIC"), where("plantContentId", "==", "species-a"))));
    await assertFails(getDoc(doc(user, "riskContents/private")));
    await assertFails(getDocs(query(collection(user, "riskContents"), where("plantContentId", "==", "species-a"))));
    await assertFails(setDoc(doc(opsAdmin, "riskContents/ops-write"), { publicationState: "PUBLIC", revision: 1 }));
    const shopItem = {
      name: "햇살 벽지",
      description: "방을 환하게 꾸며요.",
      category: "BACKGROUND",
      assetPath: mediaPath("public"),
      assetSha256: catalogDigest,
      assetContentType: "image/webp",
      assetByteSize: 3,
      assetWidth: 96,
      assetHeight: 64,
      assetMediaRevision: 1,
      acquisitionCondition: null,
      publicationState: "PUBLIC",
      revision: 1,
      updatedAt: ts("2026-08-12T00:00:00Z"),
    };
    await assertSucceeds(setDoc(doc(contentAdmin, "shopItems/public"), shopItem));
    await assertSucceeds(setDoc(doc(contentAdmin, "shopItems/action"), {
      ...shopItem,
      category: "DECORATION",
      assetPath: mediaPath("action", "b".repeat(64)),
      assetSha256: "b".repeat(64),
      acquisitionCondition: "registered-plant",
    }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/unknown-condition"), { ...shopItem, acquisitionCondition: "points-100" }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/wrong-namespace"), { ...shopItem, assetPath: "shop-items/wrong-namespace/preview.webp" }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/cross-item-asset"), { ...shopItem, assetPath: "catalog-assets/public/preview.webp" }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/wrong-extension"), { ...shopItem, assetPath: `catalog-assets/wrong-extension/${catalogDigest}.svg` }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/path-digest-mismatch"), {
      ...shopItem,
      assetPath: mediaPath("path-digest-mismatch", "b".repeat(64)),
      assetSha256: "a".repeat(64),
    }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/missing-media-contract"), {
      name: shopItem.name,
      description: shopItem.description,
      category: shopItem.category,
      assetPath: "catalog-assets/missing-media-contract/preview.webp",
      acquisitionCondition: null,
      publicationState: "PUBLIC",
      revision: 1,
      updatedAt: shopItem.updatedAt,
    }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/wrong-media-type"), {
      ...shopItem,
      assetPath: "catalog-assets/wrong-media-type/preview.webp",
      assetContentType: "image/png",
    }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/pixel-bomb"), {
      ...shopItem,
      assetPath: "catalog-assets/pixel-bomb/preview.webp",
      assetWidth: 32768,
      assetHeight: 32768,
    }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/extreme-aspect"), {
      ...shopItem,
      assetPath: "catalog-assets/extreme-aspect/preview.webp",
      assetWidth: 32768,
      assetHeight: 1,
    }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/monetized"), { ...shopItem, price: 100, currency: "POINTS" }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/public"), {
      ...shopItem,
      revision: 2,
      assetByteSize: 4,
    }));
    await assertSucceeds(setDoc(doc(contentAdmin, "shopItems/public"), {
      ...shopItem,
      revision: 2,
      assetPath: mediaPath("public", "c".repeat(64)),
      assetSha256: "c".repeat(64),
      assetMediaRevision: 2,
      updatedAt: ts("2026-08-12T00:00:01Z"),
    }));
    await assertFails(setDoc(doc(user, "shopItems/user-write"), shopItem));
    await assertFails(deleteDoc(doc(user, "shopItems/public")));
    await assertSucceeds(deleteDoc(doc(contentAdmin, "shopItems/public")));
    await env.withSecurityRulesDisabled(async (admin) => setDoc(doc(admin.firestore(), "auditLogs/audit-1"), { action: "publish" }));
    await assertSucceeds(getDoc(doc(opsAdmin, "auditLogs/audit-1")));
    await assertFails(getDoc(doc(contentAdmin, "auditLogs/audit-1")));
    await assertFails(setDoc(doc(opsAdmin, "auditLogs/client-write"), { action: "spoof" }));
    const server = env.authenticatedContext("service", { server: true }).firestore();
    await assertSucceeds(setDoc(doc(server, "auditLogs/server-write"), { action: "sync" }));
  });

  it("keeps authoritative inventory and Mini-home snapshot generations private and server-managed", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const foreign = env.authenticatedContext("user-b").firestore();
    const state = doc(owner, "users/user-a/inventoryStates/current");
    await env.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "users/user-a/inventoryStates/current"), {
        ownerUid: "user-a",
        generation: 1,
        snapshotHash: "a".repeat(64),
      });
    });
    await assertFails(getDoc(state));
    await assertFails(getDoc(doc(foreign, "users/user-a/inventoryStates/current")));
    await assertFails(setDoc(state, {
      ownerUid: "user-a",
      generation: 2,
      snapshotHash: "b".repeat(64),
    }));
    const snapshotState = doc(owner, "users/user-a/miniHomeSnapshotStates/current");
    await env.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "users/user-a/miniHomeSnapshotStates/current"), {
        ownerUid: "user-a",
        generation: 1,
        snapshotToken: "c".repeat(64),
      });
    });
    await assertFails(getDoc(snapshotState));
    await assertFails(getDoc(doc(foreign, "users/user-a/miniHomeSnapshotStates/current")));
    await assertFails(setDoc(snapshotState, {
      ownerUid: "user-a",
      generation: 2,
      snapshotToken: "d".repeat(64),
    }));
    for (const path of [
      "users/user-a/miniHomeProjectionPointers/current",
      `users/user-a/miniHomeProjections/1-${"a".repeat(64)}`,
      "catalogProjectionPointers/current",
      `catalogProjections/1-${"a".repeat(64)}`,
    ]) {
      await env.withSecurityRulesDisabled(async (admin) => {
        await setDoc(doc(admin.firestore(), path), { ownerUid: "user-a", schemaVersion: 1 });
      });
      await assertFails(getDoc(doc(owner, path)));
      await assertFails(getDoc(doc(foreign, path)));
      await assertFails(setDoc(doc(owner, path), { ownerUid: "user-a", schemaVersion: 1 }));
    }
  });

  it("allows bounded catalog asset reads only for public catalog or the owning account and denies every client write", async () => {
    const contentAdmin = env.authenticatedContext("content-admin", { contentAdmin: true }).firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const ownerStorage = env.authenticatedContext("user-a").storage();
    const foreignStorage = env.authenticatedContext("user-b").storage();
    const anonymousStorage = env.unauthenticatedContext().storage();
    const catalog = {
      name: "햇살 벽지",
      description: "방을 환하게 꾸며요.",
      category: "BACKGROUND",
      assetPath: mediaPath("public"),
      assetSha256: catalogDigest,
      assetContentType: "image/webp",
      assetByteSize: 3,
      assetWidth: 96,
      assetHeight: 64,
      assetMediaRevision: 1,
      acquisitionCondition: null,
      publicationState: "PUBLIC",
      revision: 1,
      updatedAt: ts("2026-08-12T00:00:00Z"),
    };
    await assertSucceeds(setDoc(doc(contentAdmin, "shopItems/public"), catalog));
    await assertSucceeds(setDoc(doc(contentAdmin, "shopItems/private"), {
      ...catalog,
      assetPath: mediaPath("private"),
      publicationState: "PRIVATE",
    }));
    await assertSucceeds(setDoc(doc(server, "users/user-a/ownedItems/private"), {
      ...write("user-a"),
      itemId: "private",
      acquiredAt: ts("2026-08-12T00:00:00Z"),
      applied: false,
      nameSnapshot: "이전 벽지",
      categorySnapshot: "BACKGROUND",
      assetPathSnapshot: mediaPath("private"),
      assetSha256Snapshot: catalogDigest,
      assetByteSizeSnapshot: 3,
      assetMimeTypeSnapshot: "image/webp",
      assetWidthSnapshot: 96,
      assetHeightSnapshot: 64,
      assetMediaRevisionSnapshot: 1,
      catalogRevisionSnapshot: 1,
    }));
    await env.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "shopItems/missing-bounds"), {
        ...catalog,
        assetPath: "catalog-assets/missing-bounds/preview.webp",
      });
      await setDoc(doc(admin.firestore(), "shopItems/extreme-bounds"), {
        ...catalog,
        assetPath: "catalog-assets/extreme-bounds/preview.webp",
        assetWidth: 32768,
        assetHeight: 1,
      });
      const bytes = new Uint8Array([1, 2, 3]);
      const metadata = {
        contentType: "image/webp",
        customMetadata: { width: "96", height: "64", sha256: catalogDigest, mediaRevision: "1" },
      };
      await uploadBytes(ref(admin.storage(), mediaPath("public")), bytes, metadata);
      await uploadBytes(ref(admin.storage(), mediaPath("private")), bytes, metadata);
      await uploadBytes(
        ref(admin.storage(), "catalog-assets/missing-bounds/preview.webp"),
        bytes,
        { contentType: "image/webp" },
      );
      await uploadBytes(
        ref(admin.storage(), "catalog-assets/extreme-bounds/preview.webp"),
        bytes,
        { contentType: "image/webp", customMetadata: { width: "32768", height: "1" } },
      );
    });

    await assertSucceeds(getBytes(ref(anonymousStorage, mediaPath("public"))));
    await assertSucceeds(getBytes(ref(ownerStorage, mediaPath("private"))));
    await assertFails(getBytes(ref(anonymousStorage, "catalog-assets/missing-bounds/preview.webp")));
    await assertFails(getBytes(ref(anonymousStorage, "catalog-assets/extreme-bounds/preview.webp")));
    await assertFails(getBytes(ref(foreignStorage, mediaPath("private"))));
    await assertFails(getBytes(ref(anonymousStorage, mediaPath("private"))));
    await assertFails(uploadBytes(
      ref(ownerStorage, "catalog-assets/public/client.webp"),
      new Uint8Array([1]),
      { contentType: "image/webp" },
    ));
    await assertFails(getBytes(ref(ownerStorage, "catalog-assets/public/nested/preview.webp")));
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
