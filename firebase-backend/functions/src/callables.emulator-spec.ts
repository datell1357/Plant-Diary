import assert from "node:assert/strict"
import test from "node:test"
import { z } from "zod"

const CANONICAL_PROJECT_ID = "demo-planterior-ios-deletion"
const REGION = "us-central1"
const { AUTH_EMULATOR_PORT, FUNCTIONS_EMULATOR_PORT, GCLOUD_PROJECT } = process.env
const authPort = AUTH_EMULATOR_PORT ?? "9299"
const functionsPort = FUNCTIONS_EMULATOR_PORT ?? "5201"
const projectId = GCLOUD_PROJECT ?? CANONICAL_PROJECT_ID
const FUNCTION_NAMES = [
  "previewAccountDeletion",
  "recoverAccountDeletion",
  "requestAccountDeletion",
  "cancelAccountDeletion",
  "loadInventory",
  "acquireInventoryItem",
  "loadMiniHome",
  "saveMiniHome",
] as const
const AuthResponseSchema = z.object({ idToken: z.string().min(1) }).passthrough()

function callableUrl(functionName: (typeof FUNCTION_NAMES)[number]): string {
  return `http://127.0.0.1:${functionsPort}/${projectId}/${REGION}/${functionName}`
}

for (const functionName of FUNCTION_NAMES) {
  test(`${functionName} callable rejects an unauthenticated request`, async () => {
    // Given
    const url = callableUrl(functionName)
    assert.equal(new URL(url).pathname, `/${projectId}/${REGION}/${functionName}`)
    if (projectId !== CANONICAL_PROJECT_ID) {
      assert.equal(url.includes(`/${CANONICAL_PROJECT_ID}/`), false)
    }

    // When
    const response = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ data: { ownerID: "owner-a" } }),
    })

    // Then
    assert.equal(response.status, 401)
  })
}

for (const functionName of ["loadInventory", "loadMiniHome", "saveMiniHome"] as const) {
  test(`${functionName} callable rejects an authenticated request without App Check`, async () => {
    // Given
    const authResponse = await fetch(
      `http://127.0.0.1:${authPort}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-key`,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          email: `${functionName}@example.test`,
          password: "deterministic-password",
          returnSecureToken: true,
        }),
      },
    )
    const authPayload = AuthResponseSchema.parse(await authResponse.json())

    // When
    const response = await fetch(callableUrl(functionName), {
      method: "POST",
      headers: {
        authorization: `Bearer ${authPayload.idToken}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({ data: { expectedOwnerUid: "foreign-owner" } }),
    })

    // Then
    assert.equal(response.status, 401)
  })
}
