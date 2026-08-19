import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import {
  APPLE_SESSION_CLEANUP_LIMIT,
  APPLE_SESSION_RATE_LIMIT,
  FirestoreAppleSessionStore,
} from "./apple-auth-runtime.js";
import {
  AppleAuthError,
  executeAppleCallback,
  executeBeginAppleSignIn,
  executeCompleteAppleSignIn,
  type AppleOAuthSession,
} from "./apple-auth.js";

const projectId = "demo-planterior";
const now = new Date("2026-08-12T12:00:00.000Z");

function session(
  id: string,
  abuseKeyHash: string = "a".repeat(64),
  createdAt: Date = now,
): AppleOAuthSession {
  return {
    id,
    stateHash: id.padEnd(43, "s"),
    nonceHash: "n".repeat(43),
    codeChallenge: "c".repeat(43),
    authorizationCode: null,
    createdAt,
    expiresAt: new Date(createdAt.getTime() + 600_000),
    usedAt: null,
    abuseKeyHash,
  };
}

test("Apple session admission is transactionally bounded per keyed App Check and IP identity", async () => {
  const app = initializeApp({ projectId }, "apple-rate-limit-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreAppleSessionStore(firestore);
  try {
    await clear(firestore);
    for (let index = 0; index < APPLE_SESSION_RATE_LIMIT; index += 1) {
      await store.create(session(`session-rate-${index}`));
    }
    await assert.rejects(
      () => store.create(session("session-rate-rejected")),
      (error: unknown) => error instanceof AppleAuthError && error.code === "resource-exhausted",
    );
    await store.create(session("session-other-device", "b".repeat(64)));

    const rate = await firestore.doc(`appleAuthRateLimits/${"a".repeat(64)}`).get();
    assert.equal(rate.get("count"), APPLE_SESSION_RATE_LIMIT);
    assert.ok(rate.get("expiresAt") instanceof Timestamp);
    assert.deepEqual(Object.keys(rate.data() ?? {}).sort(), ["count", "expiresAt", "windowStartedAt"]);
    const stored = await firestore.doc("appleAuthSessions/session-rate-0").get();
    assert.equal(stored.get("abuseKeyHash"), "a".repeat(64));
    assert.equal(JSON.stringify(stored.data()).includes("127.0.0.1"), false);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("generated Apple authorize request accepts standard first-login callback and completes through Firestore once", async () => {
  const app = initializeApp({ projectId }, "apple-standard-callback-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreAppleSessionStore(firestore);
  const state = "s".repeat(43);
  const nonceHash = "n".repeat(43);
  const verifier = "v".repeat(43);
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  try {
    await clear(firestore);
    const start = await executeBeginAppleSignIn(
      { state, nonceHash, codeChallenge: challenge },
      store,
      {
        clientId: "com.planterior.helper.signin",
        redirectUri: "https://us-central1-demo-planterior.cloudfunctions.net/appleOAuthCallback",
      },
      "a".repeat(64),
      now,
      () => "session-standard-emulator",
    );
    const authorize = new URL(start.authorizationUrl);
    await executeAppleCallback(
      {
        state: authorize.searchParams.get("state"),
        code: "authorization-code",
        id_token: "callback.header.signature",
        user: JSON.stringify({
          name: { firstName: "Planterior", lastName: "Tester" },
          email: "relay@example.com",
        }),
      },
      store,
      now,
    );
    const result = await executeCompleteAppleSignIn(
      { sessionId: start.sessionId, state, codeVerifier: verifier },
      store,
      {
        async exchange(input) {
          assert.deepEqual(input, {
            code: "authorization-code",
            codeVerifier: verifier,
            expectedNonceHash: nonceHash,
          });
          return { idToken: "verified.header.signature" };
        },
      },
      now,
    );
    assert.deepEqual(result, { idToken: "verified.header.signature" });
    const stored = await firestore.doc(`appleAuthSessions/${start.sessionId}`).get();
    assert.equal(JSON.stringify(stored.data()).includes("relay@example.com"), false);
    await assert.rejects(
      executeAppleCallback(
        { state, code: "authorization-code", id_token: "callback.header.signature" },
        store,
        now,
      ),
      (error: unknown) => error instanceof AppleAuthError && error.code === "already-exists",
    );
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("Apple callback attachment and completion consume one session exactly once", async () => {
  const app = initializeApp({ projectId }, "apple-replay-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreAppleSessionStore(firestore);
  try {
    await clear(firestore);
    await store.create(session("session-replay"));
    await store.attachCode("session-replay", "authorization-code", now);

    const results = await Promise.allSettled([
      store.consume("session-replay", "c".repeat(43), now),
      store.consume("session-replay", "c".repeat(43), now),
    ]);
    assert.equal(results.filter((result) => result.status === "fulfilled").length, 1);
    const rejection = results.find((result) => result.status === "rejected");
    assert.ok(
      rejection?.status === "rejected" &&
        rejection.reason instanceof AppleAuthError &&
        rejection.reason.code === "already-exists",
    );
    assert.ok((await firestore.doc("appleAuthSessions/session-replay").get()).get("usedAt") instanceof Timestamp);
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

test("expired Apple session cleanup is indexed bounded and preserves fresh sessions", async () => {
  const app = initializeApp({ projectId }, "apple-cleanup-emulator");
  const firestore = getFirestore(app);
  const store = new FirestoreAppleSessionStore(firestore);
  try {
    await clear(firestore);
    const expiredAt = Timestamp.fromDate(new Date(now.getTime() - 1));
    const writes = Array.from({ length: APPLE_SESSION_CLEANUP_LIMIT + 5 }, (_, index) =>
      firestore.doc(`appleAuthSessions/expired-${String(index).padStart(3, "0")}`).set({
        expiresAt: expiredAt,
      }),
    );
    writes.push(firestore.doc("appleAuthSessions/fresh").set({
      expiresAt: Timestamp.fromDate(new Date(now.getTime() + 600_000)),
    }));
    await Promise.all(writes);

    assert.deepEqual(await store.cleanupExpired(now), {
      sessions: APPLE_SESSION_CLEANUP_LIMIT,
      rateLimits: 0,
    });
    assert.equal((await firestore.collection("appleAuthSessions").get()).size, 6);
    assert.deepEqual(await store.cleanupExpired(now), { sessions: 5, rateLimits: 0 });
    assert.equal((await firestore.collection("appleAuthSessions").get()).docs[0]?.id, "fresh");
  } finally {
    await clear(firestore);
    await deleteApp(app);
  }
});

async function clear(firestore: ReturnType<typeof getFirestore>): Promise<void> {
  for (const collection of ["appleAuthSessions", "appleAuthRateLimits"]) {
    const snapshot = await firestore.collection(collection).get();
    await Promise.all(snapshot.docs.map((document) => document.ref.delete()));
  }
}
