import assert from "node:assert/strict";
import type { Server } from "node:http";
import test from "node:test";
import { getAppCheck } from "firebase-admin/app-check";
import { getAuth } from "firebase-admin/auth";
import express from "express";
import {
  applyRevisionedOwnerWrite,
  beginAppleSignIn,
  completeAppleSignIn,
  completeWatering,
  confirmNotificationOpened,
  ensureWateringNotificationSettings,
  identifyPlant,
  loadMiniHomeLayout,
  reconcileWateringNotificationTimezone,
  refreshWeather,
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

test("all owner watering mini-home and Apple callable boundaries reject missing App Check first", async () => {
  const endpoints: CallableHandler[] = [
    (request, response) => applyRevisionedOwnerWrite(request, response),
    (request, response) => completeWatering(request, response),
    (request, response) => loadMiniHomeLayout(request, response),
    (request, response) => saveMiniHomeLayout(request, response),
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
      (request, response) => saveMiniHomeLayout(request, response),
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
      (request, response) => saveMiniHomeLayout(request, response),
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
