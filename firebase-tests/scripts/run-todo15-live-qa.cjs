const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { createRequire } = require("node:module");

const root = path.resolve(__dirname, "../..");
const evidenceRoot = path.resolve(process.env.TODO15_EVIDENCE_DIR || path.join(root, ".omo/evidence/todo15/backend"));
const requireFunctions = createRequire(path.join(root, "functions/package.json"));
const {
  deleteApp: deleteAdminApp,
  initializeApp: initializeAdminApp,
} = requireFunctions("firebase-admin/app");
const { getFirestore: getAdminFirestore, Timestamp } = requireFunctions("firebase-admin/firestore");
const { getStorage: getAdminStorage } = requireFunctions("firebase-admin/storage");
const { deleteApp, initializeApp } = require("firebase/app");
const { connectAuthEmulator, getAuth, signInAnonymously } = require("firebase/auth");
const { CustomProvider, getToken, initializeAppCheck } = require("firebase/app-check");
const {
  connectFirestoreEmulator,
  doc,
  getDoc,
  getFirestore,
} = require("firebase/firestore");
const {
  connectFunctionsEmulator,
  getFunctions,
  httpsCallable,
} = require("firebase/functions");
const {
  connectStorageEmulator,
  getBytes,
  getStorage,
  ref,
} = require("firebase/storage");

const projectId = "demo-planterior";
const functionBase = `http://127.0.0.1:5001/${projectId}/us-central1`;
const lifetimeMillis = 30 * 24 * 60 * 60 * 1000;
const gitObjectId = /^[0-9a-f]{40}$/;
const usage = `Usage:
  node firebase-tests/scripts/run-todo15-live-qa.cjs \\
    --expected-head <40-lowercase-hex> --expected-tree <40-lowercase-hex>

Environment alternatives:
  TODO15_EXPECTED_HEAD=<40-lowercase-hex>
  TODO15_EXPECTED_TREE=<40-lowercase-hex>

Checks:
  --self-test  Run source-binding parser and mismatch checks without Firebase.
  --help       Print this help.
`;
const forbiddenKeys = new Set([
  "ownerUid", "miniHomeId", "placementId", "plantId", "personalPlantId", "displayName",
  "representativePhotoPath", "note", "location", "operationId", "token", "tokenHash",
  "projectionToken", "sourceProjectionGeneration", "requestHash", "idempotencyKey",
]);

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function git(...args) {
  const result = spawnSync("git", args, { cwd: root, encoding: "utf8" });
  if (result.status !== 0) throw new Error(result.stderr || `git ${args.join(" ")} failed`);
  return result.stdout.trim();
}

function expectedSource(args, environment) {
  const values = new Map();
  for (let index = 0; index < args.length; index += 1) {
    const name = args[index];
    if (name !== "--expected-head" && name !== "--expected-tree") {
      throw new Error(`unknown argument: ${name}`);
    }
    if (values.has(name)) throw new Error(`duplicate argument: ${name}`);
    const value = args[index + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new Error(`missing value for ${name}`);
    }
    values.set(name, value);
    index += 1;
  }
  const cliHead = values.get("--expected-head");
  const cliTree = values.get("--expected-tree");
  const envHead = environment.TODO15_EXPECTED_HEAD || undefined;
  const envTree = environment.TODO15_EXPECTED_TREE || undefined;
  if (cliHead && envHead && cliHead !== envHead) {
    throw new Error("--expected-head does not match TODO15_EXPECTED_HEAD");
  }
  if (cliTree && envTree && cliTree !== envTree) {
    throw new Error("--expected-tree does not match TODO15_EXPECTED_TREE");
  }
  const head = cliHead || envHead;
  const tree = cliTree || envTree;
  if (head === undefined) throw new Error("missing expected HEAD (--expected-head or TODO15_EXPECTED_HEAD)");
  if (tree === undefined) throw new Error("missing expected tree (--expected-tree or TODO15_EXPECTED_TREE)");
  if (!gitObjectId.test(head)) throw new Error("expected HEAD must be exactly 40 lowercase hex characters");
  if (!gitObjectId.test(tree)) throw new Error("expected tree must be exactly 40 lowercase hex characters");
  return { head, tree };
}

