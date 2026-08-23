import assert from "node:assert/strict";
import type { Server } from "node:http";
import test from "node:test";
import { getAppCheck } from "firebase-admin/app-check";
import { getAuth } from "firebase-admin/auth";
import express from "express";
import {
  acquireInventoryItem,
  applyRevisionedOwnerWrite,
  beginAppleSignIn,
  completeAppleSignIn,
  completeWatering,
  confirmNotificationOpened,
  createCatalogProjectionWriteHandler,
  createMiniHomeShareLink,
  createLoadInventoryCallable,
  ensureWateringNotificationSettings,
  identifyPlant,
  loadInventory,
  loadMiniHomeLayout,
  loadMiniHomeSnapshot,
  reconcileWateringNotificationTimezone,
  refreshWeather,
  revokeMiniHomeShareLink,
  registerNotificationEndpoint,
  saveMiniHomeLayout,
  searchWeatherRegions,
  setManualWeatherRegion,
  setWeatherLocationConsent,
  unregisterNotificationEndpoint,
  updateAccountProfile,
  updateWateringNotificationSettings,
  updateWeatherAlerts,
} from "./index.js";
import { inventorySnapshotHash, type InventorySnapshot, type InventoryStore } from "./inventory.js";

declare global {
  namespace Express {
    interface Request {
      readonly rawBody: Buffer;
    }
  }
}

type CallableHandler = (
  request: Parameters<typeof identifyPlant>[0],
  response: Parameters<typeof identifyPlant>[1],
) => void | Promise<void>;

