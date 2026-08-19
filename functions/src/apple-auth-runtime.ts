import { createRemoteJWKSet, errors, importPKCS8, jwtVerify, SignJWT } from "jose";
import { FieldPath, Timestamp, type Firestore } from "firebase-admin/firestore";
import {
  AppleAuthError,
  type AppleOAuthConfig,
  type AppleOAuthSession,
  type AppleSessionStore,
  type AppleTokenExchange,
} from "./apple-auth.js";

function sessionFromData(id: string, data: Readonly<Record<string, unknown>>): AppleOAuthSession {
  const stateHash = data.stateHash;
  const nonceHash = data.nonceHash;
  const codeChallenge = data.codeChallenge;
  const authorizationCode = data.authorizationCode;
  const createdAt = data.createdAt;
  const expiresAt = data.expiresAt;
  const usedAt = data.usedAt;
  const abuseKeyHash = data.abuseKeyHash;
  if (
    typeof stateHash !== "string" || typeof nonceHash !== "string" || typeof codeChallenge !== "string" ||
    (authorizationCode !== null && typeof authorizationCode !== "string") || !(createdAt instanceof Timestamp) ||
    !(expiresAt instanceof Timestamp) || (usedAt !== null && !(usedAt instanceof Timestamp)) ||
    typeof abuseKeyHash !== "string" || !/^[a-f0-9]{64}$/.test(abuseKeyHash)
  ) throw new AppleAuthError("failed-precondition", "stored Apple session is malformed");
  return {
    id,
    stateHash,
    nonceHash,
    codeChallenge,
    authorizationCode,
    createdAt: createdAt.toDate(),
    expiresAt: expiresAt.toDate(),
    usedAt: usedAt === null ? null : usedAt.toDate(),
    abuseKeyHash,
  };
}

export const APPLE_SESSION_RATE_LIMIT = 10;
export const APPLE_SESSION_RATE_WINDOW_MILLIS = 10 * 60 * 1000;
export const APPLE_SESSION_CLEANUP_LIMIT = 200;

export class FirestoreAppleSessionStore implements AppleSessionStore {
  constructor(private readonly firestore: Firestore) {}

  async create(session: AppleOAuthSession): Promise<void> {
    const sessionReference = this.firestore.doc(`appleAuthSessions/${session.id}`);
    const rateReference = this.firestore.doc(`appleAuthRateLimits/${session.abuseKeyHash}`);
    await this.firestore.runTransaction(async (transaction) => {
      const [existingSession, rateDocument] = await Promise.all([
        transaction.get(sessionReference),
        transaction.get(rateReference),
      ]);
      if (existingSession.exists) {
        throw new AppleAuthError("already-exists", "Apple session could not be created");
      }
      const now = session.createdAt;
      const rate = rateDocument.data();
      let windowStartedAt = now;
      let count = 0;
      if (rateDocument.exists) {
        const storedStart = rate?.windowStartedAt;
        const storedCount = rate?.count;
        if (
          !(storedStart instanceof Timestamp) ||
          typeof storedCount !== "number" ||
          !Number.isSafeInteger(storedCount) ||
          storedCount < 0
        ) {
          throw new AppleAuthError("failed-precondition", "Apple session admission is unavailable");
        }
        const elapsed = now.getTime() - storedStart.toMillis();
        if (elapsed >= 0 && elapsed < APPLE_SESSION_RATE_WINDOW_MILLIS) {
          windowStartedAt = storedStart.toDate();
          count = storedCount;
        }
      }
      if (count >= APPLE_SESSION_RATE_LIMIT) {
        throw new AppleAuthError("resource-exhausted", "Apple sign-in is temporarily limited");
      }
      transaction.create(sessionReference, {
        ...session,
        createdAt: Timestamp.fromDate(session.createdAt),
        expiresAt: Timestamp.fromDate(session.expiresAt),
      });
      transaction.set(rateReference, {
        windowStartedAt: Timestamp.fromDate(windowStartedAt),
        count: count + 1,
        expiresAt: Timestamp.fromMillis(windowStartedAt.getTime() + APPLE_SESSION_RATE_WINDOW_MILLIS),
      });
    });
  }

  async findByStateHash(stateHash: string): Promise<AppleOAuthSession | null> {
    const result = await this.firestore.collection("appleAuthSessions").where("stateHash", "==", stateHash).limit(1).get();
    const document = result.docs[0];
    return document === undefined ? null : sessionFromData(document.id, document.data());
  }

  async attachCode(id: string, code: string, at: Date): Promise<void> {
    const reference = this.firestore.doc(`appleAuthSessions/${id}`);
    await this.firestore.runTransaction(async (transaction) => {
      const document = await transaction.get(reference);
      if (!document.exists) throw new AppleAuthError("not-found", "Apple session was not found");
      const session = sessionFromData(document.id, document.data() ?? {});
      if (session.authorizationCode !== null || session.usedAt !== null) throw new AppleAuthError("already-exists", "Apple callback was already used");
      if (session.expiresAt <= at) throw new AppleAuthError("failed-precondition", "Apple session expired");
      transaction.update(reference, { authorizationCode: code });
    });
  }

