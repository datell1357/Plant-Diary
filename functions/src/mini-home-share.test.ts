import assert from "node:assert/strict";
import type { Server } from "node:http";
import test from "node:test";
import express from "express";
import type { MiniHomeSnapshot } from "./mini-home-snapshot.js";
import {
  MINI_HOME_SHARE_LIFETIME_MILLIS,
  MiniHomeShareError,
  createPublicMiniHomeShareHandler,
  deriveMiniHomeShareIdentity,
  executeCreateMiniHomeShareLink,
  executeRevokeMiniHomeShareLink,
  renderPublicMiniHomeShareHtml,
  sanitizeMiniHomeShareSnapshot,
  type CreateMiniHomeShareResult,
  type MiniHomeShareStore,
  type PublicMiniHomeShareSnapshot,
} from "./mini-home-share.js";

const ownerUid = "share-owner";
const key = "test-only-mini-home-share-token-key-1234567890";
const createdAt = new Date("2026-08-22T00:00:00.000Z");

const snapshot: MiniHomeSnapshot = {
  contractVersion: 1,
  ownerUid,
  snapshotToken: "a".repeat(64),
  snapshotGeneration: 7,
  serverReadTimeEpochMillis: createdAt.getTime(),
  layout: {
    kind: "present",
    ownerUid,
    generation: 7,
    miniHomeId: "private-home-name-id",
    name: "<script>alert('private')</script>",
    placedPlantCount: 1,
    placementCount: 2,
    revision: 4,
    expectedRevision: 3,
    idempotencyKey: "private-layout-operation",
    requestHash: "b".repeat(64),
    updatedAtEpochMillis: createdAt.getTime(),
    placements: [
      {
        placementId: "private-plant-placement",
        ownerUid,
        miniHomeId: "private-home-name-id",
        layoutRevision: 4,
        plantId: "personal-plant-id",
        itemId: null,
        normalizedX: 0.1,
        normalizedY: 0.125,
        zIndex: 0,
        revision: 4,
        expectedRevision: 3,
        idempotencyKey: "private-layout-operation",
        updatedAtEpochMillis: createdAt.getTime(),
      },
      {
        placementId: "private-item-placement",
        ownerUid,
        miniHomeId: "private-home-name-id",
        layoutRevision: 4,
        plantId: null,
        itemId: "public-decor",
        normalizedX: 0.3,
        normalizedY: 0.375,
        zIndex: 1,
        revision: 4,
        expectedRevision: 3,
        idempotencyKey: "private-layout-operation",
        updatedAtEpochMillis: createdAt.getTime(),
      },
    ],
  },
  inventory: {
    contractVersion: 3,
    ownerUid,
    catalog: [{
      itemId: "public-decor",
      name: "Public pot <unsafe>",
      description: "Public catalog copy",
      category: "DECORATION",
      mediaIdentity: {
        path: `catalog-assets/public-decor/${"c".repeat(64)}.webp`,
        sha256: "c".repeat(64),
        byteSize: 1234,
        mimeType: "image/webp",
        width: 128,
        height: 128,
        mediaRevision: 1,
      },
      acquisitionCondition: null,
      revision: 2,
      updatedAtEpochMillis: createdAt.getTime(),
    }],
    owned: [],
    registeredPlantCount: 1,
    partial: false,
    loadedAtEpochMillis: createdAt.getTime(),
    inventoryGeneration: 2,
    snapshotHash: "d".repeat(64),
  },
  plants: [{
    plantId: "personal-plant-id",
    ownerUid,
    displayName: "Private Monstera",
    representativePhotoPath: "plant-photos/share-owner/personal-plant-id/private.webp",
    revision: 9,
    updatedAtEpochMillis: createdAt.getTime(),
  }],
};

class RecordingStore implements MiniHomeShareStore {
  creates: unknown[] = [];
  revokes: unknown[] = [];
  revokedAt: string | null = null;
  publicResult: PublicMiniHomeShareSnapshot | null = null;
  result: CreateMiniHomeShareResult = {
    shareId: "s".repeat(43),
    url: `https://example.test/publicMiniHomeShare?token=${"t".repeat(43)}`,
    sourceRevision: 4,
    createdAt: createdAt.toISOString(),
    expiresAt: new Date(createdAt.getTime() + MINI_HOME_SHARE_LIFETIME_MILLIS).toISOString(),
  };

  async create(command: Parameters<MiniHomeShareStore["create"]>[0]): Promise<CreateMiniHomeShareResult> {
    this.creates.push(command);
    return this.result;
  }

  async revoke(command: Parameters<MiniHomeShareStore["revoke"]>[0]): Promise<string> {
    this.revokes.push(command);
    this.revokedAt ??= command.now.toISOString();
    return this.revokedAt;
  }

