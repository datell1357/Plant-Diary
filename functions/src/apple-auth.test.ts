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

class MemoryStore implements AppleSessionStore {
  sessions = new Map<string, AppleOAuthSession>();
  async create(session: AppleOAuthSession): Promise<void> { this.sessions.set(session.id, session); }
  async findByStateHash(hash: string): Promise<AppleOAuthSession | null> {
    return [...this.sessions.values()].find((value) => value.stateHash === hash) ?? null;
  }
  async attachCode(id: string, code: string): Promise<void> {
    const session = this.sessions.get(id);
    if (session === undefined || session.authorizationCode !== null) throw new AppleAuthError("already-exists", "callback already recorded");
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
  const start = await executeBeginAppleSignIn({ state, nonceHash, codeChallenge: challenge }, store, config, now, () => "session_12345678");
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
  await assert.rejects(executeBeginAppleSignIn({ state, nonceHash, codeChallenge: challenge, token: "leak" }, store, config, now));
  await prepared(store);
  const result = await executeAppleCallback({ state, error: "access_denied", error_description: "private provider text" }, store, now);
  assert.equal(result.redirectUri, "planterior://auth/apple?error=cancelled");
  assert.equal(result.redirectUri.includes("private"), false);
});
