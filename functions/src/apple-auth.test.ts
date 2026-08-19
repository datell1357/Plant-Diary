import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import {
  AppleAuthError,
  executeAppleCallback,
  executeBeginAppleSignIn,
  executeCompleteAppleSignIn,
  type AppleOAuthSession,
  type AppleSessionStore,
  type AppleTokenExchange,
} from "./apple-auth.js";

const now = new Date("2026-08-12T12:00:00.000Z");
const state = "s".repeat(43);
const nonceHash = "n".repeat(43);
const verifier = "v".repeat(43);
const challenge = createHash("sha256").update(verifier).digest("base64url");
const abuseKeyHash = "a".repeat(64);
const callbackIdToken = "callback-header.callback-payload.callback-signature";
const firstLoginUser = JSON.stringify({
  name: { firstName: "Planterior", lastName: "Tester" },
  email: "relay@example.com",
});

class MemoryStore implements AppleSessionStore {
  sessions = new Map<string, AppleOAuthSession>();
  async create(session: AppleOAuthSession): Promise<void> { this.sessions.set(session.id, session); }
  async findByStateHash(hash: string): Promise<AppleOAuthSession | null> {
    return [...this.sessions.values()].find((value) => value.stateHash === hash) ?? null;
  }
  async attachCode(id: string, code: string, at: Date): Promise<void> {
    const session = this.sessions.get(id);
    if (session === undefined || session.authorizationCode !== null) throw new AppleAuthError("already-exists", "callback already recorded");
    if (session.expiresAt <= at) throw new AppleAuthError("failed-precondition", "session expired");
    this.sessions.set(id, { ...session, authorizationCode: code });
  }
  async consume(id: string, expectedChallenge: string, at: Date): Promise<AppleOAuthSession> {
    const session = this.sessions.get(id);
    if (session === undefined || session.usedAt !== null) throw new AppleAuthError("already-exists", "session already consumed");
    if (session.expiresAt <= at || session.codeChallenge !== expectedChallenge || session.authorizationCode === null) {
      throw new AppleAuthError("failed-precondition", "session invalid");
    }
    this.sessions.set(id, { ...session, usedAt: at });
    return session;
  }
}

const config = {
  clientId: "com.planterior.helper.signin",
  redirectUri: "https://us-central1-demo-planterior.cloudfunctions.net/appleOAuthCallback",
};

async function prepared(store: MemoryStore): Promise<string> {
  const start = await executeBeginAppleSignIn(
    { state, nonceHash, codeChallenge: challenge },
    store,
    config,
    abuseKeyHash,
    now,
    () => "session_12345678",
  );
  assert.match(start.authorizationUrl, /^https:\/\/appleid\.apple\.com\/auth\/authorize\?/);
  const callback = await executeAppleCallback({ state, code: "authorization-code" }, store, now);
  assert.equal(callback.redirectUri, `planterior://auth/apple?sessionId=${start.sessionId}&state=${state}`);
  return start.sessionId;
}

const successExchange: AppleTokenExchange = {
  async exchange(input) {
    assert.equal(input.code, "authorization-code");
    assert.equal(input.codeVerifier, verifier);
    assert.equal(input.expectedNonceHash, nonceHash);
    return { idToken: "header.payload.signature" };
  },
};

test("Apple start callback and completion return a server-validated id token once", async () => {
  const store = new MemoryStore();
  const sessionId = await prepared(store);
  const result = await executeCompleteAppleSignIn({ sessionId, state, codeVerifier: verifier }, store, successExchange, now);
  assert.equal(result.idToken, "header.payload.signature");
});

test("generated authorize request accepts the standard first-login form_post and completes with only the exchanged token", async () => {
  const store = new MemoryStore();
  const start = await executeBeginAppleSignIn(
    { state, nonceHash, codeChallenge: challenge },
    store,
    config,
    abuseKeyHash,
    now,
    () => "session_standard_first_login",
  );
  const authorize = new URL(start.authorizationUrl);
  assert.equal(authorize.searchParams.get("response_type"), "code id_token");
  assert.equal(authorize.searchParams.get("response_mode"), "form_post");
  assert.equal(authorize.searchParams.get("scope"), "name email");
  assert.equal(authorize.searchParams.get("state"), state);
  assert.equal(authorize.searchParams.get("nonce"), nonceHash);

  const callback = await executeAppleCallback(
    {
      state: authorize.searchParams.get("state"),
      code: "authorization-code",
      id_token: callbackIdToken,
      user: firstLoginUser,
    },
    store,
    now,
  );
  assert.equal(
    callback.redirectUri,
    `planterior://auth/apple?sessionId=${start.sessionId}&state=${state}`,
  );
  assert.equal(JSON.stringify(store.sessions.get(start.sessionId)).includes("relay@example.com"), false);
  const result = await executeCompleteAppleSignIn(
    { sessionId: start.sessionId, state, codeVerifier: verifier },
    store,
    successExchange,
    now,
  );
  assert.deepEqual(result, { idToken: "header.payload.signature" });
});