  async loadPublic(): Promise<PublicMiniHomeShareSnapshot | null> {
    return this.publicResult;
  }
}

test("create validates auth, exact operation envelope, positive JS-safe revision, and a 32-byte secret", async () => {
  const invalid: readonly [unknown, unknown, string][] = [
    [null, { operationId: "share-operation-0001", expectedRevision: 4 }, "auth"],
    [{ uid: ownerUid }, { operationId: "short", expectedRevision: 4 }, "operationId"],
    [{ uid: ownerUid }, { operationId: "share-operation-0001", expectedRevision: 0 }, "expectedRevision"],
    [{ uid: ownerUid }, { operationId: "share-operation-0001", expectedRevision: Number.MAX_SAFE_INTEGER + 1 }, "expectedRevision"],
    [{ uid: ownerUid }, { operationId: "share-operation-0001", expectedRevision: 4, ownerUid }, "request"],
  ];
  for (const [auth, input, field] of invalid) {
    const store = new RecordingStore();
    await assert.rejects(
      () => executeCreateMiniHomeShareLink(auth as never, input, store, key, createdAt, "https://example.test/publicMiniHomeShare"),
      (error: unknown) => error instanceof MiniHomeShareError && error.details?.field === field,
    );
    assert.equal(store.creates.length, 0);
  }
  await assert.rejects(
    () => executeCreateMiniHomeShareLink({ uid: ownerUid }, { operationId: "share-operation-0001", expectedRevision: 4 }, new RecordingStore(), "too-short", createdAt, "https://example.test/publicMiniHomeShare"),
    (error: unknown) => error instanceof MiniHomeShareError && error.code === "failed-precondition",
  );
});

test("create derives owner server-side and passes a fixed 30-day envelope", async () => {
  const store = new RecordingStore();
  const result = await executeCreateMiniHomeShareLink(
    { uid: ownerUid },
    { operationId: "share-operation-0001", expectedRevision: 4 },
    store,
    key,
    createdAt,
    "https://example.test/publicMiniHomeShare",
  );
  assert.deepEqual(result, store.result);
  assert.deepEqual(store.creates, [{
    ownerUid,
    operationId: "share-operation-0001",
    expectedRevision: 4,
    tokenKey: key,
    now: createdAt,
    publicEndpoint: "https://example.test/publicMiniHomeShare",
  }]);
  assert.equal(Date.parse(result.expiresAt) - Date.parse(result.createdAt), 30 * 24 * 60 * 60 * 1000);
});

test("domain-separated HMAC identity is deterministic for replay and unique across operation and projection", () => {
  const first = deriveMiniHomeShareIdentity(key, ownerUid, "share-operation-0001", "projection-identity-a");
  const replay = deriveMiniHomeShareIdentity(key, ownerUid, "share-operation-0001", "projection-identity-a");
  const distinctOperation = deriveMiniHomeShareIdentity(key, ownerUid, "share-operation-0002", "projection-identity-a");
  const distinctProjection = deriveMiniHomeShareIdentity(key, ownerUid, "share-operation-0001", "projection-identity-b");
  assert.deepEqual(first, replay);
  assert.notEqual(first.token, distinctOperation.token);
  assert.notEqual(first.token, distinctProjection.token);
  assert.match(first.token, /^[A-Za-z0-9_-]{43}$/);
  assert.match(first.tokenHash, /^[a-f0-9]{64}$/);
  assert.equal(JSON.stringify({ shareId: first.shareId, tokenHash: first.tokenHash }).includes(first.token), false);
});

test("sanitizer emits bounded 5x4 static placements and recursively omits private identity", () => {
  const identity = deriveMiniHomeShareIdentity(key, ownerUid, "share-operation-0001", snapshot.snapshotToken);
  const publicSnapshot = sanitizeMiniHomeShareSnapshot(snapshot, identity.shareId, createdAt);
  assert.equal(publicSnapshot.sourceRevision, 4);
  assert.deepEqual(publicSnapshot.grid, { columns: 5, rows: 4, projection: "isometric" });
  assert.equal(publicSnapshot.placements.length, 2);
  assert.deepEqual(publicSnapshot.placements[0], {
    kind: "plant",
    ordinal: 1,
    style: { variant: 1, scale: 1 },
    position: { x: 0.1, y: 0.125, z: 0 },
  });
  assert.equal(publicSnapshot.placements[1]?.kind, "catalog-item");
  const serialized = JSON.stringify(publicSnapshot);
  for (const forbidden of [
    ownerUid, "private-home-name-id", "personal-plant-id", "Private Monstera", "private.webp",
    "private-layout-operation", "requestHash", "ownerUid", "miniHomeId", "plantId", "operationId", "token",
  ]) assert.equal(serialized.includes(forbidden), false, forbidden);
  assert.ok(serialized.includes("public-decor"));
  assert.ok(serialized.includes("catalog-assets/public-decor/"));

  const tooMany = structuredClone(snapshot) as MiniHomeSnapshot;
  if (tooMany.layout.kind !== "present") throw new Error("fixture");
  (tooMany.layout.placements as unknown[]).push(...Array.from({ length: 19 }, () => tooMany.layout.kind === "present" ? tooMany.layout.placements[0] : null));
  assert.throws(() => sanitizeMiniHomeShareSnapshot(tooMany, identity.shareId, createdAt), MiniHomeShareError);
});

