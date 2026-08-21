import assert from "node:assert/strict"
import test from "node:test"
import { z } from "zod"

const PROJECT_ID = "demo-planterior-ios-deletion"
const REGION = "us-central1"
const FUNCTION_NAMES = [
  "previewAccountDeletion",
  "requestAccountDeletion",
  "cancelAccountDeletion",
] as const
const AuthResponseSchema = z.object({ idToken: z.string().min(1) }).passthrough()

function callableUrl(functionName: (typeof FUNCTION_NAMES)[number]): string {
  return `http://127.0.0.1:5201/${PROJECT_ID}/${REGION}/${functionName}`
}

for (const functionName of FUNCTION_NAMES) {
  test(`${functionName} callable rejects an unauthenticated request`, async () => {
    // Given
    const url = callableUrl(functionName)

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

test("previewAccountDeletion callable rejects an authenticated request without App Check", async () => {
  // Given
  const authResponse = await fetch(
    "http://127.0.0.1:9299/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-key",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        email: "callable-owner@example.test",
        password: "deterministic-password",
        returnSecureToken: true,
      }),
    },
  )
  const authPayload = AuthResponseSchema.parse(await authResponse.json())

  // When
  const response = await fetch(callableUrl("previewAccountDeletion"), {
    method: "POST",
    headers: {
      authorization: `Bearer ${authPayload.idToken}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ data: { ownerID: "foreign-owner" } }),
  })

  // Then
  assert.equal(response.status, 401)
})