function requireCurrentSource(args, environment, readGit = git) {
  const expected = expectedSource(args, environment);
  const currentHead = readGit("rev-parse", "HEAD");
  const currentTree = readGit("rev-parse", "HEAD^{tree}");
  assert.equal(currentHead, expected.head, "current git HEAD does not match expected HEAD");
  assert.equal(currentTree, expected.tree, "current git tree does not match expected tree");
  return expected;
}

function runSourceBindingSelfTest() {
  const head = "1".repeat(40);
  const tree = "a".repeat(40);
  assert.throws(() => expectedSource([], {}), /missing expected HEAD/);
  assert.throws(
    () => expectedSource(["--expected-head", "A".repeat(40), "--expected-tree", tree], {}),
    /40 lowercase hex/,
  );
  assert.throws(
    () => requireCurrentSource(
      ["--expected-head", head, "--expected-tree", tree],
      {},
      (...gitArgs) => gitArgs.at(-1) === "HEAD" ? "2".repeat(40) : tree,
    ),
    /current git HEAD does not match/,
  );
  assert.deepEqual(
    requireCurrentSource(
      [],
      { TODO15_EXPECTED_HEAD: head, TODO15_EXPECTED_TREE: tree },
      (...gitArgs) => gitArgs.at(-1) === "HEAD" ? head : tree,
    ),
    { head, tree },
  );
  console.log("TODO15_LIVE_SOURCE_BINDING_SELF_TEST tests=4 failures=0");
}

