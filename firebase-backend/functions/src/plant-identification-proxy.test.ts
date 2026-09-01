import assert from "node:assert/strict"
import test from "node:test"
import {
  PlantIdHttpClient,
  type PlantIdTransport,
  PlantIdTransportTimeoutError,
} from "./plant-id-client.js"
import {
  createPlantIdentificationHTTPHandler,
  executePlantIdentification,
  type PlantIdentificationProvider,
  type PlantIdentificationResponse,
  type PlantIdentificationResultStore,
} from "./plant-identification-proxy.js"

const request = {
  requestID: "request_12345678",
  idempotencyKey: "operation_12345678",
  imageBase64: Buffer.from("photo").toString("base64"),
}

class MemoryResultStore implements PlantIdentificationResultStore {
  readonly calls: string[] = []
  private readonly results = new Map<string, Promise<PlantIdentificationResponse>>()

  runOnce(
    subjectID: string,
    requestID: string,
    idempotencyKey: string,
    operation: () => Promise<PlantIdentificationResponse>,
  ): Promise<PlantIdentificationResponse> {
    this.calls.push(`${subjectID}:${requestID}:${idempotencyKey}`)
    const existing = this.results.get(idempotencyKey)
    if (existing !== undefined) return existing
    const created = operation()
    this.results.set(idempotencyKey, created)
    return created
  }
}

function provider(result: unknown): PlantIdentificationProvider {
  return { identify: async () => result }
}

test("normalizes Korean Plant.id candidates for the strict iOS proxy contract", async () => {
  const result = await executePlantIdentification(
    "user-a",
    request,
    new MemoryResultStore(),
    provider({
      result: {
        classification: {
          suggestions: [
            {
              id: "species-low",
              name: "Ficus lyrata",
              probability: 0.21,
              details: { common_names: ["떡갈고무나무"] },
              similar_images: [{ url: "https://images.example/low.jpg" }],
            },
            {
              id: "species-high",
              name: "Monstera deliciosa",
              probability: 0.93,
              details: { common_names: ["몬스테라"] },
              similar_images: [{ url: "https://images.example/high.jpg" }],
            },
            {
              id: "species-middle",
              name: "Epipremnum aureum",
              probability: 0.67,
              details: { common_names: ["스킨답서스"] },
              similar_images: [{ url: "https://images.example/middle.jpg" }],
            },
            {
              id: "species-four",
              name: "Philodendron hederaceum",
              probability: 0.4,
              details: {},
              similar_images: [{ url: "https://images.example/four.jpg" }],
            },
          ],
        },
      },
    }),
  )

  assert.deepEqual(result, {
    kind: "candidates",
    candidates: [
      {
        publicContentId: "species-high",
        koreanName: "몬스테라",
        commonName: "몬스테라",
        scientificName: "Monstera deliciosa",
        confidence: 0.93,
        thumbnailUrl: "https://images.example/high.jpg",
      },
      {
        publicContentId: "species-middle",
        koreanName: "스킨답서스",
        commonName: "스킨답서스",
        scientificName: "Epipremnum aureum",
        confidence: 0.67,
        thumbnailUrl: "https://images.example/middle.jpg",
      },
      {
        publicContentId: "species-four",
        koreanName: "Philodendron hederaceum",
        commonName: "Philodendron hederaceum",
        scientificName: "Philodendron hederaceum",
        confidence: 0.4,
        thumbnailUrl: "https://images.example/four.jpg",
      },
    ],
  })
})

test("rejects invalid callers and payloads before sending a private image", async () => {
  let providerCalls = 0
  const countingProvider: PlantIdentificationProvider = {
    identify: async () => {
      providerCalls += 1
      return { result: { classification: { suggestions: [] } } }
    },
  }

  await assert.rejects(
    executePlantIdentification(null, request, new MemoryResultStore(), countingProvider),
    { code: "unauthenticated" },
  )
  await assert.rejects(
    executePlantIdentification(
      "user-a",
      { ...request, imageBase64: "not base64" },
      new MemoryResultStore(),
      countingProvider,
    ),
    { code: "invalid-argument" },
  )
  assert.equal(providerCalls, 0)
})

