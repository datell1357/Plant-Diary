const assert = require("node:assert/strict");
const { createHash } = require("node:crypto");
const { createRequire } = require("node:module");
const path = require("node:path");
const requireFunctions = createRequire(path.resolve(__dirname, "../../functions/package.json"));
const { deleteApp: deleteAdminApp, initializeApp: initializeAdminApp } = requireFunctions("firebase-admin/app");
const { getFirestore: getAdminFirestore, Timestamp } = requireFunctions("firebase-admin/firestore");
const { deleteApp, initializeApp } = require("firebase/app");
const { connectAuthEmulator, getAuth, signInAnonymously } = require("firebase/auth");
const { CustomProvider, initializeAppCheck } = require("firebase/app-check");
const { connectFirestoreEmulator, doc, getDoc, getFirestore } = require("firebase/firestore");
const { connectFunctionsEmulator, getFunctions, httpsCallable } = require("firebase/functions");

const projectId = "demo-planterior";

async function main() {
  assert.equal(process.env.GCLOUD_PROJECT, projectId);
  assert.equal(process.env.GOOGLE_APPLICATION_CREDENTIALS, undefined);
  const app = initializeApp({ projectId, apiKey: "demo-key", appId: "demo-app" });
  const auth = getAuth(app);
  const debugAppCheckToken = [
    Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url"),
    Buffer.from(JSON.stringify({
      aud: [`projects/${projectId}`],
      iss: `https://firebaseappcheck.googleapis.com/${projectId}`,
      sub: "demo-app",
      exp: Math.floor(Date.now() / 1000) + 3600,
    })).toString("base64url"),
    "debug-signature",
  ].join(".");
  initializeAppCheck(app, {
    provider: new CustomProvider({
      getToken: async () => ({
        token: debugAppCheckToken,
        expireTimeMillis: Date.now() + 3600_000,
      }),
    }),
    isTokenAutoRefreshEnabled: false,
  });
  const firestore = getFirestore(app);
  const functions = getFunctions(app, "us-central1");
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(firestore, "127.0.0.1", 8180);
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  const callableUrl =
    `http://127.0.0.1:5001/${projectId}/us-central1/applyRevisionedOwnerWrite`;
  const missingAppCheck = await fetch(callableUrl, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ data: { deliberately: "invalid" } }),
  });
  assert.equal(missingAppCheck.status, 401);
  assert.deepEqual(await missingAppCheck.json(), {
    error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
  });
  const applyWrite = httpsCallable(functions, "applyRevisionedOwnerWrite");
  const mutationPayload = {
    collection: "personalPlants",
    documentId: "callable-plant",
    mutationType: "CREATE",
    expectedRevision: 0,
    idempotencyKey: "operation-callable-0001",
    payload: { displayName: "몬스테라", registrationMethod: "MANUAL" },
  };

  await assert.rejects(
    () => applyWrite({ ...mutationPayload, expectedOwnerUid: "anonymous-owner" }),
    (error) => error.code === "functions/unauthenticated",
  );
  const credential = await signInAnonymously(auth);
  const mutation = { ...mutationPayload, expectedOwnerUid: credential.user.uid };
  const first = await applyWrite(mutation);
  const duplicate = await applyWrite(mutation);
  const conflict = await applyWrite({ ...mutation, idempotencyKey: "operation-callable-0002" });
  assert.deepEqual(first.data, { kind: "applied", revision: 1 });
  assert.deepEqual(duplicate.data, { kind: "duplicate", revision: 1 });
  assert.deepEqual(conflict.data, { kind: "conflict", actualRevision: 1 });
  const snapshot = await getDoc(doc(firestore, `users/${credential.user.uid}/personalPlants/callable-plant`));
  assert.equal(snapshot.data().ownerUid, credential.user.uid);
  assert.equal(snapshot.data().revision, 1);

  const loadMiniHomeSnapshot = httpsCallable(functions, "loadMiniHomeSnapshot");
  const combined = await loadMiniHomeSnapshot({ expectedOwnerUid: credential.user.uid });
  assert.equal(combined.data.contractVersion, 1);
  assert.equal(combined.data.ownerUid, credential.user.uid);
  assert.match(combined.data.snapshotToken, /^[a-f0-9]{64}$/);
  assert.equal(combined.data.layout.kind, "missing");
  assert.equal(combined.data.inventory.registeredPlantCount, 1);
  assert.equal(combined.data.plants.length, 1);

  const adminApp = initializeAdminApp({ projectId }, "apple-callback-smoke");
  const adminFirestore = getAdminFirestore(adminApp);
  const callbackUrl = `http://127.0.0.1:5001/${projectId}/us-central1/appleOAuthCallback`;
  const callbackState = "s".repeat(43);
  const callbackSessionId = "session-http-standard";
  const seedAppleSession = async (sessionId, state) => {
    await adminFirestore.doc(`appleAuthSessions/${sessionId}`).set({
      stateHash: createHash("sha256").update(state, "utf8").digest("base64url"),
      nonceHash: "n".repeat(43),
      codeChallenge: "c".repeat(43),
      authorizationCode: null,
      createdAt: Timestamp.fromMillis(Date.now()),
      expiresAt: Timestamp.fromMillis(Date.now() + 600_000),
      usedAt: null,
      abuseKeyHash: "a".repeat(64),
    });
  };
  await seedAppleSession(callbackSessionId, callbackState);
  const standardCallback = await fetch(callbackUrl, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      state: callbackState,
      code: "authorization-code",
      id_token: "callback.header.signature",
      user: JSON.stringify({
        name: { firstName: "Planterior", lastName: "Tester" },
        email: "relay@example.com",
      }),
    }),
    redirect: "manual",
  });
  assert.equal(standardCallback.status, 302);
  assert.equal(
    standardCallback.headers.get("location"),
    `planterior://auth/apple?sessionId=${callbackSessionId}&state=${callbackState}`,
  );
  const storedCallback = await adminFirestore.doc(`appleAuthSessions/${callbackSessionId}`).get();
  assert.equal(storedCallback.get("authorizationCode"), "authorization-code");
  assert.equal(JSON.stringify(storedCallback.data()).includes("relay@example.com"), false);
  assert.equal(JSON.stringify(storedCallback.data()).includes("callback.header.signature"), false);
  const replayedCallback = await fetch(callbackUrl, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      state: callbackState,
      code: "authorization-code",
      id_token: "callback.header.signature",
    }),
    redirect: "manual",
  });
  assert.equal(replayedCallback.status, 400);
  assert.equal((await replayedCallback.text()).includes("authorization-code"), false);

  const errorState = "e".repeat(43);
  await seedAppleSession("session-http-error", errorState);
  const errorCallback = await fetch(callbackUrl, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      state: errorState,
      error: "user_cancelled_authorize",
      error_description: "private provider text",
    }),
    redirect: "manual",
  });
  assert.equal(errorCallback.status, 302);
  assert.equal(errorCallback.headers.get("location"), "planterior://auth/apple?error=cancelled");
  await deleteAdminApp(adminApp);

  console.log(`FUNCTIONS_QA appCheck=valid missingAppCheck=rejected ownerDerived=true atomicMiniHomeSnapshot=${combined.data.snapshotGeneration} revision=${snapshot.data().revision} duplicate=${duplicate.data.kind} conflict=${conflict.data.kind} appleStandardCallback=accepted appleReplay=rejected appleError=accepted`);
  await deleteApp(app);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
