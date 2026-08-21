const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { createRequire } = require("node:module");

const root = path.resolve(__dirname, "../..");
const fixtureRoot = path.join(root, "test-fixtures");
const manifestPath = path.join(fixtureRoot, "todo14/catalog-media-fixture.json");
const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
const fixturePath = path.join(fixtureRoot, manifest.file);
const bytes = fs.readFileSync(fixturePath);

function fail(message) {
  throw new Error(`Todo 14 fixture contract: ${message}`);
}

function losslessWebpDimensions(value) {
  if (value.length < 25) fail("WebP payload is truncated");
  if (value.subarray(0, 4).toString("ascii") !== "RIFF") fail("RIFF magic is missing");
  if (value.subarray(8, 12).toString("ascii") !== "WEBP") fail("WEBP magic is missing");
  if (value.subarray(12, 16).toString("ascii") !== "VP8L") fail("fixture is not lossless WebP");
  if (value[20] !== 0x2f) fail("VP8L signature is invalid");
  const packed = value.readUInt32LE(21);
  return { width: (packed & 0x3fff) + 1, height: ((packed >>> 14) & 0x3fff) + 1 };
}

function validateFixture() {
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  const dimensions = losslessWebpDimensions(bytes);
  if (manifest.contractVersion !== 3) fail("unsupported manifest version");
  if (path.extname(manifest.file) !== ".webp") fail("fixture extension is not .webp");
  if (path.extname(manifest.storagePath) !== ".webp") fail("Storage path extension is not .webp");
  if (manifest.contentType !== "image/webp") fail("MIME type is not image/webp");
  if (manifest.byteSize !== bytes.length) fail("encoded byte size does not match");
  if (manifest.sha256 !== digest) fail("SHA-256 does not match");
  if (!manifest.storagePath.includes(`/${digest}.`)) fail("Storage path is not content addressed");
  if (!Number.isSafeInteger(manifest.mediaRevision) || manifest.mediaRevision < 1) fail("media revision is invalid");
  if (manifest.width !== dimensions.width || manifest.height !== dimensions.height) {
    fail("decoded dimensions do not match");
  }
  if (manifest.storageMetadata?.width !== String(dimensions.width)) fail("Storage width does not match");
  if (manifest.storageMetadata?.height !== String(dimensions.height)) fail("Storage height does not match");
}

async function main() {
  validateFixture();
  const requireFromFunctions = createRequire(path.join(root, "functions/package.json"));
  const { initializeApp } = requireFromFunctions("firebase-admin/app");
  const { getFirestore, Timestamp } = requireFromFunctions("firebase-admin/firestore");
  const { getStorage } = requireFromFunctions("firebase-admin/storage");
  initializeApp({ projectId: "demo-planterior", storageBucket: "demo-planterior.appspot.com" });

  await getFirestore().doc(`shopItems/${manifest.itemId}`).set({
    name: "햇살 벽지",
    description: "방을 환하게 꾸며요.",
    category: "BACKGROUND",
    acquisitionCondition: null,
    publicationState: "PUBLIC",
    assetPath: manifest.storagePath,
    assetSha256: manifest.sha256,
    assetContentType: manifest.contentType,
    assetByteSize: manifest.byteSize,
    assetWidth: manifest.width,
    assetHeight: manifest.height,
    assetMediaRevision: manifest.mediaRevision,
    revision: 1,
    updatedAt: Timestamp.fromDate(new Date("2026-08-20T00:00:00Z")),
  });
  const object = getStorage().bucket().file(manifest.storagePath);
  await object.save(bytes, {
    resumable: false,
    metadata: {
      contentType: manifest.contentType,
      metadata: manifest.storageMetadata,
    },
  });
  const [uploaded] = await object.getMetadata();
  if (uploaded.contentType !== manifest.contentType) fail("uploaded MIME type does not match");
  if (Number(uploaded.size) !== manifest.byteSize) fail("uploaded byte size does not match");
  if (uploaded.metadata?.width !== manifest.storageMetadata.width) fail("uploaded width does not match");
  if (uploaded.metadata?.height !== manifest.storageMetadata.height) fail("uploaded height does not match");
  if (uploaded.metadata?.sha256 !== manifest.sha256) fail("uploaded digest metadata does not match");
  if (uploaded.metadata?.mediaRevision !== String(manifest.mediaRevision)) fail("uploaded media revision does not match");
  console.log(`TODO14_FIXTURE_SEEDED path=${manifest.storagePath} sha256=${manifest.sha256}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