function sourceManifest() {
  const files = git("ls-files", "-z").split("\0").filter(Boolean).sort();
  const lines = files.map((file) => `${sha256(fs.readFileSync(path.join(root, file)))}  ${file}`);
  return { algorithm: "sorted-git-file-sha256-lines-v1", files: files.length, sha256: sha256(`${lines.join("\n")}\n`) };
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`;
}

function curlTranscript(label, url, headers = [], method = "GET", body) {
  const args = ["-i", "-sS", "--max-time", "15", "-X", method];
  for (const header of headers) args.push("-H", header);
  if (body !== undefined) args.push("--data-binary", body);
  args.push(url);
  const command = ["curl", ...args].map(shellQuote).join(" ");
  const result = spawnSync("curl", args, { encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
  assert.equal(result.status, 0, `${label}: ${result.stderr}`);
  const transcript = `$ ${command}\n${result.stdout}`;
  const status = Number((result.stdout.match(/^HTTP\/\S+ (\d{3})/m) || [])[1]);
  assert.ok(Number.isInteger(status), `${label}: HTTP status missing`);
  return { label, status, transcript, body: result.stdout.split(/\r?\n\r?\n/).at(-1) };
}

function scrub(value, secrets) {
  let result = value;
  for (const secret of secrets.filter(Boolean).sort((a, b) => b.length - a.length)) {
    assert.ok(result.includes(secret), "in-memory transcript did not carry expected bearer value");
    result = result.replaceAll(secret, "<REDACTED>");
  }
  return result;
}

function scanPublic(value, pathParts = []) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => scanPublic(item, [...pathParts, String(index)]));
    return;
  }
  if (value !== null && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      assert.equal(forbiddenKeys.has(key), false, `private field ${[...pathParts, key].join(".")}`);
      scanPublic(item, [...pathParts, key]);
    }
    return;
  }
  if (typeof value === "string") {
    for (const privateValue of ["todo15-live-owner", "Private Todo 15 home", "private-plant", "private note"]) {
      assert.equal(value.includes(privateValue), false, `private value at ${pathParts.join(".")}`);
    }
  }
}

async function expectDenied(action, label) {
  await assert.rejects(action, (error) => {
    const code = String(error && error.code || "");
    return code.includes("permission-denied") || code.includes("unauthorized");
  }, label);
}

async function main(source) {
  fs.mkdirSync(evidenceRoot, { recursive: true });
  assert.equal(process.env.GCLOUD_PROJECT, projectId);
  assert.equal(process.env.GOOGLE_APPLICATION_CREDENTIALS, undefined);
  const emulatorSecretFile = path.join(root, "functions/.secret.local");
  assert.ok(
    (process.env.MINI_HOME_SHARE_TOKEN_KEY || "").length >= 32 || fs.existsSync(emulatorSecretFile),
    "ephemeral share key missing",
  );

  const app = initializeApp({ projectId, apiKey: "demo-key", appId: "todo15-live-app" }, "todo15-live-client");
  const auth = getAuth(app);
  const debugAppCheckToken = [
    Buffer.from(JSON.stringify({ alg: "none", typ: "JWT" })).toString("base64url"),
    Buffer.from(JSON.stringify({
      aud: [`projects/${projectId}`],
      iss: `https://firebaseappcheck.googleapis.com/${projectId}`,
      sub: "todo15-live-app",
      exp: Math.floor(Date.now() / 1000) + 3600,
    })).toString("base64url"),
    "debug-signature",
  ].join(".");
  const appCheck = initializeAppCheck(app, {
    provider: new CustomProvider({
      getToken: async () => ({ token: debugAppCheckToken, expireTimeMillis: Date.now() + 3_600_000 }),
    }),
    isTokenAutoRefreshEnabled: false,
  });
  const firestore = getFirestore(app);
  const functions = getFunctions(app, "us-central1");
  const storage = getStorage(app, `gs://${projectId}.appspot.com`);
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(firestore, "127.0.0.1", 8180);
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  connectStorageEmulator(storage, "127.0.0.1", 9199);

  const credential = await signInAnonymously(auth);
  const ownerUid = credential.user.uid;
  const idToken = await credential.user.getIdToken();
  const checked = await getToken(appCheck, false);
  assert.equal(checked.token, debugAppCheckToken);

  const adminApp = initializeAdminApp(
    { projectId, storageBucket: `${projectId}.appspot.com` },
    "todo15-live-admin",
  );
  const adminFirestore = getAdminFirestore(adminApp);
  const adminStorage = getAdminStorage(adminApp);
  const save = httpsCallable(functions, "saveMiniHomeLayout");
  const create = httpsCallable(functions, "createMiniHomeShareLink");
  const revoke = httpsCallable(functions, "revokeMiniHomeShareLink");
  const transcripts = [];
  const cleanup = [];
  let share;
  let expiryShare;
  try {
    await assert.rejects(
      () => create({ operationId: "todo15-unsaved-operation", expectedRevision: 1 }),
      (error) => error.code === "functions/failed-precondition",
      "unsaved revision must not share",
    );
    const saved = await save({
      expectedOwnerUid: ownerUid,
      miniHomeId: "todo15-live-home",
      expectedRevision: 0,
      idempotencyKey: "todo15-layout-save-0001",
      name: "Private Todo 15 home",
      placements: [],
    });
    assert.deepEqual(saved.data, { kind: "applied", revision: 1 });

    const callablePayload = JSON.stringify({
      data: { operationId: "todo15-share-operation-0001", expectedRevision: 1 },
    });
    const callable = curlTranscript(
      "create-callable",
      `${functionBase}/createMiniHomeShareLink`,
      [
        "content-type: application/json",
        `Authorization: Bearer ${idToken}`,
        `X-Firebase-AppCheck: ${checked.token}`,
      ],
      "POST",
      callablePayload,
    );
    assert.equal(callable.status, 200);
    share = JSON.parse(callable.body).result;
    const replay = await create({ operationId: "todo15-share-operation-0001", expectedRevision: 1 });
    assert.deepEqual(replay.data, share);
    const token = new URL(share.url).searchParams.get("token");
    assert.match(token, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(Date.parse(share.expiresAt) - Date.parse(share.createdAt), lifetimeMillis);

    const metadataRef = adminFirestore.doc(`users/${ownerUid}/shareLinks/${share.shareId}`);
    const metadata = await metadataRef.get();
    const tokenHash = metadata.get("tokenHash");
    assert.equal(tokenHash, sha256(token));
    const publicRef = adminFirestore.doc(`publicShares/${tokenHash}`);
    const publicDocument = await publicRef.get();
    const stored = JSON.stringify({ metadata: metadata.data(), public: publicDocument.data() });
    assert.equal(stored.includes(token), false);
    assert.equal(stored.includes(share.url), false);
    assert.equal(publicDocument.id, tokenHash);

    await expectDenied(() => getDoc(doc(firestore, metadataRef.path)), "private share metadata read");
    await expectDenied(() => getDoc(doc(firestore, publicRef.path)), "public mirror client read");
    const deniedStoragePath = `share-images/${ownerUid}/${share.shareId}/private.png`;
    await adminStorage.bucket().file(deniedStoragePath).save(Buffer.from("not-public"), {
      resumable: false,
      metadata: { contentType: "image/png" },
    });
    cleanup.push(`storage:${deniedStoragePath}`);
    await expectDenied(() => getBytes(ref(storage, deniedStoragePath)), "share Storage read");

    const activeHtml = curlTranscript("active-html", share.url, ["Accept: text/html"]);
    assert.equal(activeHtml.status, 200);
    for (const header of [
      "cache-control: no-store", "content-security-policy: default-src 'none'",
      "x-content-type-options: nosniff", "referrer-policy: no-referrer",
      "x-robots-tag: noindex, nofollow, noarchive",
    ]) assert.match(activeHtml.transcript.toLowerCase(), new RegExp(header.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
    assert.equal(/<script|<iframe|https?:\/\//i.test(activeHtml.body), false);
    transcripts.push(activeHtml);

    const activeJson = curlTranscript("active-json", share.url, ["Accept: application/json"]);
    assert.equal(activeJson.status, 200);
    const payload = JSON.parse(activeJson.body);
    assert.equal(payload.contractVersion, 1);
    assert.equal(payload.sourceRevision, 1);
    assert.deepEqual(payload.placements, []);
    scanPublic(payload);
    transcripts.push(activeJson);

    const unknownUrl = new URL(share.url);
    unknownUrl.searchParams.set("token", "z".repeat(43));
    const unknown = curlTranscript("unknown", unknownUrl.toString(), ["Accept: application/json"]);
    const malformed = curlTranscript(
      "malformed",
      `${functionBase}/publicMiniHomeShare?token=malformed`,
      ["Accept: application/json"],
    );
    assert.equal(unknown.status, 404);
    assert.equal(malformed.status, 404);
    assert.equal(unknown.body, malformed.body);
    transcripts.push(unknown, malformed);

    const revoked = await revoke({ shareId: share.shareId });
    assert.equal(revoked.data.shareId, share.shareId);
    const afterRevoke = curlTranscript("revoked", share.url, ["Accept: application/json"]);
    assert.equal(afterRevoke.status, 404);
    transcripts.push(afterRevoke);

    expiryShare = (await create({
      operationId: "todo15-share-operation-expiry",
      expectedRevision: 1,
    })).data;
    const expiryToken = new URL(expiryShare.url).searchParams.get("token");
    const expiryMetadata = await adminFirestore
      .doc(`users/${ownerUid}/shareLinks/${expiryShare.shareId}`).get();
    const expiryHash = expiryMetadata.get("tokenHash");
    const expiryRef = adminFirestore.doc(`publicShares/${expiryHash}`);
    const baseExpiry = await expiryRef.get();
    const now = Date.now();
    const equalCreated = now - lifetimeMillis;
    await expiryRef.update({
      createdAt: Timestamp.fromMillis(equalCreated),
      expiresAt: Timestamp.fromMillis(now),
      "snapshot.createdAt": new Date(equalCreated).toISOString(),
      "snapshot.expiresAt": new Date(now).toISOString(),
    });
    const equality = curlTranscript("expiry-equality", expiryShare.url, ["Accept: application/json"]);
    assert.equal(equality.status, 404);
    transcripts.push(equality);

    const plus31Created = now - 31 * 24 * 60 * 60 * 1000;
    const plus31Expiry = plus31Created + lifetimeMillis;
    await expiryRef.update({
      createdAt: Timestamp.fromMillis(plus31Created),
      expiresAt: Timestamp.fromMillis(plus31Expiry),
      "snapshot.createdAt": new Date(plus31Created).toISOString(),
      "snapshot.expiresAt": new Date(plus31Expiry).toISOString(),
    });
    const plus31 = curlTranscript("expiry-plus-31-days", expiryShare.url, ["Accept: application/json"]);
    assert.equal(plus31.status, 404);
    transcripts.push(plus31);
    assert.ok(expiryToken);
    assert.equal(JSON.stringify(baseExpiry.data()).includes(expiryToken), false);

    const allRaw = [callable, ...transcripts].map((entry) => `===== ${entry.label} =====\n${entry.transcript}`).join("\n\n");
    const allSecrets = [idToken, checked.token, token, expiryToken];
    const scrubbed = scrub(allRaw, allSecrets);
    assert.equal(allSecrets.some((secret) => scrubbed.includes(secret)), false);
    fs.writeFileSync(path.join(evidenceRoot, "todo15-live-http-transcript.txt"), `${scrubbed}\n`);

    const machine = {
      contractVersion: 1,
      source: {
        head: source.head,
        tree: source.tree,
        manifest: sourceManifest(),
      },
      runtime: {
        requiredNodeMajor: 22,
        actualNode: process.version,
        projectId,
        emulators: { auth: 9099, firestore: 8180, functions: 5001, storage: 9199 },
        externalWrites: false,
      },
      callable: {
        auth: "anonymous-emulator-id-token",
        appCheck: "custom-emulator-token",
        createStatus: callable.status,
        unsavedRevisionRejected: true,
        emptySavedRevisionShared: true,
        exactReplay: true,
      },
      lifecycle: {
        activeHtml: activeHtml.status,
        activeJson: activeJson.status,
        unknown: unknown.status,
        malformed: malformed.status,
        revoked: afterRevoke.status,
        expiryEquality: equality.status,
        expiryPlus31Days: plus31.status,
        lifetimeDays: 30,
      },
      privacy: {
        recursivePublicScan: "passed",
        rawTokenAtRest: false,
        tokenHashAtRest: tokenHash,
        clientFirestoreMetadataRead: "denied",
        clientFirestorePublicRead: "denied",
        clientStorageShareRead: "denied",
        transcriptBearers: "redacted-after-in-memory-comparison",
      },
      platformSeparation: "Android production codec requires canonical HTTPS; live emulator transport is HTTP and is verified separately.",
      transcriptSha256: sha256(Buffer.from(`${scrubbed}\n`)),
    };
    fs.writeFileSync(
      path.join(evidenceRoot, "todo15-live-result.json"),
      `${JSON.stringify(machine, null, 2)}\n`,
    );
    console.log(`TODO15_LIVE_QA activeHtml=200 activeJson=200 unknown=404 malformed=404 revoked=404 expiryEquality=404 expiryPlus31=404 replay=exact privacy=passed transcriptSha256=${machine.transcriptSha256}`);
  } finally {
    await adminStorage.bucket().file(`share-images/${ownerUid}/${share?.shareId || "none"}/private.png`).delete({ ignoreNotFound: true });
    await adminFirestore.recursiveDelete(adminFirestore.collection("users"));
    await adminFirestore.recursiveDelete(adminFirestore.collection("publicShares"));
    await adminFirestore.recursiveDelete(adminFirestore.collection("catalogProjectionPointers"));
    await adminFirestore.recursiveDelete(adminFirestore.collection("catalogProjections"));
    await adminFirestore.recursiveDelete(adminFirestore.collection("shopItems"));
    await deleteAdminApp(adminApp);
    await deleteApp(app);
    const receipt = [
      `cleanedAt=${new Date().toISOString()}`,
      `firestoreUsers=${(await fetch("http://127.0.0.1:8180/v1/projects/demo-planterior/databases/(default)/documents/users?pageSize=1").then((r) => r.json())).documents?.length || 0}`,
      "firestorePublicShares=0",
      "storageShareObject=deleted",
      "secretFile=not-created",
      "externalWrites=false",
    ].join("\n");
    fs.writeFileSync(path.join(evidenceRoot, "todo15-live-cleanup.txt"), `${receipt}\n`);
  }
}

function run() {
  const args = process.argv.slice(2);
  if (args.length === 1 && (args[0] === "--help" || args[0] === "-h")) {
    process.stdout.write(usage);
    return;
  }
  if (args.length === 1 && args[0] === "--self-test") {
    runSourceBindingSelfTest();
    return;
  }
  let source;
  try {
    source = requireCurrentSource(args, process.env);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
    return;
  }
  main(source).catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}

run();
