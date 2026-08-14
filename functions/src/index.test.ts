import assert from "node:assert/strict";
import type { Server } from "node:http";
import test from "node:test";
import { getAppCheck } from "firebase-admin/app-check";
import express from "express";
import { identifyPlant } from "./index.js";

declare global {
  namespace Express {
    interface Request {
      readonly rawBody: Buffer;
    }
  }
}

async function invokeIdentifyPlant(appCheckToken?: string): Promise<Response> {
  const app = express();
  app.use(express.json());
  app.post("/", (request, response) => identifyPlant(request, response));
  const server = app.listen(0);
  await new Promise<void>((resolve) => server.once("listening", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("Callable test server did not bind a TCP port");
  try {
    return await fetch(`http://127.0.0.1:${address.port}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...(appCheckToken === undefined ? {} : { "X-Firebase-AppCheck": appCheckToken }),
      },
      body: JSON.stringify({ data: {} }),
    });
  } finally {
    await closeServer(server);
  }
}

async function closeServer(server: Server): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    server.close((error) => error === undefined ? resolve() : reject(error));
  });
}

test("compiled identifyPlant endpoint rejects a request with missing App Check", async () => {
  // Given / When
  const response = await invokeIdentifyPlant();

  // Then
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), {
    error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
  });
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
    const response = await invokeIdentifyPlant("invalid.fixture.token");

    // Then
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), {
      error: { message: "Unauthenticated", status: "UNAUTHENTICATED" },
    });
  } finally {
    appCheck.verifyToken = verifyToken;
  }
});
