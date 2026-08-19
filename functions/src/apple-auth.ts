import { createHash, randomUUID, timingSafeEqual } from "node:crypto";

export type AppleAuthErrorCode =
  | "invalid-argument"
  | "not-found"
  | "already-exists"
  | "failed-precondition"
  | "unauthenticated"
  | "resource-exhausted"
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
  abuseKeyHash: string;
}>;

export interface AppleSessionStore {
  create(session: AppleOAuthSession): Promise<void>;
  findByStateHash(stateHash: string): Promise<AppleOAuthSession | null>;
  attachCode(id: string, code: string, at: Date): Promise<void>;
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
const callbackIdentityToken = /^[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+[.][A-Za-z0-9_-]+$/;
const providerError = /^[A-Za-z0-9._~-]{1,128}$/;
const maximumCallbackIdentityTokenBytes = 16_384;
const maximumCallbackUserBytes = 8_192;
const maximumProviderErrorDescriptionBytes = 2_048;

function record(input: unknown): Readonly<Record<string, unknown>> {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new AppleAuthError("invalid-argument", "input must be an object");
  }
  const prototype = Object.getPrototypeOf(input);
  if (prototype !== Object.prototype && prototype !== null) {
    throw new AppleAuthError("invalid-argument", "input object prototype is invalid");
  }
  const entries = Object.entries(input);
  if (entries.some(([field]) => field === "__proto__" || field === "prototype" || field === "constructor")) {
    throw new AppleAuthError("invalid-argument", "input fields do not match the contract");
  }
  return Object.fromEntries(entries);
}

function exact(value: Readonly<Record<string, unknown>>, required: readonly string[], optional: readonly string[] = []): void {
  const allowed = new Set([...required, ...optional]);
  if (
    !required.every((field) => Object.prototype.hasOwnProperty.call(value, field)) ||
    !Object.keys(value).every((field) => allowed.has(field))
  ) {
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

function boundedText(value: unknown, maximumBytes: number, field: string): string {
  if (typeof value !== "string" || Buffer.byteLength(value, "utf8") > maximumBytes) {
    throw new AppleAuthError("invalid-argument", `${field} is invalid`);
  }
  return value;
}

function validateIgnoredCallbackIdentityToken(value: unknown): void {
  const token = boundedText(value, maximumCallbackIdentityTokenBytes, "id_token");
  if (!callbackIdentityToken.test(token)) {
    throw new AppleAuthError("invalid-argument", "id_token is invalid");
  }
}

function rejectDuplicateJsonObjectKeys(encoded: string): void {
  let index = 0;
  const invalid = (): never => { throw new AppleAuthError("invalid-argument", "user is invalid"); };
  const whitespace = (): void => {
    while (index < encoded.length && /\s/.test(encoded[index] ?? "")) index += 1;
  };
  const string = (): string => {
    const start = index;
    if (encoded[index] !== '"') invalid();
    index += 1;
    while (index < encoded.length) {
      if (encoded[index] === "\\") {
        index += 2;
      } else if (encoded[index] === '"') {
        index += 1;
        return JSON.parse(encoded.slice(start, index)) as string;
      } else {
        index += 1;
      }
    }
    return invalid();
  };
  const value = (depth: number): void => {
    if (depth > 8) invalid();
    whitespace();
    if (encoded[index] === '"') {
      string();
      return;
    }
    if (encoded[index] === "{") {
      index += 1;
      whitespace();
      const keys = new Set<string>();
      if (encoded[index] === "}") {
        index += 1;
        return;
      }
      while (index < encoded.length) {
        const key = string();
        if (keys.has(key)) invalid();
        keys.add(key);
        whitespace();
        if (encoded[index] !== ":") invalid();
        index += 1;
        value(depth + 1);
        whitespace();
        if (encoded[index] === "}") {
          index += 1;
          return;
        }
        if (encoded[index] !== ",") invalid();
        index += 1;
        whitespace();
      }
      invalid();
    }
    if (encoded[index] === "[") {
      index += 1;
      whitespace();
      if (encoded[index] === "]") {
        index += 1;
        return;
      }
      while (index < encoded.length) {
        value(depth + 1);
        whitespace();
        if (encoded[index] === "]") {
          index += 1;
          return;
        }
        if (encoded[index] !== ",") invalid();
        index += 1;
      }
      invalid();
    }
    const start = index;
    while (index < encoded.length && !/[\s,}\]]/.test(encoded[index] ?? "")) index += 1;
    if (index === start) invalid();
  };
  value(0);
  whitespace();
  if (index !== encoded.length) invalid();
}

function validateIgnoredCallbackUser(value: unknown): void {
  const encoded = boundedText(value, maximumCallbackUserBytes, "user");
  let parsed: unknown;
  try {
    parsed = JSON.parse(encoded) as unknown;
  } catch {
    throw new AppleAuthError("invalid-argument", "user is invalid");
  }
  rejectDuplicateJsonObjectKeys(encoded);
  const user = record(parsed);
  exact(user, ["name", "email"]);
  const email = boundedText(user.email, 320, "user.email");
  if (email.length === 0) throw new AppleAuthError("invalid-argument", "user.email is invalid");
  const name = record(user.name);
  exact(name, ["firstName", "lastName"]);
  boundedText(name.firstName, 256, "user.name.firstName");
  boundedText(name.lastName, 256, "user.name.lastName");
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
  abuseKeyHash: string,
  now: Date = new Date(),
  idFactory: () => string = () => randomUUID().replaceAll("-", ""),
): Promise<Readonly<{ sessionId: string; authorizationUrl: string; expiresAt: string }>> {
  const value = record(input);
  exact(value, ["state", "nonceHash", "codeChallenge"]);
  const state = text(value, "state", base64Url43);
  const nonceHash = text(value, "nonceHash", base64Url43);
  const codeChallenge = text(value, "codeChallenge", base64Url43);
  validateConfig(config);
  if (!/^[a-f0-9]{64}$/.test(abuseKeyHash)) {
    throw new AppleAuthError("failed-precondition", "Apple session admission is unavailable");
  }
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
    abuseKeyHash,
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
  exact(value, ["state"], ["code", "id_token", "user", "error", "error_description"]);
  const state = text(value, "state", base64Url43);
  const hasError = value.error !== undefined || value.error_description !== undefined;
  const hasSuccess = value.code !== undefined || value.id_token !== undefined || value.user !== undefined;
  if (hasError && hasSuccess) {
    throw new AppleAuthError("invalid-argument", "provider callback mixes success and error fields");
  }
  if (hasError) {
    exact(value, ["state", "error"], ["error_description"]);
    text(value, "error", providerError);
    if (value.error_description !== undefined) {
      boundedText(value.error_description, maximumProviderErrorDescriptionBytes, "error_description");
    }
  } else {
    exact(value, ["state", "code"], ["id_token", "user"]);
    text(value, "code", authorizationCode);
    if (value.id_token !== undefined) validateIgnoredCallbackIdentityToken(value.id_token);
    if (value.user !== undefined) validateIgnoredCallbackUser(value.user);
  }
  const session = await store.findByStateHash(digest(state));
  if (session === null) throw new AppleAuthError("not-found", "Apple session was not found");
  if (session.expiresAt <= now) throw new AppleAuthError("failed-precondition", "Apple session expired");
  if (hasError) return { redirectUri: "planterior://auth/apple?error=cancelled" };
  const code = text(value, "code", authorizationCode);
  await store.attachCode(session.id, code, now);
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
