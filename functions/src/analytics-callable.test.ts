import assert from "node:assert/strict";
import type { Server } from "node:http";
import test from "node:test";
import { getAppCheck } from "firebase-admin/app-check";
import express from "express";
import {
  cleanupExpiredAnalytics,
  getAnalyticsConsent,
  recordAnalyticsEvent,
  setAnalyticsConsent,
} from "./index.js";

declare global {
  namespace Express {
    interface Request {
      readonly rawBody: Buffer;
    }
  }
}

type AnalyticsCallable = (
  request: Parameters<typeof getAnalyticsConsent>[0],
  response: Parameters<typeof getAnalyticsConsent>[1],
) => void | Promise<void>;

const analyticsCallables: readonly AnalyticsCallable[] = [
  (request, response) => getAnalyticsConsent(request, response),
  (request, response) => setAnalyticsConsent(request, response),
  (request, response) => recordAnalyticsEvent(request, response),
];

async function invoke(
  handler: AnalyticsCallable,
  appCheckToken?: string,
): Promise<Response> {
  const app = express();
  app.use(express.json());
  app.post("/", (request, response) => handler(request, response));
  const server = app.listen(0);
  await new Promise<void>((resolve) => server.once("listening", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("Analytics callable test server did not bind a TCP port");
  }
  try {
    const response = await fetch(`http://127.0.0.1:${address.port}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...(appCheckToken === undefined
          ? {}
          : { "X-Firebase-AppCheck": appCheckToken }),
      },
      body: JSON.stringify({ data: {} }),
    });
    await response.clone().arrayBuffer();
    return response;
  } finally {
    await close(server);
  }
}

async function close(server: Server): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    server.close((error) => (error === undefined ? resolve() : reject(error)));
  });
}

test("analytics callables enforce App Check before application validation", async () => {
  for (const callable of analyticsCallables) {
    const response = await invoke(callable);
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), {
      error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
    });
  }
});

test("analytics callables require Firebase Auth after valid App Check", async () => {
  const appCheck = getAppCheck();
  const verifyToken = appCheck.verifyToken;
  appCheck.verifyToken = async () =>
    ({ appId: "debug-app", token: {} }) as never;
  try {
    for (const callable of analyticsCallables) {
      const response = await invoke(callable, "debug.valid.token");
      assert.equal(response.status, 401);
      assert.deepEqual(await response.json(), {
        error: { message: "Sign-in is required", status: "UNAUTHENTICATED" },
      });
    }
  } finally {
    appCheck.verifyToken = verifyToken;
  }
});

test("analytics cleanup is a bounded hourly UTC schedule", () => {
  assert.equal(
    cleanupExpiredAnalytics.__endpoint.scheduleTrigger?.schedule,
    "every 60 minutes",
  );
  assert.equal(
    cleanupExpiredAnalytics.__endpoint.scheduleTrigger?.timeZone,
    "UTC",
  );
  for (const callable of [
    getAnalyticsConsent,
    setAnalyticsConsent,
    recordAnalyticsEvent,
  ]) {
    assert.ok(callable.__endpoint.callableTrigger !== undefined);
    assert.equal(callable.__endpoint.platform, "gcfv2");
  }
});