test("deduplicates a repeated idempotency key and maps malformed provider data", async () => {
  const store = new MemoryResultStore()
  let providerCalls = 0
  const malformedProvider: PlantIdentificationProvider = {
    identify: async () => {
      providerCalls += 1
      return { result: { classification: { suggestions: [{ id: 7 }] } } }
    },
  }

  const [first, second] = await Promise.all([
    executePlantIdentification("user-a", request, store, malformedProvider),
    executePlantIdentification("user-a", request, store, malformedProvider),
  ])

  assert.deepEqual(first, { kind: "failed", reason: "malformed_response" })
  assert.deepEqual(second, first)
  assert.equal(providerCalls, 1)
})

test("Plant.id transport receives classification-only Korean request without exposing the key", async () => {
  let captured: Parameters<PlantIdTransport["post"]>[0] | undefined
  const transport: PlantIdTransport = {
    post: async (value) => {
      captured = value
      return { status: 201, body: { result: { classification: { suggestions: [] } } } }
    },
  }
  const client = new PlantIdHttpClient("private-key", transport)

  const result = await client.identify(Buffer.from([0xff, 0xd8, 0xff, 0x00]))

  assert.deepEqual(result, { result: { classification: { suggestions: [] } } })
  assert.deepEqual(captured, {
    apiKey: "private-key",
    image: "data:image/jpeg;base64,/9j/AA==",
    language: "ko",
    details: ["common_names"],
    similarImages: true,
    classificationLevel: "species",
  })
})

for (const [status, code] of [
  [429, "rate-limited"],
  [503, "provider-unavailable"],
] as const) {
  test(`maps Plant.id HTTP ${status} to ${code}`, async () => {
    const transport: PlantIdTransport = {
      post: async () => ({ status, body: { providerDiagnostic: "hidden" } }),
    }
    await assert.rejects(
      new PlantIdHttpClient("private-key", transport).identify(
        Buffer.from([0xff, 0xd8, 0xff, 0x00]),
      ),
      { code },
    )
  })
}

test("maps a Plant.id timeout without leaking transport details", async () => {
  const transport: PlantIdTransport = {
    post: async () => {
      throw new PlantIdTransportTimeoutError()
    },
  }
  await assert.rejects(
    new PlantIdHttpClient("private-key", transport).identify(Buffer.from([0xff, 0xd8, 0xff, 0x00])),
    { code: "timeout" },
  )
})

test("platform-neutral HTTP handler requires host authentication and matches the iOS contract", async () => {
  let providerCalls = 0
  const handler = createPlantIdentificationHTTPHandler({
    authenticate: async (incoming) =>
      incoming.headers.get("authorization") === "Bearer local-test-token" ? "user-a" : null,
    store: new MemoryResultStore(),
    provider: {
      identify: async () => {
        providerCalls += 1
        return {
          result: {
            classification: {
              suggestions: [
                {
                  id: "species-one",
                  name: "Monstera deliciosa",
                  probability: 0.95,
                  details: { common_names: ["몬스테라"] },
                  similar_images: [{ url: "https://images.example/one.jpg" }],
                },
              ],
            },
          },
        }
      },
    },
  })

  const rejected = await handler(
    new Request("https://proxy.example/identify", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(request),
    }),
  )
  assert.equal(rejected.status, 401)
  assert.equal(providerCalls, 0)

  const accepted = await handler(
    new Request("https://proxy.example/identify", {
      method: "POST",
      headers: {
        authorization: "Bearer local-test-token",
        "content-type": "application/json",
      },
      body: JSON.stringify(request),
    }),
  )
  assert.equal(accepted.status, 200)
  assert.equal(accepted.headers.get("cache-control"), "no-store")
  assert.deepEqual(await accepted.json(), {
    kind: "candidates",
    candidates: [
      {
        publicContentId: "species-one",
        koreanName: "몬스테라",
        commonName: "몬스테라",
        scientificName: "Monstera deliciosa",
        confidence: 0.95,
        thumbnailUrl: "https://images.example/one.jpg",
      },
    ],
  })
  assert.equal(providerCalls, 1)
})