  async consume(id: string, expectedChallenge: string, at: Date): Promise<AppleOAuthSession> {
    const reference = this.firestore.doc(`appleAuthSessions/${id}`);
    return this.firestore.runTransaction(async (transaction) => {
      const document = await transaction.get(reference);
      if (!document.exists) throw new AppleAuthError("not-found", "Apple session was not found");
      const session = sessionFromData(document.id, document.data() ?? {});
      if (session.usedAt !== null) throw new AppleAuthError("already-exists", "Apple session was already consumed");
      if (session.authorizationCode === null || session.expiresAt <= at || session.codeChallenge !== expectedChallenge) {
        throw new AppleAuthError("failed-precondition", "Apple session cannot be completed");
      }
      transaction.update(reference, { usedAt: Timestamp.fromDate(at) });
      return session;
    });
  }

  async cleanupExpired(at: Date, limit: number = APPLE_SESSION_CLEANUP_LIMIT): Promise<Readonly<{
    sessions: number;
    rateLimits: number;
  }>> {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > APPLE_SESSION_CLEANUP_LIMIT) {
      throw new AppleAuthError("failed-precondition", "Apple cleanup limit is invalid");
    }
    const sessions = await this.cleanupCollection("appleAuthSessions", at, limit);
    const rateLimits = await this.cleanupCollection("appleAuthRateLimits", at, limit);
    return { sessions, rateLimits };
  }

  private async cleanupCollection(collection: string, at: Date, limit: number): Promise<number> {
    const query = this.firestore
      .collection(collection)
      .where("expiresAt", "<=", Timestamp.fromDate(at))
      .orderBy("expiresAt", "asc")
      .orderBy(FieldPath.documentId(), "asc")
      .limit(limit);
    return this.firestore.runTransaction(async (transaction) => {
      const expired = await transaction.get(query);
      for (const document of expired.docs) transaction.delete(document.ref);
      return expired.size;
    });
  }
}

export type AppleSecretConfig = AppleOAuthConfig & Readonly<{
  teamId: string;
  keyId: string;
  privateKey: string;
}>;

export class VerifiedAppleTokenExchange implements AppleTokenExchange {
  private readonly jwks = createRemoteJWKSet(new URL("https://appleid.apple.com/auth/keys"));

  constructor(private readonly config: AppleSecretConfig, private readonly fetcher: typeof fetch = fetch) {}

  async exchange(input: Readonly<{ code: string; codeVerifier: string; expectedNonceHash: string }>): Promise<Readonly<{ idToken: string }>> {
    const clientSecret = await this.clientSecret();
    const response = await this.fetcher("https://appleid.apple.com/auth/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code: input.code,
        code_verifier: input.codeVerifier,
        client_id: this.config.clientId,
        client_secret: clientSecret,
        redirect_uri: this.config.redirectUri,
      }),
    });
    if (!response.ok) throw new AppleAuthError("unauthenticated", "Apple authorization code was rejected");
    const body: unknown = await response.json();
    if (typeof body !== "object" || body === null || Array.isArray(body)) throw new AppleAuthError("unavailable", "Apple token response was malformed");
    const idToken = Object.fromEntries(Object.entries(body)).id_token;
    if (typeof idToken !== "string") throw new AppleAuthError("unavailable", "Apple token response did not contain an identity token");
    const verified = await jwtVerify(idToken, this.jwks, {
      algorithms: ["RS256"],
      issuer: "https://appleid.apple.com",
      audience: this.config.clientId,
      clockTolerance: 5,
    }).catch((cause: unknown) => {
      throw new AppleAuthError(
        "unauthenticated",
        cause instanceof errors.JWTExpired ? "expired-token" : "invalid-issuer-or-audience",
      );
    });
    if (verified.payload.nonce !== input.expectedNonceHash) throw new AppleAuthError("unauthenticated", "invalid-nonce");
    return { idToken };
  }

  private async clientSecret(): Promise<string> {
    if (!this.config.privateKey.includes("PRIVATE KEY")) throw new AppleAuthError("failed-precondition", "Apple signing key is unavailable");
    const key = await importPKCS8(this.config.privateKey.replaceAll("\\n", "\n"), "ES256");
    const now = Math.floor(Date.now() / 1000);
    return new SignJWT({})
      .setProtectedHeader({ alg: "ES256", kid: this.config.keyId })
      .setIssuer(this.config.teamId)
      .setSubject(this.config.clientId)
      .setAudience("https://appleid.apple.com")
      .setIssuedAt(now)
      .setExpirationTime(now + 300)
      .sign(key);
  }
}
