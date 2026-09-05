import assert from "node:assert/strict"
import test from "node:test"
import {
  FIREBASE_APP_CHECK_EMULATOR_TOKEN,
  FIREBASE_AUTH_EMULATOR_TOKEN,
  FirebaseEmulatorAwareAppCheckTokenVerifier,
  FirebaseEmulatorAwareIDTokenVerifier,
  FirebasePlantIdentificationAuthenticator,
} from "./firebase-plant-identification-authenticator.js"

const request = (authorization: string, appCheck: string): Request =>
  new Request("https://plant-identification.internal/identifyPlant", {
    headers: {
      authorization,
      "x-firebase-appcheck": appCheck,
    },
  })

test("verifies the exact bearer token and consumes one matching App Check token", async () => {
  const authCalls: Array<readonly [string, true]> = []
  const appCheckCalls: Array<readonly [string, Readonly<{ consume: true }>]> = []
  const authenticator = new FirebasePlantIdentificationAuthenticator(
    {
      verifyIdToken: async (token, checkRevoked) => {
        authCalls.push([token, checkRevoked])
        return { uid: "user-a" }
      },
    },
    {
      verifyToken: async (token, options) => {
        appCheckCalls.push([token, options])
        return { appId: "ios-app-id" }
      },
    },
    () => "ios-app-id",
  )

  assert.equal(await authenticator.authenticate(request("Bearer id-token", "app-token")), "user-a")
  assert.deepEqual(authCalls, [["id-token", true]])
  assert.deepEqual(appCheckCalls, [["app-token", { consume: true }]])
})

test("rejects consumed and wrong-app App Check before a caller can proceed", async () => {
  const authenticator = (result: Readonly<{ appId: string; alreadyConsumed?: boolean }>) =>
    new FirebasePlantIdentificationAuthenticator(
      { verifyIdToken: async () => ({ uid: "user-a" }) },
      { verifyToken: async () => result },
      () => "ios-app-id",
    )

  await assert.rejects(
    authenticator({ appId: "ios-app-id", alreadyConsumed: true }).authenticate(
      request("Bearer id-token", "app-token"),
    ),
    { code: "permission-denied" },
  )
  await assert.rejects(
    authenticator({ appId: "android-app-id" }).authenticate(
      request("Bearer id-token", "app-token"),
    ),
    { code: "permission-denied" },
  )
})

test("maps missing and invalid App Check to permission denied", async () => {
  const validAuth = { verifyIdToken: async () => ({ uid: "user-a" }) }
  const missingAppCheck = new FirebasePlantIdentificationAuthenticator(
    validAuth,
    { verifyToken: async () => ({ appId: "ios-app-id" }) },
    () => "ios-app-id",
  )
  await assert.rejects(
    missingAppCheck.authenticate(
      new Request("https://plant-identification.internal/identifyPlant", {
        headers: { authorization: "Bearer id-token" },
      }),
    ),
    { code: "permission-denied" },
  )

  const invalidAppCheck = new FirebasePlantIdentificationAuthenticator(
    validAuth,
    {
      verifyToken: async () => {
        throw new Error("invalid App Check")
      },
    },
    () => "ios-app-id",
  )
  await assert.rejects(
    invalidAppCheck.authenticate(request("Bearer id-token", "invalid-app-token")),
    { code: "permission-denied" },
  )
})

test("rejects malformed bearer syntax without verifying either credential", async () => {
  let authCalls = 0
  let appCheckCalls = 0
  const authenticator = new FirebasePlantIdentificationAuthenticator(
    {
      verifyIdToken: async () => {
        authCalls += 1
        return { uid: "user-a" }
      },
    },
    {
      verifyToken: async () => {
        appCheckCalls += 1
        return { appId: "ios-app-id" }
      },
    },
    () => "ios-app-id",
  )

  assert.equal(
    await authenticator.authenticate(request("Bearer id-token extra", "app-token")),
    null,
  )
  assert.equal(authCalls, 0)
  assert.equal(appCheckCalls, 0)
})

test("accepts the local App Check marker only inside the Functions emulator", async () => {
  let delegateCalls = 0
  const verifier = new FirebaseEmulatorAwareAppCheckTokenVerifier(
    {
      verifyToken: async () => {
        delegateCalls += 1
        throw new Error("the emulator marker must not reach Firebase App Check")
      },
    },
    () => "ios-app-id",
    () => true,
  )

  assert.deepEqual(
    await verifier.verifyToken(FIREBASE_APP_CHECK_EMULATOR_TOKEN, { consume: true }),
    { appId: "ios-app-id" },
  )
  assert.equal(delegateCalls, 0)
})

test("never accepts the local App Check marker outside the Functions emulator", async () => {
  let delegatedToken: string | null = null
  const verifier = new FirebaseEmulatorAwareAppCheckTokenVerifier(
    {
      verifyToken: async (token) => {
        delegatedToken = token
        throw new Error("invalid App Check token")
      },
    },
    () => "ios-app-id",
    () => false,
  )

  await assert.rejects(verifier.verifyToken(FIREBASE_APP_CHECK_EMULATOR_TOKEN, { consume: true }), {
    message: "invalid App Check token",
  })
  assert.equal(delegatedToken, FIREBASE_APP_CHECK_EMULATOR_TOKEN)
})

test("accepts the local Auth marker only inside the Functions emulator", async () => {
  let delegateCalls = 0
  const verifier = new FirebaseEmulatorAwareIDTokenVerifier(
    {
      verifyIdToken: async () => {
        delegateCalls += 1
        throw new Error("the emulator marker must not reach Firebase Auth")
      },
    },
    () => true,
  )

  assert.deepEqual(await verifier.verifyIdToken(FIREBASE_AUTH_EMULATOR_TOKEN, true), {
    uid: "planterior-simulator-user",
  })
  assert.equal(delegateCalls, 0)
})

test("never accepts the local Auth marker outside the Functions emulator", async () => {
  let delegatedToken: string | null = null
  const verifier = new FirebaseEmulatorAwareIDTokenVerifier(
    {
      verifyIdToken: async (token) => {
        delegatedToken = token
        throw new Error("invalid Firebase ID token")
      },
    },
    () => false,
  )

  await assert.rejects(verifier.verifyIdToken(FIREBASE_AUTH_EMULATOR_TOKEN, true), {
    message: "invalid Firebase ID token",
  })
  assert.equal(delegatedToken, FIREBASE_AUTH_EMULATOR_TOKEN)
})