test("standard subsequent callback accepts code and id_token without first-login user metadata", async () => {
  const store = new MemoryStore();
  const start = await executeBeginAppleSignIn(
    { state, nonceHash, codeChallenge: challenge },
    store,
    config,
    abuseKeyHash,
    now,
    () => "session_standard_subsequent",
  );
  await executeAppleCallback(
    { state, code: "authorization-code", id_token: callbackIdToken },
    store,
    now,
  );
  assert.equal(store.sessions.get(start.sessionId)?.authorizationCode, "authorization-code");
});

test("provider error callback is state-bound and rejects mixed success and oversized error payloads", async () => {
  const store = new MemoryStore();
  const start = await executeBeginAppleSignIn(
    { state, nonceHash, codeChallenge: challenge },
    store,
    config,
    abuseKeyHash,
    now,
    () => "session_provider_error",
  );
  const result = await executeAppleCallback(
    { state, error: "user_cancelled_authorize", error_description: "cancelled" },
    store,
    now,
  );
  assert.equal(result.redirectUri, "planterior://auth/apple?error=cancelled");
  assert.equal(store.sessions.get(start.sessionId)?.authorizationCode, null);
  await assert.rejects(
    executeAppleCallback(
      { state, code: "authorization-code", error: "access_denied" },
      store,
      now,
    ),
    (error: unknown) => error instanceof AppleAuthError && error.code === "invalid-argument",
  );
  await assert.rejects(
    executeAppleCallback(
      { state, error: "access_denied", error_description: "x".repeat(2049) },
      store,
      now,
    ),
    (error: unknown) => error instanceof AppleAuthError && error.code === "invalid-argument",
  );
  await assert.rejects(
    executeAppleCallback(
      { state: "x".repeat(43), error: "access_denied" },
      store,
      now,
    ),
    (error: unknown) => error instanceof AppleAuthError && error.code === "not-found",
  );
});

test("callback strictly rejects malformed oversized or unexpected id_token and user fields before attachment", async () => {
  const malformedPayloads: readonly Readonly<Record<string, unknown>>[] = [
    { state, code: "authorization-code", id_token: "not-a-jwt" },
    { state, code: "authorization-code", id_token: `a.${"b".repeat(16_385)}.c` },
    { state, code: "authorization-code", id_token: callbackIdToken, user: "{" },
    { state, code: "authorization-code", id_token: callbackIdToken, user: "x".repeat(8_193) },
    { state, code: "authorization-code", id_token: callbackIdToken, user: "[]" },
    {
      state,
      code: "authorization-code",
      id_token: callbackIdToken,
      user: '{"name":{"firstName":"A","lastName":"B"},"email":"first@example.com","email":"second@example.com"}',
    },
    {
      state,
      code: "authorization-code",
      id_token: callbackIdToken,
      user: JSON.stringify({ name: { firstName: "A", lastName: "B", role: "admin" }, email: "relay@example.com" }),
    },
    { state, code: "authorization-code", id_token: callbackIdToken, unknown: "field" },
    { state: [state, state], code: "authorization-code", id_token: callbackIdToken },
    { state, code: ["authorization-code", "authorization-code"], id_token: callbackIdToken },
  ];
  for (const [index, payload] of malformedPayloads.entries()) {
    const store = new MemoryStore();
    const start = await executeBeginAppleSignIn(
      { state, nonceHash, codeChallenge: challenge },
      store,
      config,
      abuseKeyHash,
      now,
      () => `session_malformed_${index}`,
    );
    await assert.rejects(
      executeAppleCallback(payload, store, now),
      (error: unknown) => error instanceof AppleAuthError && error.code === "invalid-argument",
    );
    assert.equal(store.sessions.get(start.sessionId)?.authorizationCode, null);
  }
});

test("callback rejects polluted object prototypes and duplicate callback replay", async () => {
  const polluted = Object.assign(Object.create({ admin: true }), {
    state,
    code: "authorization-code",
    id_token: callbackIdToken,
  }) as Record<string, unknown>;
  const pollutedStore = new MemoryStore();
  await executeBeginAppleSignIn(
    { state, nonceHash, codeChallenge: challenge },
    pollutedStore,
    config,
    abuseKeyHash,
    now,
    () => "session_polluted_callback",
  );
  await assert.rejects(
    executeAppleCallback(polluted, pollutedStore, now),
    (error: unknown) => error instanceof AppleAuthError && error.code === "invalid-argument",
  );

  const replayStore = new MemoryStore();
  await executeBeginAppleSignIn(
    { state, nonceHash, codeChallenge: challenge },
    replayStore,
    config,
    abuseKeyHash,
    now,
    () => "session_callback_replay",
  );
  const callback = { state, code: "authorization-code", id_token: callbackIdToken };
  await executeAppleCallback(callback, replayStore, now);
  await assert.rejects(
    executeAppleCallback(callback, replayStore, now),
    (error: unknown) => error instanceof AppleAuthError && error.code === "already-exists",
  );
});

