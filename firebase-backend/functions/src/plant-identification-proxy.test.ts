import assert from "node:assert/strict"
import test from "node:test"
import {
  PlantIdHttpClient,
  type PlantIdTransport,
  PlantIdTransportTimeoutError,
} from "./plant-id-client.js"
import {
  canonicalPlantIdentificationRequest,
  createPlantIdentificationHTTPHandler,
  executePlantIdentification,
  type PlantIdentificationOperation,
  type PlantIdentificationProvider,
  type PlantIdentificationResponse,
  type PlantIdentificationResultStore,
  plantIdentificationRequestHash,
} from "./plant-identification-proxy.js"

const request = {
  requestID: "request_12345678",
  idempotencyKey: "operation_12345678",
  imagesBase64: [
    Buffer.from("photo-front").toString("base64"),
    Buffer.from("photo-leaf").toString("base64"),
    Buffer.from("photo-stem").toString("base64"),
  ],
}

class MemoryResultStore implements PlantIdentificationResultStore {
  readonly calls: string[] = []
  private readonly results = new Map<string, Promise<PlantIdentificationResponse>>()

  runOnce(
    operation: PlantIdentificationOperation,
    execute: () => Promise<PlantIdentificationResponse>,
  ): Promise<PlantIdentificationResponse> {
    this.calls.push(`${operation.ownerID}:${operation.requestID}:${operation.idempotencyKey}`)
    const operationKey = `${operation.ownerID}:${operation.idempotencyKey}`
    const existing = this.results.get(operationKey)
    if (existing !== undefined) return existing
    const created = execute()
    this.results.set(operationKey, created)
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

test("hashes the contract version, owner, request ID, and ordered image texts", () => {
  const operation = { ownerID: "user-a", requestID: request.requestID }
  assert.equal(
    canonicalPlantIdentificationRequest(operation, request.imagesBase64),
    JSON.stringify({
      contractVersion: 2,
      owner: "user-a",
      requestID: request.requestID,
      imagesBase64: request.imagesBase64,
    }),
  )
  const hash = plantIdentificationRequestHash(operation, request.imagesBase64)

  assert.match(hash, /^[a-f0-9]{64}$/)
  assert.notEqual(
    hash,
    plantIdentificationRequestHash({ ...operation, ownerID: "user-b" }, request.imagesBase64),
  )
  assert.notEqual(
    hash,
    plantIdentificationRequestHash(operation, [...request.imagesBase64].reverse()),
  )
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
      { ...request, imagesBase64: [] },
      new MemoryResultStore(),
      countingProvider,
    ),
    { code: "invalid-argument" },
  )
  await assert.rejects(
    executePlantIdentification(
      "user-a",
      { ...request, imagesBase64: Array(6).fill(request.imagesBase64[0]) },
      new MemoryResultStore(),
      countingProvider,
    ),
    { code: "invalid-argument" },
  )
  await assert.rejects(
    executePlantIdentification(
      "user-a",
      { ...request, imagesBase64: ["not base64"] },
      new MemoryResultStore(),
      countingProvider,
    ),
    { code: "invalid-argument" },
  )
  assert.equal(providerCalls, 0)
})

test("forwards every validated image to Plant.id once and in order", async () => {
  let receivedImages: readonly Buffer[] = []
  const result = await executePlantIdentification("user-a", request, new MemoryResultStore(), {
    identify: async (images) => {
      receivedImages = images
      return { result: { classification: { suggestions: [] } } }
    },
  })

  assert.deepEqual(result, { kind: "no_candidates" })
  assert.deepEqual(
    receivedImages.map((image) => image.toString()),
    ["photo-front", "photo-leaf", "photo-stem"],
  )
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

test("Plant.id transport receives one ordered multi-image classification request", async () => {
  let captured: Parameters<PlantIdTransport["post"]>[0] | undefined
  const transport: PlantIdTransport = {
    post: async (value) => {
      captured = value
      return { status: 201, body: { result: { classification: { suggestions: [] } } } }
    },
  }
  const client = new PlantIdHttpClient("private-key", transport)

  const result = await client.identify([
    Buffer.from([0xff, 0xd8, 0xff, 0x00]),
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  ])

  assert.deepEqual(result, { result: { classification: { suggestions: [] } } })
  assert.deepEqual(captured, {
    apiKey: "private-key",
    images: ["data:image/jpeg;base64,/9j/AA==", "data:image/png;base64,iVBORw0KGgo="],
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
      new PlantIdHttpClient("private-key", transport).identify([
        Buffer.from([0xff, 0xd8, 0xff, 0x00]),
      ]),
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
    new PlantIdHttpClient("private-key", transport).identify([
      Buffer.from([0xff, 0xd8, 0xff, 0x00]),
    ]),
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
      body: "not-json",
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
