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
let app;
let adminApp;

async function main() {
  assert.equal(process.env.GCLOUD_PROJECT, projectId);
  assert.equal(process.env.GOOGLE_APPLICATION_CREDENTIALS, undefined);
  app = initializeApp({ projectId, apiKey: "demo-key", appId: "demo-app" });
  const auth = getAuth(app);
  const debugAppCheckToken = process.env.FUNCTIONS_EMULATOR_APP_CHECK_DEBUG_TOKEN;
  assert.equal(typeof debugAppCheckToken, "string");
  assert.notEqual(debugAppCheckToken.length, 0);
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
  const missingAnalyticsAppCheck = await fetch(
    `http://127.0.0.1:5001/${projectId}/us-central1/recordAnalyticsEvent`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ data: { deliberately: "invalid" } }),
    },
  );
  assert.equal(missingAnalyticsAppCheck.status, 401);
  const getAnalyticsConsent = httpsCallable(functions, "getAnalyticsConsent");
  await assert.rejects(
    () => getAnalyticsConsent({ ownerUid: "anonymous-owner" }),
    (error) => error.code === "functions/unauthenticated",
  );
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
  adminApp = initializeAdminApp({ projectId }, "apple-callback-smoke");
  const adminFirestore = getAdminFirestore(adminApp);
  const appCheckGuardDocumentId = "app-check-guard-plant";
  const appCheckGuardDocument = adminFirestore.doc(
    `users/${credential.user.uid}/personalPlants/${appCheckGuardDocumentId}`,
  );
  const authenticatedMutation = {
    collection: "personalPlants",
    documentId: appCheckGuardDocumentId,
    mutationType: "CREATE",
    expectedRevision: 0,
    idempotencyKey: "app-check-guard-0001",
    expectedOwnerUid: credential.user.uid,
    payload: { displayName: "App Check guard", registrationMethod: "MANUAL" },
  };
  const authToken = await credential.user.getIdToken();
  async function invokeAuthenticatedMutation(appCheckToken) {
    return fetch(callableUrl, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${authToken}`,
        ...(appCheckToken === undefined ? {} : { "x-firebase-appcheck": appCheckToken }),
      },
      body: JSON.stringify({ data: authenticatedMutation }),
    });
  }
  for (const appCheckToken of [undefined, "not-a-valid-app-check-token", "wrong-debug-token"]) {
    const rejected = await invokeAuthenticatedMutation(appCheckToken);
    assert.equal(rejected.status, 401);
    assert.deepEqual(await rejected.json(), {
      error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
    });
    assert.equal((await appCheckGuardDocument.get()).exists, false);
  }
  const accepted = await fetch(
    `http://127.0.0.1:5001/${projectId}/us-central1/getAnalyticsConsent`,
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${authToken}`,
        "x-firebase-appcheck": debugAppCheckToken,
      },
      body: JSON.stringify({ data: { ownerUid: credential.user.uid } }),
    },
  );
  assert.equal(accepted.status, 200);
  assert.equal((await appCheckGuardDocument.get()).exists, false);
  const analyticsOwner = { ownerUid: credential.user.uid };
  const setAnalyticsConsent = httpsCallable(functions, "setAnalyticsConsent");
  const recordAnalyticsEvent = httpsCallable(functions, "recordAnalyticsEvent");
  assert.deepEqual((await getAnalyticsConsent(analyticsOwner)).data, {
    schemaVersion: 1,
    granted: false,
    commandGeneration: 0,
    grantedAtEpochMillis: null,
    revokedAtEpochMillis: null,
  });
  await assert.rejects(
    () => getAnalyticsConsent({ ownerUid: "foreign-owner" }),
    (error) => error.code === "functions/permission-denied",
  );
  const analyticsGrant = await setAnalyticsConsent({
    ...analyticsOwner,
    granted: true,
    commandGeneration: 1,
    operationId: "analytics-smoke-grant-0001",
  });
  assert.equal(analyticsGrant.data.granted, true);
  assert.equal(analyticsGrant.data.commandGeneration, 1);
  assert.equal(analyticsGrant.data.replayed, false);
  const analyticsEvent = {
    schemaVersion: 1,
    eventId: "11111111-1111-4111-8111-111111111111",
    eventName: "APP_SESSION_STARTED",
    consentRevision: 1,
  };
  const secondAnalyticsEvent = {
    ...analyticsEvent,
    eventId: "22222222-2222-4222-8222-222222222222",
    eventName: "CARE_INFORMATION_VIEWED",
  };
  const recordedAnalytics = await recordAnalyticsEvent({
    ...analyticsOwner,
    events: [analyticsEvent, secondAnalyticsEvent],
  });
  const replayedAnalytics = await recordAnalyticsEvent({
    ...analyticsOwner,
    events: [secondAnalyticsEvent, analyticsEvent],
  });
  assert.deepEqual(recordedAnalytics.data, {
    results: [
      { eventId: analyticsEvent.eventId, accepted: true, duplicate: false },
      { eventId: secondAnalyticsEvent.eventId, accepted: true, duplicate: false },
    ],
  });
  assert.deepEqual(replayedAnalytics.data, {
    results: [
      { eventId: secondAnalyticsEvent.eventId, accepted: true, duplicate: true },
      { eventId: analyticsEvent.eventId, accepted: true, duplicate: true },
    ],
  });
  await assert.rejects(
    () => recordAnalyticsEvent({
      ...analyticsOwner,
      events: [{ ...analyticsEvent, properties: {} }],
    }),
    (error) => error.code === "functions/invalid-argument",
  );
  await assert.rejects(
    () => recordAnalyticsEvent({
      ...analyticsOwner,
      events: [{
        ...analyticsEvent,
        eventId: "33333333-3333-4333-8333-333333333333",
        eventName: "WATERING_NOTIFICATION_SENT",
      }],
    }),
    (error) => error.code === "functions/permission-denied",
  );
  const storedAnalyticsRef = adminFirestore.doc(
    `users/${credential.user.uid}/analyticsEvents/${analyticsEvent.eventId}`,
  );
  const storedAnalytics = await storedAnalyticsRef.get();
  assert.deepEqual(Object.keys(storedAnalytics.data()).sort(), [
    "consentRevision", "eventName", "expiresAt", "occurredAt", "schemaVersion",
  ]);
  const storedConsent = await adminFirestore
    .doc(`users/${credential.user.uid}/consents/analytics`)
    .get();
  assert.deepEqual(Object.keys(storedConsent.data()).sort(), [
    "commandGeneration", "granted", "grantedAt", "operationId", "revokedAt",
    "schemaVersion", "updatedAt",
  ]);
  assert.equal(storedConsent.data().revision, undefined);
  const analyticsRevoke = await setAnalyticsConsent({
    ...analyticsOwner,
    granted: false,
    commandGeneration: 2,
    operationId: "analytics-smoke-revoke-0002",
  });
  assert.equal(analyticsRevoke.data.granted, false);
  assert.equal(analyticsRevoke.data.commandGeneration, 2);
  assert.equal(analyticsRevoke.data.purgedEventCount, 2);
  assert.equal((await storedAnalyticsRef.get()).exists, false);

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

  const previewDeletion = httpsCallable(functions, "previewAccountDeletion");
  const requestDeletion = httpsCallable(functions, "requestAccountDeletion");
  const getDeletionStatus = httpsCallable(functions, "getAccountDeletionStatus");
  const cancelDeletion = httpsCallable(functions, "cancelAccountDeletion");
  const retryDeletion = httpsCallable(functions, "retryAccountDeletion");
  const deletionOwner = { expectedOwnerUid: credential.user.uid };
  const deletionPreview = await previewDeletion(deletionOwner);
  assert.equal(deletionPreview.data.request, null);
  assert.equal(deletionPreview.data.scope.gracePeriodMillis, 7 * 24 * 60 * 60 * 1000);
  assert.deepEqual(deletionPreview.data.scope.categories, [
    "PUBLIC_SHARES",
    "NOTIFICATION_ENDPOINT_OWNERS",
    "PRIVATE_MEDIA_RESERVATIONS",
    "IDENTIFICATION_ORIGINALS",
    "PLANT_PHOTOS",
    "SHARE_IMAGES",
    "OWNER_GLOBAL_SWEEP",
    "USER_DOCUMENTS",
    "AUTH_ACCOUNT",
  ]);
  await assert.rejects(
    () => previewDeletion({ expectedOwnerUid: "foreign-owner" }),
    (error) => error.code === "functions/permission-denied",
  );
  const deletionCommand = {
    ...deletionOwner,
    confirmed: true,
    idempotencyKey: "delete-aaaaaaaa",
  };
  const deletionRequested = await requestDeletion(deletionCommand);
  const deletionDuplicate = await requestDeletion(deletionCommand);
  const deletionStatus = await getDeletionStatus(deletionOwner);
  assert.deepEqual(deletionDuplicate.data, deletionRequested.data);
  assert.deepEqual(deletionStatus.data, deletionRequested.data);
  assert.equal(
    deletionRequested.data.scheduledForMillis - deletionRequested.data.requestedAtMillis,
    7 * 24 * 60 * 60 * 1000,
  );
  const storedDeletionRef = adminFirestore.doc(`accountDeletionRequests/${credential.user.uid}`);
  const storedDeletion = await storedDeletionRef.get();
  assert.equal(storedDeletion.exists, true);
  assert.equal(JSON.stringify(storedDeletion.data()).includes(deletionCommand.idempotencyKey), false);
  const cancelledDeletion = await cancelDeletion({
    ...deletionOwner,
    requestId: deletionRequested.data.requestId,
  });
  assert.equal(cancelledDeletion.data.status, "CANCELLED");
  const rerequestedDeletion = await requestDeletion({
    ...deletionOwner,
    confirmed: true,
    idempotencyKey: "delete-bbbbbbbb",
  });
  assert.equal(rerequestedDeletion.data.status, "RECEIVED");
  const partialFailureAt = Timestamp.now();
  await storedDeletionRef.update({
    status: "PARTIALLY_FAILED",
    scheduledFor: partialFailureAt,
    nextAttemptAt: null,
    leaseExpiresAt: null,
    claimGeneration: 1,
    updatedAt: partialFailureAt,
    completedScopes: ["PUBLIC_SHARES"],
    failedScopes: [
      "NOTIFICATION_ENDPOINT_OWNERS",
      "PRIVATE_MEDIA_RESERVATIONS",
      "IDENTIFICATION_ORIGINALS",
      "PLANT_PHOTOS",
      "SHARE_IMAGES",
      "OWNER_GLOBAL_SWEEP",
      "USER_DOCUMENTS",
      "AUTH_ACCOUNT",
    ],
  });
  const retriedDeletion = await retryDeletion({
    ...deletionOwner,
    confirmed: true,
    idempotencyKey: "delete-cccccccc",
  });
  assert.equal(retriedDeletion.data.status, "PARTIALLY_FAILED");
  await storedDeletionRef.update({ status: "PROCESSING" });
  await assert.rejects(
    () => applyWrite({
      ...mutation,
      mutationType: "UPDATE",
      expectedRevision: 1,
      idempotencyKey: "operation-callable-frozen",
      payload: { note: "blocked" },
    }),
    (error) => error.code === "functions/failed-precondition",
  );
  await assert.rejects(
    () => setAnalyticsConsent({
      ...analyticsOwner,
      granted: true,
      commandGeneration: 3,
      operationId: "analytics-smoke-locked-0003",
    }),
    (error) => error.code === "functions/failed-precondition",
  );
  await storedDeletionRef.delete();

  const reserveMedia = httpsCallable(functions, "reservePrivateMediaUpload");
  const commitMedia = httpsCallable(functions, "commitPrivateMediaReservation");
  await assert.rejects(
    () => reserveMedia({
      expectedOwnerUid: "foreign-owner",
      mediaKind: "PLANT_PHOTO",
      contentType: "image/webp",
      byteSize: 3,
      idempotencyKey: "media-smoke-operation-0001",
    }),
    (error) => error.code === "functions/permission-denied",
  );
  await assert.rejects(
    () => commitMedia({
      expectedOwnerUid: credential.user.uid,
      reservationId: "reservation_missing_12345678",
    }),
    (error) => error.code === "functions/not-found",
  );

  const loadMiniHomeSnapshot = httpsCallable(functions, "loadMiniHomeSnapshot");
  const combined = await loadMiniHomeSnapshot({ expectedOwnerUid: credential.user.uid });
  assert.equal(combined.data.contractVersion, 1);
  assert.equal(combined.data.ownerUid, credential.user.uid);
  assert.match(combined.data.snapshotToken, /^[a-f0-9]{64}$/);
  assert.equal(combined.data.layout.kind, "missing");
  assert.equal(combined.data.inventory.registeredPlantCount, 1);
  assert.equal(combined.data.plants.length, 1);

  const saveMiniHome = httpsCallable(functions, "saveMiniHomeLayout");
  const saved = await saveMiniHome({
    expectedOwnerUid: credential.user.uid,
    miniHomeId: "share-smoke-home",
    expectedRevision: 0,
    idempotencyKey: "share-smoke-layout-0001",
    name: "Private smoke home",
    placements: [],
  });
  assert.deepEqual(saved.data, { kind: "applied", revision: 1 });
  const createShare = httpsCallable(functions, "createMiniHomeShareLink");
  const revokeShare = httpsCallable(functions, "revokeMiniHomeShareLink");
  const shareRequest = { operationId: "share-smoke-operation-0001", expectedRevision: 1 };
  const share = await createShare(shareRequest);
  const shareReplay = await createShare(shareRequest);
  assert.deepEqual(shareReplay.data, share.data);
  assert.match(share.data.shareId, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(Date.parse(share.data.expiresAt) - Date.parse(share.data.createdAt), 30 * 24 * 60 * 60 * 1000);
  const publicJson = await fetch(share.data.url, { headers: { accept: "application/json" } });
  assert.equal(publicJson.status, 200);
  assert.equal(publicJson.headers.get("cache-control"), "no-store");
  assert.equal(publicJson.headers.get("x-content-type-options"), "nosniff");
  assert.match(publicJson.headers.get("content-security-policy"), /default-src 'none'/);
  const publicPayload = await publicJson.json();
  assert.equal(publicPayload.contractVersion, 1);
  assert.equal(publicPayload.sourceRevision, 1);
  assert.deepEqual(publicPayload.grid, { columns: 5, rows: 4, projection: "isometric" });
  assert.equal(JSON.stringify(publicPayload).includes("Private smoke home"), false);
  const publicHtml = await fetch(share.data.url, { headers: { accept: "text/html" } });
  assert.equal(publicHtml.status, 200);
  assert.equal((await publicHtml.text()).includes("<script"), false);
  const unknownShare = await fetch(share.data.url.replace(/token=[A-Za-z0-9_-]{43}/, `token=${"z".repeat(43)}`));
  assert.equal(unknownShare.status, 404);

  const storedShare = await adminFirestore.doc(`users/${credential.user.uid}/shareLinks/${share.data.shareId}`).get();
  const rawToken = new URL(share.data.url).searchParams.get("token");
  assert.ok(rawToken);
  assert.equal(JSON.stringify(storedShare.data()).includes(rawToken), false);
  const firstRevoke = await revokeShare({ shareId: share.data.shareId });
  const replayedRevoke = await revokeShare({ shareId: share.data.shareId });
  assert.deepEqual(Object.keys(firstRevoke.data).sort(), ["revokedAt", "shareId"]);
  assert.equal(firstRevoke.data.shareId, share.data.shareId);
  assert.equal(new Date(firstRevoke.data.revokedAt).toISOString(), firstRevoke.data.revokedAt);
  assert.deepEqual(replayedRevoke.data, firstRevoke.data);
  const revokedMetadata = await adminFirestore.doc(`users/${credential.user.uid}/shareLinks/${share.data.shareId}`).get();
  const revokedPublic = await adminFirestore.doc(`publicShares/${storedShare.get("tokenHash")}`).get();
  assert.equal(revokedMetadata.get("revokedAt").toDate().toISOString(), firstRevoke.data.revokedAt);
  assert.equal(revokedPublic.get("revokedAt").toDate().toISOString(), firstRevoke.data.revokedAt);
  assert.equal((await fetch(share.data.url)).status, 404);

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
  adminApp = undefined;

  console.log(`FUNCTIONS_QA appCheck=valid missingAppCheck=rejected ownerDerived=true atomicMiniHomeSnapshot=${combined.data.snapshotGeneration} revision=${snapshot.data().revision} duplicate=${duplicate.data.kind} conflict=${conflict.data.kind} deletionScopes=9 mediaCallables=2 mediaForeign=denied mediaMissing=not-found deletionDuplicate=exact deletionForeign=denied deletionRetry=immediate processingFreeze=denied idempotencyRawAtRest=false shareReplay=exact revokeReplay=original-timestamp revokeFields=exact publicJson=200 publicHtml=200 revoked=404 rawTokenAtRest=false appleStandardCallback=accepted appleReplay=rejected appleError=accepted`);
  await deleteApp(app);
  app = undefined;
}

main().catch(async (error) => {
  console.error(error);
  const cleanup = await Promise.allSettled([
    adminApp === undefined ? Promise.resolve() : deleteAdminApp(adminApp),
    app === undefined ? Promise.resolve() : deleteApp(app),
  ]);
  for (const result of cleanup) {
    if (result.status === "rejected") console.error(result.reason);
  }
  process.exitCode = 1;
});