test("wrong callback state and cryptographically verified exchange nonce remain rejected", async () => {
  const store = new MemoryStore();
  const start = await executeBeginAppleSignIn(
    { state, nonceHash, codeChallenge: challenge },
    store,
    config,
    abuseKeyHash,
    now,
    () => "session_wrong_state_nonce",
  );
  await assert.rejects(
    executeAppleCallback(
      { state: "x".repeat(43), code: "authorization-code", id_token: callbackIdToken },
      store,
      now,
    ),
    (error: unknown) => error instanceof AppleAuthError && error.code === "not-found",
  );
  await executeAppleCallback(
    { state, code: "authorization-code", id_token: callbackIdToken },
    store,
    now,
  );
  const wrongNonceExchange: AppleTokenExchange = {
    async exchange(input) {
      assert.equal(input.expectedNonceHash, nonceHash);
      throw new AppleAuthError("unauthenticated", "invalid-nonce");
    },
  };
  await assert.rejects(
    executeCompleteAppleSignIn(
      { sessionId: start.sessionId, state, codeVerifier: verifier },
      store,
      wrongNonceExchange,
      now,
    ),
    (error: unknown) => error instanceof AppleAuthError && error.message === "invalid-nonce",
  );
});

test("replayed Apple completion is rejected before token exchange", async () => {
  const store = new MemoryStore();
  const sessionId = await prepared(store);
  await executeCompleteAppleSignIn({ sessionId, state, codeVerifier: verifier }, store, successExchange, now);
  await assert.rejects(
    executeCompleteAppleSignIn({ sessionId, state, codeVerifier: verifier }, store, successExchange, now),
    (error: unknown) => error instanceof AppleAuthError && error.code === "already-exists",
  );
});

test("malformed callback wrong state expired session and wrong PKCE are rejected", async () => {
  const store = new MemoryStore();
  const sessionId = await prepared(store);
  await assert.rejects(executeAppleCallback({ state: "x".repeat(43), code: "forged" }, store, now));
  await assert.rejects(
    executeCompleteAppleSignIn({ sessionId, state, codeVerifier: "x".repeat(43) }, store, successExchange, now),
  );
  const expiredStore = new MemoryStore();
  const expiredId = await prepared(expiredStore);
  await assert.rejects(
    executeCompleteAppleSignIn({ sessionId: expiredId, state, codeVerifier: verifier }, expiredStore, successExchange, new Date(now.getTime() + 601_000)),
  );
});

test("expired callback cannot attach an authorization code and admission identity is mandatory", async () => {
  const store = new MemoryStore();
  const start = await executeBeginAppleSignIn(
    { state, nonceHash, codeChallenge: challenge },
    store,
    config,
    abuseKeyHash,
    now,
    () => "session_expired_callback",
  );
  await assert.rejects(
    executeAppleCallback(
      { state, code: "private-authorization-code" },
      store,
      new Date(now.getTime() + 600_001),
    ),
    (error: unknown) =>
      error instanceof AppleAuthError &&
      error.code === "failed-precondition" &&
      !error.message.includes("private-authorization-code"),
  );
  assert.equal(store.sessions.get(start.sessionId)?.authorizationCode, null);
  await assert.rejects(
    executeBeginAppleSignIn(
      { state, nonceHash, codeChallenge: challenge },
      store,
      config,
      "raw-ip-address",
      now,
    ),
    (error: unknown) => error instanceof AppleAuthError && error.code === "failed-precondition",
  );
});

test("issuer audience expiry and nonce verification failures from injectable exchange stay typed and redact tokens", async () => {
  for (const code of ["invalid-issuer", "invalid-audience", "expired-token", "invalid-nonce"] as const) {
    const store = new MemoryStore();
    const sessionId = await prepared(store);
    const exchange: AppleTokenExchange = { async exchange() { throw new AppleAuthError("unauthenticated", code); } };
    await assert.rejects(
      executeCompleteAppleSignIn({ sessionId, state, codeVerifier: verifier }, store, exchange, now),
      (error: unknown) => error instanceof AppleAuthError && error.message === code && !error.message.includes("authorization-code"),
    );
  }
});

test("strict input rejects unknown fields and callback errors never include provider payload", async () => {
  const store = new MemoryStore();
  await assert.rejects(
    executeBeginAppleSignIn(
      { state, nonceHash, codeChallenge: challenge, token: "leak" },
      store,
      config,
      abuseKeyHash,
      now,
    ),
  );
  await prepared(store);
  const result = await executeAppleCallback({ state, error: "access_denied", error_description: "private provider text" }, store, now);
  assert.equal(result.redirectUri, "planterior://auth/apple?error=cancelled");
  assert.equal(result.redirectUri.includes("private"), false);
});
