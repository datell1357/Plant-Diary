const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
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

const repositoryRoot = path.resolve(__dirname, "../..");
const readRepositoryFile = (relativePath) =>
  fs.readFileSync(path.join(repositoryRoot, relativePath), "utf8");

describe("canonical QA gate contracts", () => {
  it("keeps every emulator client aligned with the canonical fixed ports", () => {
    const firebaseConfig = JSON.parse(readRepositoryFile("firebase.json"));
    const ports = Object.fromEntries(
      ["auth", "firestore", "functions", "storage"].map((service) => [
        service,
        firebaseConfig.emulators[service].port,
      ]),
    );
    assert.deepEqual(ports, {
      auth: 9099,
      firestore: 8180,
      functions: 5001,
      storage: 9199,
    });

    const clientReferences = {
      "app/src/main/kotlin/com/planterior/helper/auth/AuthRepositoryRuntime.kt": [
        `auth.useEmulator("10.0.2.2", ${ports.auth})`,
        `firestore.useEmulator("10.0.2.2", ${ports.firestore})`,
        `functions.useEmulator("10.0.2.2", ${ports.functions})`,
        `storage.useEmulator("10.0.2.2", ${ports.storage})`,
      ],
      "feature/auth/src/androidTest/kotlin/com/planterior/helper/feature/auth/AuthDebugHarnessTest.kt": [
        `auth.useEmulator("10.0.2.2", ${ports.auth})`,
        `firestore.useEmulator("10.0.2.2", ${ports.firestore})`,
        `functions.useEmulator("10.0.2.2", ${ports.functions})`,
      ],
      "firebase-tests/test/functions-smoke.cjs": [
        `connectAuthEmulator(auth, "http://127.0.0.1:${ports.auth}"`,
        `connectFirestoreEmulator(firestore, "127.0.0.1", ${ports.firestore})`,
        `connectFunctionsEmulator(functions, "127.0.0.1", ${ports.functions})`,
      ],
      "app/src/androidTest/kotlin/com/planterior/helper/feature/shop/InventoryScreenApi37Test.kt": [
        `http://10.0.2.2:${ports.storage}/v0/b/`,
      ],
    };
    for (const [relativePath, expectedReferences] of Object.entries(clientReferences)) {
      const source = readRepositoryFile(relativePath);
      for (const expectedReference of expectedReferences) {
        assert.ok(source.includes(expectedReference), `${relativePath} is missing ${expectedReference}`);
      }
    }

    const sourcePaths = execFileSync(
      "git",
      ["-c", "core.quotepath=false", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
      { cwd: repositoryRoot },
    ).toString("utf8").split("\0").filter(Boolean);
    const retiredPort = 8_000 + 80;
    const retiredPortPattern = new RegExp(`\\b${retiredPort}\\b`);
    const staleReferences = sourcePaths.filter((relativePath) =>
      retiredPortPattern.test(readRepositoryFile(relativePath)),
    );
    assert.deepEqual(staleReferences, []);
  });

  it("keeps backend account deletion categories equal to Android wire mappings", () => {
    const backend = readRepositoryFile("functions/src/account-deletion-contract.ts");
    const android = readRepositoryFile(
      "app/src/main/kotlin/com/planterior/helper/accountdeletion/FirebaseAccountDeletionRepository.kt",
    );
    const backendBlock = backend.match(
      /export const ACCOUNT_DELETION_SCOPES = \[([\s\S]*?)\] as const;/,
    );
    const androidBlock = android.match(
      /(?:private|internal) val SERVER_SCOPES\s*=\s*listOf\(([\s\S]*?)\n\s*\)/,
    );
    assert.ok(backendBlock, "backend account deletion scope declaration is missing");
    assert.ok(androidBlock, "Android account deletion wire mapping is missing");
    const wireValues = (block) => [...block.matchAll(/"([A-Z_]+)"/g)].map((match) => match[1]);
    const backendValues = wireValues(backendBlock[1]);
    const androidValues = wireValues(androidBlock[1]);
    assert.equal(new Set(backendValues).size, backendValues.length);
    assert.equal(new Set(androidValues).size, androidValues.length);
    assert.deepEqual([...androidValues].sort(), [...backendValues].sort());
  });

  it("limits deterministic gitleaks exemptions to exact emulator fixture values", () => {
    const config = readRepositoryFile(".gitleaks.toml");
    const description = "Deterministic mini-home emulator idempotency fixtures";
    const start = config.indexOf(`description = "${description}"`);
    assert.notEqual(start, -1);
    const nextAllowlist = config.indexOf("[[allowlists]]", start);
    const block = config.slice(start, nextAllowlist === -1 ? undefined : nextAllowlist);
    assert.ok(block.includes('targetRules = ["generic-api-key"]'));
    assert.ok(block.includes('condition = "AND"'));
    assert.ok(block.includes('regexTarget = "secret"'));
    assert.ok(block.includes("'''(?:^|/)functions/src/mini-home\\.emulator-spec\\.ts$'''"));
    const exactFixtures = ["legacy-" + "receipt-0001", "inventory-" + "fixture-0001"];
    assert.ok(block.includes(`'''^(?:${exactFixtures.join("|")})$'''`));
    assert.equal(block.match(/targetRules = \["generic-api-key"\]/g)?.length, 1);
    assert.doesNotMatch(block, /src\/(?:main|production)|paths\s*=\s*\[[^\]]*\.\*/s);
  });
});

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

  it("declares the bounded weather outbox lease recovery index", () => {
    const indexes = JSON.parse(
      fs.readFileSync(path.resolve(__dirname, "../../firestore.indexes.json"), "utf8"),
    );
    const recoveryIndex = indexes.indexes.find(
      (index) => index.collectionGroup === "weatherAlerts"
        && index.fields.some((field) => field.fieldPath === "leaseExpiresAt"),
    );
    assert.deepEqual(recoveryIndex, {
      collectionGroup: "weatherAlerts",
      queryScope: "COLLECTION_GROUP",
      fields: [
        { fieldPath: "status", order: "ASCENDING" },
        { fieldPath: "leaseExpiresAt", order: "ASCENDING" },
        { fieldPath: "__name__", order: "ASCENDING" },
      ],
    });
  });

  it("declares bounded Apple session and abuse-control cleanup indexes", () => {
    const indexes = JSON.parse(
      fs.readFileSync(path.resolve(__dirname, "../../firestore.indexes.json"), "utf8"),
    );
    for (const collectionGroup of ["appleAuthSessions", "appleAuthRateLimits"]) {
      assert.deepEqual(
        indexes.fieldOverrides.find((field) => field.collectionGroup === collectionGroup),
        {
          collectionGroup,
          fieldPath: "expiresAt",
          ttl: true,
          indexes: [],
        },
      );
      assert.deepEqual(
        indexes.indexes.find((index) => index.collectionGroup === collectionGroup),
        {
          collectionGroup,
          queryScope: "COLLECTION",
          fields: [
            { fieldPath: "expiresAt", order: "ASCENDING" },
            { fieldPath: "__name__", order: "ASCENDING" },
          ],
        },
      );
    }
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

  it("denies all direct Apple session and abuse-control access", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const anonymous = env.unauthenticatedContext().firestore();
    await env.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), "appleAuthSessions/session-a"), {
        expiresAt: ts("2026-08-12T00:10:00Z"),
      });
      await setDoc(doc(admin.firestore(), "appleAuthRateLimits/rate-a"), {
        count: 1,
        expiresAt: ts("2026-08-12T00:10:00Z"),
      });
    });
    for (const firestore of [owner, anonymous]) {
      await assertFails(getDoc(doc(firestore, "appleAuthSessions/session-a")));
      await assertFails(getDoc(doc(firestore, "appleAuthRateLimits/rate-a")));
      await assertFails(setDoc(doc(firestore, "appleAuthSessions/forged"), {
        authorizationCode: "secret",
      }));
      await assertFails(setDoc(doc(firestore, "appleAuthRateLimits/forged"), { count: 0 }));
    }
  });

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
      await setDoc(doc(admin.firestore(), "notificationRuntime/weatherRefreshCursor"), {
        documentPath: "users/user-a/weatherSettings/current",
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
    await assertFails(getDoc(doc(owner, "notificationRuntime/weatherRefreshCursor")));
    await assertFails(setDoc(doc(owner, "notificationRuntime/wateringClaimRecovery"), {
      documentPath: "notificationDeliveryClaims/claim-b",
    }));
    await assertFails(setDoc(doc(owner, "notificationRuntime/weatherRefreshCursor"), {
      documentPath: "users/user-b/weatherSettings/current",
    }));
  });

  it("denies owner-forged outcomes and permits only valid trusted-server state", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const fixtures = {
      notificationDeliveries: { ...write("user-a"), plantId: "plant-a", dueDate: "2026-08-12", attempt: 0, status: "SENT", scheduledFor: ts("2026-08-12T00:00:00Z"), deliveredAt: ts("2026-08-12T00:01:00Z"), deduplicationKey: "user-a:plant-a:2026-08-12:0" },
      weatherRisks: { ...write("user-a"), plantId: "plant-a", plantName: "몬스테라", snapshotId: "snapshot-a", type: "DRY", reason: "건조해요", action: "mist", detectedAt: ts("2026-08-12T00:00:00Z"), observedAt: ts("2026-08-12T00:00:00Z"), active: true, transition: 1, deliveredTransition: null, source: "DEVICE", locationConsentGeneration: 2 },
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

  it("denies every client role direct access to public shares and owner share metadata", async () => {
    const server = env.authenticatedContext("service", { server: true }).firestore();
    const owner = env.authenticatedContext("user-a").firestore();
    const foreign = env.authenticatedContext("user-b").firestore();
    const anonymous = env.unauthenticatedContext().firestore();
    const publicPath = "publicShares/" + "a".repeat(64);
    const metadataPath = "users/user-a/shareLinks/" + "s".repeat(43);
    await env.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), publicPath), { schemaVersion: 1, tokenHash: "a".repeat(64), sourceRevision: 1 });
      await setDoc(doc(admin.firestore(), metadataPath), { schemaVersion: 1, ownerUid: "user-a", sourceRevision: 1 });
    });
    for (const context of [server, owner, foreign, anonymous]) {
      await assertFails(getDoc(doc(context, publicPath)));
      await assertFails(getDoc(doc(context, metadataPath)));
      await assertFails(setDoc(doc(context, publicPath), { schemaVersion: 1 }));
      await assertFails(setDoc(doc(context, metadataPath), { schemaVersion: 1 }));
    }
  });
});
