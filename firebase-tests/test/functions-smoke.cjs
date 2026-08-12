const assert = require("node:assert/strict");
const { deleteApp, initializeApp } = require("firebase/app");
const { connectAuthEmulator, getAuth, signInAnonymously } = require("firebase/auth");
const { connectFirestoreEmulator, doc, getDoc, getFirestore } = require("firebase/firestore");
const { connectFunctionsEmulator, getFunctions, httpsCallable } = require("firebase/functions");

const projectId = "demo-planterior";

async function main() {
  assert.equal(process.env.GCLOUD_PROJECT, projectId);
  assert.equal(process.env.GOOGLE_APPLICATION_CREDENTIALS, undefined);
  const app = initializeApp({ projectId, apiKey: "demo-key", appId: "demo-app" });
  const auth = getAuth(app);
  const firestore = getFirestore(app);
  const functions = getFunctions(app, "us-central1");
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(firestore, "127.0.0.1", 8080);
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  const applyWrite = httpsCallable(functions, "applyRevisionedOwnerWrite");
  const mutation = {
    collection: "personalPlants",
    documentId: "callable-plant",
    expectedRevision: 0,
    idempotencyKey: "operation-callable-0001",
    payload: { displayName: "몬스테라", registrationMethod: "MANUAL" },
  };

  await assert.rejects(
    () => applyWrite(mutation),
    (error) => error.code === "functions/unauthenticated",
  );
  const credential = await signInAnonymously(auth);
  const first = await applyWrite(mutation);
  const duplicate = await applyWrite(mutation);
  const conflict = await applyWrite({ ...mutation, idempotencyKey: "operation-callable-0002" });
  assert.deepEqual(first.data, { kind: "applied", revision: 1 });
  assert.deepEqual(duplicate.data, { kind: "duplicate", revision: 1 });
  assert.deepEqual(conflict.data, { kind: "conflict", actualRevision: 1 });
  const snapshot = await getDoc(doc(firestore, `users/${credential.user.uid}/personalPlants/callable-plant`));
  assert.equal(snapshot.data().ownerUid, credential.user.uid);
  assert.equal(snapshot.data().revision, 1);
  console.log(`FUNCTIONS_QA ownerDerived=true revision=${snapshot.data().revision} duplicate=${duplicate.data.kind} conflict=${conflict.data.kind}`);
  await deleteApp(app);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