test("HTML renderer escapes bounded catalog fields and ships no script, tracker, or discovery surface", () => {
  const identity = deriveMiniHomeShareIdentity(key, ownerUid, "share-operation-0001", snapshot.snapshotToken);
  const payload = sanitizeMiniHomeShareSnapshot(snapshot, identity.shareId, createdAt);
  const html = renderPublicMiniHomeShareHtml(payload);
  assert.ok(html.includes("Public pot &lt;unsafe&gt;"));
  assert.equal(html.includes("Public pot <unsafe>"), false);
  assert.equal(/<script|https?:\/\/|og:|twitter:|<iframe/i.test(html), false);
  assert.ok(html.includes("grid-template-columns:repeat(5"));
  assert.ok(html.includes("data-projection=\"isometric\""));
});

test("public GET serves active JSON and escaped HTML with hardened headers and uniform 404s", async () => {
  const identity = deriveMiniHomeShareIdentity(key, ownerUid, "share-operation-0001", snapshot.snapshotToken);
  const payload = sanitizeMiniHomeShareSnapshot(snapshot, identity.shareId, createdAt);
  const store = new RecordingStore();
  store.publicResult = payload;
  const handler = createPublicMiniHomeShareHandler(store, () => createdAt);
  const json = await invokePublic(handler, `/?token=${identity.token}`, "application/json");
  assert.equal(json.status, 200);
  assert.deepEqual(await json.json(), payload);
  assert.equal(json.headers.get("cache-control"), "no-store");
  assert.equal(json.headers.get("x-content-type-options"), "nosniff");
  assert.equal(json.headers.get("referrer-policy"), "no-referrer");
  assert.match(json.headers.get("content-security-policy") ?? "", /default-src 'none'/);

  const html = await invokePublic(handler, `/?token=${identity.token}`, "text/html");
  assert.equal(html.status, 200);
  assert.ok((await html.text()).includes("Public pot &lt;unsafe&gt;"));

  store.publicResult = null;
  const bodies = [];
  for (const path of ["/", "/?token=bad", `/?token=${"u".repeat(43)}`]) {
    const response = await invokePublic(handler, path, "application/json");
    assert.equal(response.status, 404);
    bodies.push(await response.text());
  }
  assert.deepEqual(new Set(bodies).size, 1);
});

test("revoke validates owner and share id, then returns only the store-authoritative timestamp", async () => {
  const store = new RecordingStore();
  await assert.rejects(
    () => executeRevokeMiniHomeShareLink(null, { shareId: "s".repeat(43) }, store, createdAt),
    (error: unknown) => error instanceof MiniHomeShareError && error.code === "unauthenticated",
  );
  await assert.rejects(
    () => executeRevokeMiniHomeShareLink({ uid: ownerUid }, { shareId: "../private" }, store, createdAt),
    (error: unknown) => error instanceof MiniHomeShareError && error.code === "invalid-argument",
  );
  const first = await executeRevokeMiniHomeShareLink(
    { uid: ownerUid }, { shareId: "s".repeat(43) }, store, createdAt,
  );
  const replay = await executeRevokeMiniHomeShareLink(
    { uid: ownerUid }, { shareId: "s".repeat(43) }, store, new Date(createdAt.getTime() + 20_000),
  );
  assert.deepEqual(first, { shareId: "s".repeat(43), revokedAt: createdAt.toISOString() });
  assert.deepEqual(replay, first);
  assert.deepEqual(Object.keys(first).sort(), ["revokedAt", "shareId"]);
  assert.equal(store.revokes.length, 2);
});

async function invokePublic(
  handler: ReturnType<typeof createPublicMiniHomeShareHandler>,
  path: string,
  accept: string,
): Promise<Response> {
  const app = express();
  app.get("/", (request, response) => { void handler(request, response); });
  const server = app.listen(0);
  await new Promise<void>((resolve) => server.once("listening", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("HTTP test server did not bind");
  try {
    const response = await fetch(`http://127.0.0.1:${address.port}${path}`, { headers: { accept } });
    await response.clone().arrayBuffer();
    return response;
  } finally {
    await new Promise<void>((resolve, reject) => {
      (server as Server).close((error) => error === undefined ? resolve() : reject(error));
    });
  }
}