async function invokeCallable(
  handler: CallableHandler,
  appCheckToken?: string,
  data: unknown = {},
  authToken?: string,
): Promise<Response> {
  const app = express();
  app.use(express.json());
  app.post("/", (request, response) => handler(request, response));
  const server = app.listen(0);
  await new Promise<void>((resolve) => server.once("listening", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("Callable test server did not bind a TCP port");
  try {
    const response = await fetch(`http://127.0.0.1:${address.port}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...(appCheckToken === undefined ? {} : { "X-Firebase-AppCheck": appCheckToken }),
        ...(authToken === undefined ? {} : { authorization: `Bearer ${authToken}` }),
      },
      body: JSON.stringify({ data }),
    });
    await response.clone().arrayBuffer();
    return response;
  } finally {
    await closeServer(server);
  }
}

async function closeServer(server: Server): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    server.close((error) => error === undefined ? resolve() : reject(error));
  });
}

test("catalog projection trigger surfaces failure so the exact event retry can converge", async () => {
  let attempts = 0;
  const handler = createCatalogProjectionWriteHandler({
    async rebuild() {
      attempts += 1;
      if (attempts === 1) throw new Error("injected catalog rebuild failure");
    },
  });
  await assert.rejects(() => handler(), /injected catalog rebuild failure/);
  await handler();
  assert.equal(attempts, 2);
});

test("all owner watering mini-home and Apple callable boundaries reject missing App Check first", async () => {
  const endpoints: CallableHandler[] = [
    (request, response) => applyRevisionedOwnerWrite(request, response),
    (request, response) => completeWatering(request, response),
    (request, response) => loadMiniHomeLayout(request, response),
    (request, response) => loadMiniHomeSnapshot(request, response),
    (request, response) => createMiniHomeShareLink(request, response),
    (request, response) => revokeMiniHomeShareLink(request, response),
    (request, response) => saveMiniHomeLayout(request, response),
    (request, response) => loadInventory(request, response),
    (request, response) => acquireInventoryItem(request, response),
    (request, response) => beginAppleSignIn(request, response),
    (request, response) => completeAppleSignIn(request, response),
  ];
  for (const endpoint of endpoints) {
    const response = await invokeCallable(endpoint, undefined, { deliberately: "invalid" });
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), {
      error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
    });
  }
});

test("valid debug App Check reaches application validation on hardened callables", async () => {
  const appCheck = getAppCheck();
  const auth = getAuth();
  const verifyAppCheckToken = appCheck.verifyToken;
  const verifyAuthToken = auth.verifyIdToken;
  appCheck.verifyToken = async () => ({ appId: "debug-app", token: {} }) as never;
  auth.verifyIdToken = async () => ({ uid: "debug-user" }) as never;
  const environment = {
    APPLE_CLIENT_ID: process.env.APPLE_CLIENT_ID,
    APPLE_REDIRECT_URI: process.env.APPLE_REDIRECT_URI,
    APPLE_TEAM_ID: process.env.APPLE_TEAM_ID,
    APPLE_KEY_ID: process.env.APPLE_KEY_ID,
    APPLE_ABUSE_HASH_KEY: process.env.APPLE_ABUSE_HASH_KEY,
  };
  Object.assign(process.env, {
    APPLE_CLIENT_ID: "com.planterior.helper.signin",
    APPLE_REDIRECT_URI: "https://us-central1-demo-planterior.cloudfunctions.net/appleOAuthCallback",
    APPLE_TEAM_ID: "TEAM123456",
    APPLE_KEY_ID: "KEY123456",
    APPLE_ABUSE_HASH_KEY: "test-only-apple-abuse-hmac-key-1234567890",
  });
  try {
    const endpoints: CallableHandler[] = [
      (request, response) => applyRevisionedOwnerWrite(request, response),
      (request, response) => completeWatering(request, response),
      (request, response) => loadMiniHomeLayout(request, response),
      (request, response) => loadMiniHomeSnapshot(request, response),
      (request, response) => saveMiniHomeLayout(request, response),
      (request, response) => loadInventory(request, response),
      (request, response) => acquireInventoryItem(request, response),
      (request, response) => beginAppleSignIn(request, response),
      (request, response) => completeAppleSignIn(request, response),
    ];
    for (const endpoint of endpoints) {
      const response = await invokeCallable(
        endpoint,
        "debug.valid.token",
        { deliberately: "invalid" },
        "debug.auth.token",
      );
      assert.equal(response.status, 400);
      const body = await response.json() as { error?: { status?: string } };
      assert.equal(body.error?.status, "INVALID_ARGUMENT");
    }
    const miniHomeValidation = await invokeCallable(
      (request, response) => saveMiniHomeLayout(request, response),
      "debug.valid.token",
      {
        expectedOwnerUid: "debug-user",
        miniHomeId: "home-a",
        expectedRevision: 0,
        idempotencyKey: "mini-home-validation-0001",
        name: " invalid ",
        placements: [],
      },
      "debug.auth.token",
    );
    assert.equal(miniHomeValidation.status, 400);
    const validationBody = await miniHomeValidation.json() as {
      error?: { status?: string; details?: { reason?: string; field?: string } };
    };
    assert.deepEqual(validationBody.error, {
      message: "name must be NFC, safe, and contain 1 to 100 Unicode code points",
      status: "INVALID_ARGUMENT",
      details: { reason: "INVALID_REQUEST", field: "name" },
    });
  } finally {
    appCheck.verifyToken = verifyAppCheckToken;
    auth.verifyIdToken = verifyAuthToken;
    for (const [name, value] of Object.entries(environment)) {
      if (value === undefined) delete process.env[name]; else process.env[name] = value;
    }
  }
});

test("share callable factories enforce App Check and bind only the create secret", () => {
  const createEndpoint = (createMiniHomeShareLink as unknown as { __endpoint: { callableTrigger: unknown; secretEnvironmentVariables?: readonly { key: string }[] } }).__endpoint;
  const revokeEndpoint = (revokeMiniHomeShareLink as unknown as { __endpoint: { callableTrigger: unknown; secretEnvironmentVariables?: readonly { key: string }[] } }).__endpoint;
  assert.ok(createEndpoint.callableTrigger !== undefined);
  assert.ok(revokeEndpoint.callableTrigger !== undefined);
  assert.deepEqual(createEndpoint.secretEnvironmentVariables, [{ key: "MINI_HOME_SHARE_TOKEN_KEY" }]);
  assert.equal(revokeEndpoint.secretEnvironmentVariables, undefined);
});

test("real loadInventory callable serializes the shared versioned catalog and owned contract", async () => {
  const appCheck = getAppCheck();
  const auth = getAuth();
  const verifyAppCheckToken = appCheck.verifyToken;
  const verifyAuthToken = auth.verifyIdToken;
  appCheck.verifyToken = async () => ({ appId: "debug-app", token: {} }) as never;
  auth.verifyIdToken = async () => ({ uid: "user-a" }) as never;
  const store: InventoryStore = {
    async load(ownerUid) {
      const content: Pick<InventorySnapshot, "ownerUid" | "catalog" | "owned" | "registeredPlantCount" | "partial"> = {
        ownerUid,
        catalog: [{
          itemId: "public-background",
          name: "공개 배경",
          description: "공개된 무료 배경",
          category: "BACKGROUND",
          mediaIdentity: {
            path: `catalog-assets/public-background/${"a".repeat(64)}.webp`,
            sha256: "a".repeat(64), byteSize: 3, mimeType: "image/webp",
            width: 96, height: 64, mediaRevision: 1,
          },
          acquisitionCondition: null,
          revision: 3,
          updatedAtEpochMillis: 1,
        }],
        owned: [{
          itemId: "deleted-decoration",
          acquiredAtEpochMillis: 2,
          applied: true,
          revision: 4,
          availability: "UNAVAILABLE",
          catalogSnapshot: {
            name: "삭제된 장식",
            category: "DECORATION",
            mediaIdentity: {
              path: `catalog-assets/deleted-decoration/${"b".repeat(64)}.webp`,
              sha256: "b".repeat(64), byteSize: 3, mimeType: "image/webp",
              width: 96, height: 64, mediaRevision: 1,
            },
            catalogRevision: 3,
          },
        }],
        registeredPlantCount: 1,
        partial: true,
      };
      return {
        contractVersion: 3,
        ...content,
        loadedAtEpochMillis: 3,
        inventoryGeneration: 1,
        snapshotHash: inventorySnapshotHash(content),
      };
    },
    async acquire() {
      throw new Error("not used");
    },
  };
  try {
    const callable = createLoadInventoryCallable(store);
    const response = await invokeCallable(
      (request, reply) => callable(request, reply),
      "debug.valid.token",
      { expectedOwnerUid: "user-a" },
      "debug.auth.token",
    );
    assert.equal(response.status, 200);
    const body = await response.json() as { result: Record<string, unknown> };
    assert.deepEqual(Object.keys(body.result).sort(), [
      "catalog",
      "contractVersion",
      "inventoryGeneration",
      "loadedAtEpochMillis",
      "owned",
      "ownerUid",
      "partial",
      "registeredPlantCount",
      "snapshotHash",
    ]);
    assert.equal(body.result.contractVersion, 3);
    assert.ok(Array.isArray(body.result.catalog));
    assert.ok(Array.isArray(body.result.owned));
  } finally {
    appCheck.verifyToken = verifyAppCheckToken;
    auth.verifyIdToken = verifyAuthToken;
  }
});

test("compiled identifyPlant endpoint rejects a request with missing App Check", async () => {
  // Given / When
  const response = await invokeCallable(identifyPlant);

  // Then
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), {
    error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
  });
});

test("compiled notification mutation endpoints reject requests with missing App Check", async () => {
  const endpoints: CallableHandler[] = [
    (request, response) => confirmNotificationOpened(request, response),
    (request, response) => registerNotificationEndpoint(request, response),
    (request, response) => unregisterNotificationEndpoint(request, response),
    (request, response) => ensureWateringNotificationSettings(request, response),
    (request, response) => reconcileWateringNotificationTimezone(request, response),
    (request, response) => updateAccountProfile(request, response),
    (request, response) => updateWateringNotificationSettings(request, response),
  ];
  for (const endpoint of endpoints) {
    const response = await invokeCallable(endpoint);
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), {
      error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
    });
  }
});

test("compiled weather endpoints reject requests with missing App Check", async () => {
  const endpoints: CallableHandler[] = [
    (request, response) => refreshWeather(request, response),
    (request, response) => searchWeatherRegions(request, response),
    (request, response) => setManualWeatherRegion(request, response),
    (request, response) => setWeatherLocationConsent(request, response),
    (request, response) => updateWeatherAlerts(request, response),
  ];
  for (const endpoint of endpoints) {
    const response = await invokeCallable(endpoint);
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), {
      error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
    });
  }
});

test("hardened callable boundaries reject invalid App Check before application validation", async () => {
  const appCheck = getAppCheck();
  const verifyToken = appCheck.verifyToken;
  appCheck.verifyToken = async () => {
    throw new Error("invalid App Check fixture");
  };
  try {
    const endpoints: CallableHandler[] = [
      (request, response) => applyRevisionedOwnerWrite(request, response),
      (request, response) => completeWatering(request, response),
      (request, response) => loadMiniHomeLayout(request, response),
      (request, response) => loadMiniHomeSnapshot(request, response),
      (request, response) => createMiniHomeShareLink(request, response),
      (request, response) => revokeMiniHomeShareLink(request, response),
      (request, response) => saveMiniHomeLayout(request, response),
      (request, response) => loadInventory(request, response),
      (request, response) => acquireInventoryItem(request, response),
      (request, response) => beginAppleSignIn(request, response),
      (request, response) => completeAppleSignIn(request, response),
    ];
    for (const endpoint of endpoints) {
      const response = await invokeCallable(endpoint, "invalid.fixture.token", { deliberately: "invalid" });
      assert.equal(response.status, 401);
      assert.deepEqual(await response.json(), {
        error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
      });
    }
  } finally {
    appCheck.verifyToken = verifyToken;
  }
});

test("compiled identifyPlant endpoint rejects a request with invalid App Check", async () => {
  // Given
  const appCheck = getAppCheck();
  const verifyToken = appCheck.verifyToken;
  appCheck.verifyToken = async () => {
    throw new Error("invalid App Check fixture");
  };

  try {
    // When
    const response = await invokeCallable(identifyPlant, "invalid.fixture.token");

    // Then
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), {
      error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
    });
  } finally {
    appCheck.verifyToken = verifyToken;
  }
});
