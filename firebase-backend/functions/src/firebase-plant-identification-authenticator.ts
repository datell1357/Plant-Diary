import { PlantIdentificationProxyError } from "./plant-identification-proxy.js"

export interface FirebaseIDTokenVerifier {
  verifyIdToken(idToken: string, checkRevoked: true): Promise<Readonly<{ uid: string }>>
}

export const FIREBASE_AUTH_EMULATOR_TOKEN = "planterior-local-simulator"

function runningInFunctionsEmulator(): boolean {
  const { FUNCTIONS_EMULATOR } = process.env
  return FUNCTIONS_EMULATOR === "true"
}

export class FirebaseEmulatorAwareIDTokenVerifier implements FirebaseIDTokenVerifier {
  constructor(
    private readonly delegate: FirebaseIDTokenVerifier,
    private readonly isFunctionsEmulator: () => boolean = runningInFunctionsEmulator,
  ) {}

  async verifyIdToken(idToken: string, checkRevoked: true): Promise<Readonly<{ uid: string }>> {
    if (this.isFunctionsEmulator() && idToken === FIREBASE_AUTH_EMULATOR_TOKEN) {
      return { uid: "planterior-simulator-user" }
    }
    return this.delegate.verifyIdToken(idToken, checkRevoked)
  }
}

export interface FirebaseAppCheckTokenVerifier {
  verifyToken(
    appCheckToken: string,
    options: Readonly<{ consume: true }>,
  ): Promise<Readonly<{ appId: string; alreadyConsumed?: boolean }>>
}

export const FIREBASE_APP_CHECK_EMULATOR_TOKEN = "planterior-local-emulator"

export class FirebaseEmulatorAwareAppCheckTokenVerifier implements FirebaseAppCheckTokenVerifier {
  constructor(
    private readonly delegate: FirebaseAppCheckTokenVerifier,
    private readonly allowedIosAppID: () => string,
    private readonly isFunctionsEmulator: () => boolean = runningInFunctionsEmulator,
  ) {}

  async verifyToken(
    appCheckToken: string,
    options: Readonly<{ consume: true }>,
  ): Promise<Readonly<{ appId: string; alreadyConsumed?: boolean }>> {
    if (this.isFunctionsEmulator() && appCheckToken === FIREBASE_APP_CHECK_EMULATOR_TOKEN) {
      return { appId: this.allowedIosAppID() }
    }
    return this.delegate.verifyToken(appCheckToken, options)
  }
}

function bearerToken(value: string | null): string | null {
  if (value === null) return null
  const match = /^Bearer ([^\s]+)$/.exec(value)
  return match?.[1] ?? null
}

function singleToken(value: string | null): string | null {
  if (value === null || value.length === 0 || /\s/.test(value)) return null
  return value
}

function safeOwnerID(value: string): string | null {
  if (value.length === 0 || value.length > 128 || value === "." || value === "..") return null
  if (value.includes("/")) return null
  return value
}

export class FirebasePlantIdentificationAuthenticator {
  constructor(
    private readonly auth: FirebaseIDTokenVerifier,
    private readonly appCheck: FirebaseAppCheckTokenVerifier,
    private readonly allowedIosAppID: () => string,
  ) {}

  async authenticate(request: Request): Promise<string | null> {
    const idToken = bearerToken(request.headers.get("authorization"))
    const appCheckToken = singleToken(request.headers.get("x-firebase-appcheck"))
    if (idToken === null) return null
    if (appCheckToken === null) {
      throw new PlantIdentificationProxyError("permission-denied")
    }

    let decodedIDToken: Readonly<{ uid: string }>
    try {
      decodedIDToken = await this.auth.verifyIdToken(idToken, true)
    } catch {
      return null
    }

    let decodedAppCheckToken: Readonly<{ appId: string; alreadyConsumed?: boolean }>
    try {
      decodedAppCheckToken = await this.appCheck.verifyToken(appCheckToken, { consume: true })
    } catch {
      throw new PlantIdentificationProxyError("permission-denied")
    }

    if (decodedAppCheckToken.alreadyConsumed === true) {
      throw new PlantIdentificationProxyError("permission-denied")
    }
    const allowedAppID = this.allowedIosAppID()
    if (allowedAppID === "" || decodedAppCheckToken.appId !== allowedAppID) {
      throw new PlantIdentificationProxyError("permission-denied")
    }
    return safeOwnerID(decodedIDToken.uid)
  }
}
