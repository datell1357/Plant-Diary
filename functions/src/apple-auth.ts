import { createHash, randomUUID, timingSafeEqual } from "node:crypto";

export type AppleAuthErrorCode =
  | "invalid-argument"
  | "not-found"
  | "already-exists"
  | "failed-precondition"
  | "unauthenticated"
  | "unavailable";

export class AppleAuthError extends Error {
  constructor(readonly code: AppleAuthErrorCode, message: string) {
    super(message);
    this.name = "AppleAuthError";
  }
}

export type AppleOAuthSession = Readonly<{
  id: string;
  stateHash: string;
  nonceHash: string;
  codeChallenge: string;
  authorizationCode: string | null;
  createdAt: Date;
  expiresAt: Date;
  usedAt: Date | null;
}>;

export interface AppleSessionStore {
  create(session: AppleOAuthSession): Promise<void>;
  findByStateHash(stateHash: string): Promise<AppleOAuthSession | null>;
  attachCode(id: string, code: string): Promise<void>;
  consume(id: string, expectedChallenge: string, at: Date): Promise<AppleOAuthSession>;
}

export interface AppleTokenExchange {
  exchange(input: Readonly<{
    code: string;
    codeVerifier: string;
    expectedNonceHash: string;
  }>): Promise<Readonly<{ idToken: string }>>;
}

export type AppleOAuthConfig = Readonly<{ clientId: string; redirectUri: string }>;

const base64Url43 = /^[A-Za-z0-9_-]{43}$/;
const opaqueId = /^[A-Za-z0-9_-]{8,128}$/;
const authorizationCode = /^[A-Za-z0-9._~-]{1,2048}$/;

function record(input: unknown): Readonly<Record<string, unknown>> {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new AppleAuthError("invalid-argument", "input must be an object");
  }
  return Object.fromEntries(Object.entries(input));
}

function exact(value: Readonly<Record<string, unknown>>, required: readonly string[], optional: readonly string[] = []): void {
  const allowed = new Set([...required, ...optional]);
  if (!required.every((field) => field in value) || !Object.keys(value).every((field) => allowed.has(field))) {
    throw new AppleAuthError("invalid-argument", "input fields do not match the contract");
  }
}

function text(value: Readonly<Record<string, unknown>>, field: string, pattern: RegExp): string {
  const candidate = value[field];
  if (typeof candidate !== "string" || !pattern.test(candidate)) {
    throw new AppleAuthError("invalid-argument", `${field} is invalid`);
  }
  return candidate;
}

function digest(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("base64url");
}

function equal(left: string, right: string): boolean {
  const leftBytes = Buffer.from(left);
  const rightBytes = Buffer.from(right);
  return leftBytes.length === rightBytes.length && timingSafeEqual(leftBytes, rightBytes);
}

function validateConfig(config: AppleOAuthConfig): void {
  if (!/^[A-Za-z0-9.-]{3,128}$/.test(config.clientId)) throw new AppleAuthError("failed-precondition", "Apple client ID is not configured");
  const redirect = new URL(config.redirectUri);
  if (redirect.protocol !== "https:" || !redirect.hostname.endsWith(".cloudfunctions.net") || redirect.pathname.split("/").at(-1) !== "appleOAuthCallback") {
    throw new AppleAuthError("failed-precondition", "Apple redirect URI is not allowlisted");
  }
}

export async function executeBeginAppleSignIn(
  input: unknown,
  store: AppleSessionStore,
  config: AppleOAuthConfig,
  now: Date = new Date(),
  idFactory: () => string = () => randomUUID().replaceAll("-", ""),
): Promise<Readonly<{ sessionId: string; authorizationUrl: string; expiresAt: string }>> {
  const value = record(input);
  exact(value, ["state", "nonceHash", "codeChallenge"]);
  const state = text(value, "state", base64Url43);
  const nonceHash = text(value, "nonceHash", base64Url43);
  const codeChallenge = text(value, "codeChallenge", base64Url43);
  validateConfig(config);
  const id = idFactory();
  if (!opaqueId.test(id)) throw new AppleAuthError("failed-precondition", "session ID generation failed");
  const expiresAt = new Date(now.getTime() + 600_000);
  await store.create({
    id,
    stateHash: digest(state),
    nonceHash,
    codeChallenge,
    authorizationCode: null,
    createdAt: now,
    expiresAt,
    usedAt: null,
  });
  const authorization = new URL("https://appleid.apple.com/auth/authorize");
  authorization.searchParams.set("client_id", config.clientId);
  authorization.searchParams.set("redirect_uri", config.redirectUri);
  authorization.searchParams.set("response_type", "code id_token");
  authorization.searchParams.set("response_mode", "form_post");
  authorization.searchParams.set("scope", "name email");
  authorization.searchParams.set("state", state);
  authorization.searchParams.set("nonce", nonceHash);
  authorization.searchParams.set("code_challenge", codeChallenge);
  authorization.searchParams.set("code_challenge_method", "S256");
  return { sessionId: id, authorizationUrl: authorization.toString(), expiresAt: expiresAt.toISOString() };
}

export async function executeAppleCallback(
  input: unknown,
  store: AppleSessionStore,
  now: Date = new Date(),
): Promise<Readonly<{ redirectUri: string }>> {
  const value = record(input);
  exact(value, ["state"], ["code", "error", "error_description"]);
  const state = text(value, "state", base64Url43);
  if (value.error !== undefined) {
    if (typeof value.error !== "string" || (value.error_description !== undefined && typeof value.error_description !== "string")) {
      throw new AppleAuthError("invalid-argument", "provider error is malformed");
    }
    return { redirectUri: "planterior://auth/apple?error=cancelled" };
  }
  const code = text(value, "code", authorizationCode);
  const session = await store.findByStateHash(digest(state));
  if (session === null) throw new AppleAuthError("not-found", "Apple session was not found");
  if (session.expiresAt <= now) throw new AppleAuthError("failed-precondition", "Apple session expired");
  await store.attachCode(session.id, code);
  return { redirectUri: `planterior://auth/apple?sessionId=${encodeURIComponent(session.id)}&state=${encodeURIComponent(state)}` };
}

export async function executeCompleteAppleSignIn(
  input: unknown,
  store: AppleSessionStore,
  tokenExchange: AppleTokenExchange,
  now: Date = new Date(),
): Promise<Readonly<{ idToken: string }>> {
  const value = record(input);
  exact(value, ["sessionId", "state", "codeVerifier"]);
  const sessionId = text(value, "sessionId", opaqueId);
  const state = text(value, "state", base64Url43);
  const codeVerifier = text(value, "codeVerifier", base64Url43);
  const selected = await store.findByStateHash(digest(state));
  if (selected === null || !equal(selected.id, sessionId)) throw new AppleAuthError("unauthenticated", "Apple session does not match");
  const session = await store.consume(sessionId, digest(codeVerifier), now);
  const code = session.authorizationCode;
  if (code === null) throw new AppleAuthError("failed-precondition", "Apple callback is incomplete");
  const result = await tokenExchange.exchange({ code, codeVerifier, expectedNonceHash: session.nonceHash });
  if (!/^[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+[.][A-Za-z0-9_-]+$/.test(result.idToken)) {
    throw new AppleAuthError("unauthenticated", "Apple identity token is malformed");
  }
  return result;
}
