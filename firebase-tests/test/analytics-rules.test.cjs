const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const {
  Timestamp,
  deleteDoc,
  doc,
  getDoc,
  setDoc,
  updateDoc,
} = require("firebase/firestore");
const { after, before, beforeEach, describe, it } = require("mocha");

const projectId = "demo-planterior";
let env;

const analyticsConsent = {
  schemaVersion: 1,
  granted: true,
  commandGeneration: 1,
  operationId: "analytics-consent-rules-0001",
  grantedAt: Timestamp.fromDate(new Date("2026-08-24T00:00:00.000Z")),
  revokedAt: null,
  updatedAt: Timestamp.fromDate(new Date("2026-08-24T00:00:00.000Z")),
};
const analyticsEvent = {
  schemaVersion: 1,
  eventName: "APP_SESSION_STARTED",
  consentRevision: 1,
  occurredAt: Timestamp.fromDate(new Date("2026-08-24T00:00:00.000Z")),
  expiresAt: Timestamp.fromDate(new Date("2026-09-28T00:00:00.000Z")),
};

describe("first-party analytics Firestore boundary", () => {
  it("declares analytics TTL and deterministic purge and expiry indexes", () => {
    const indexes = JSON.parse(
      fs.readFileSync(
        path.resolve(__dirname, "../../firestore.indexes.json"),
        "utf8",
      ),
    );
    assert.deepEqual(
      indexes.fieldOverrides.find(
        (override) =>
          override.collectionGroup === "analyticsEvents" &&
          override.fieldPath === "expiresAt",
      ),
      {
        collectionGroup: "analyticsEvents",
        fieldPath: "expiresAt",
        ttl: true,
        indexes: [],
      },
    );
    const analyticsIndexes = indexes.indexes.filter(
      (index) => index.collectionGroup === "analyticsEvents",
    );
    assert.deepEqual(analyticsIndexes, [
      {
        collectionGroup: "analyticsEvents",
        queryScope: "COLLECTION",
        fields: [
          { fieldPath: "consentRevision", order: "ASCENDING" },
          { fieldPath: "__name__", order: "ASCENDING" },
        ],
      },
      {
        collectionGroup: "analyticsEvents",
        queryScope: "COLLECTION_GROUP",
        fields: [
          { fieldPath: "expiresAt", order: "ASCENDING" },
          { fieldPath: "__name__", order: "ASCENDING" },
        ],
      },
    ]);
  });

  before(async () => {
    env = await initializeTestEnvironment({
      projectId,
      firestore: {
        rules: fs.readFileSync(
          path.resolve(__dirname, "../../firestore.rules"),
          "utf8",
        ),
      },
    });
  });

  beforeEach(async () => {
    await env.clearFirestore();
  });
  after(async () => {
    if (env) await env.cleanup();
  });

  it("allows owner consent read but denies direct consent writes and all event access", async () => {
    const owner = env.authenticatedContext("user-a").firestore();
    const foreign = env.authenticatedContext("user-b").firestore();
    const serverClaim = env
      .authenticatedContext("service", { server: true })
      .firestore();
    const anonymous = env.unauthenticatedContext().firestore();
    const consentPath = "users/user-a/consents/analytics";
    const eventPath =
      "users/user-a/analyticsEvents/11111111-1111-4111-8111-111111111111";

    await env.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), consentPath), analyticsConsent);
      await setDoc(doc(admin.firestore(), eventPath), analyticsEvent);
    });

    const readableConsent = await assertSucceeds(
      getDoc(doc(owner, consentPath)),
    );
    assert.deepEqual(Object.keys(readableConsent.data()).sort(), [
      "commandGeneration",
      "granted",
      "grantedAt",
      "operationId",
      "revokedAt",
      "schemaVersion",
      "updatedAt",
    ]);
    assert.equal(readableConsent.data().revision, undefined);
    await assertFails(getDoc(doc(foreign, consentPath)));
    await assertFails(getDoc(doc(anonymous, consentPath)));
    for (const context of [owner, foreign, anonymous, serverClaim]) {
      await assertFails(setDoc(doc(context, consentPath), analyticsConsent));
      await assertFails(
        updateDoc(doc(context, consentPath), { granted: false }),
      );
      await assertFails(deleteDoc(doc(context, consentPath)));
      await assertFails(getDoc(doc(context, eventPath)));
      await assertFails(setDoc(doc(context, eventPath), analyticsEvent));
      await assertFails(
        updateDoc(doc(context, eventPath), { consentRevision: 2 }),
      );
      await assertFails(deleteDoc(doc(context, eventPath)));
    }
  });
});
