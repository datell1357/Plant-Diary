import assert from "node:assert/strict";
import test from "node:test";
import {
  executePlantIdentification,
  type IdentificationRequestStore,
  type IdentificationResponse,
  PlantIdentificationError,
  type PlantIdProvider,
  type StoredIdentificationRequest,
} from "./plant-identification.js";

const input = {
  requestId: "request_12345678",
  idempotencyKey: "operation_12345678",
};

const rawSuggestions = [
  {
    id: "species-low",
    name: "Ficus lyrata",
    probability: 0.21,
    details: { common_names: ["떡갈고무나무"] },
    similar_images: [{ url: "https://example.com/low.jpg" }],
  },
  {
    id: "species-high",
    name: "Monstera deliciosa",
    probability: 0.93,
    details: { common_names: ["몬스테라"] },
    similar_images: [{ url: "https://example.com/high.jpg" }],
  },
  {
    id: "species-middle",
    name: "Epipremnum aureum",
    probability: 0.67,
    details: { common_names: ["스킨답서스"] },
  },
  { id: "species-four", name: "Philodendron hederaceum", probability: 0.4 },
];

class MemoryRequestStore implements IdentificationRequestStore {
  readonly calls: string[] = [];
  private readonly results = new Map<string, Promise<IdentificationResponse>>();

  constructor(
    private readonly request: StoredIdentificationRequest = {
      ownerUid: "user-a",
      media: {
        reference: { reservationId: "reservation_12345678", generation: "7" },
        ownerUid: "user-a",
        mediaKind: "IDENTIFICATION_ORIGINAL",
        objectPath: "private-media-v2/reservation_12345678",
        contentType: "image/webp",
        byteSize: 3,
      },
    },
  ) {}

  async runOnce(
    ownerUid: string,
    requestId: string,
    idempotencyKey: string,
    operation: (request: StoredIdentificationRequest) => Promise<IdentificationResponse>,
  ): Promise<IdentificationResponse> {
    this.calls.push(idempotencyKey);
    assert.equal(requestId, input.requestId);
    const existing = this.results.get(idempotencyKey);
    if (existing !== undefined) return existing;
    const created = operation(this.request);
    this.results.set(idempotencyKey, created);
    return created;
  }
}

function provider(result: unknown): PlantIdProvider {
  return { async identify() { return result; } };
}

test("normalizes provider candidates by descending confidence and limits the result", async () => {
  // Given
  const store = new MemoryRequestStore();

  // When
  const result = await executePlantIdentification(
    { uid: "user-a" },
    input,
    store,
    provider({ result: { classification: { suggestions: rawSuggestions } } }),
  );

  // Then
  assert.equal(result.kind, "candidates");
  if (result.kind !== "candidates") return;
  assert.deepEqual(
    result.candidates.map((candidate) => ({
      id: candidate.publicContentId,
      koreanName: candidate.koreanName,
      scientificName: candidate.scientificName,
      confidence: candidate.confidence,
      thumbnailUrl: candidate.thumbnailUrl,
    })),
    [
      {
        id: "species-high",
        koreanName: "몬스테라",
        scientificName: "Monstera deliciosa",
        confidence: 0.93,
        thumbnailUrl: "https://example.com/high.jpg",
      },
      {
        id: "species-middle",
        koreanName: "스킨답서스",
        scientificName: "Epipremnum aureum",
        confidence: 0.67,
        thumbnailUrl: null,
      },
      {
        id: "species-four",
        koreanName: null,
        scientificName: "Philodendron hederaceum",
        confidence: 0.4,
        thumbnailUrl: null,
      },
    ],
  );
});

test("returns no candidates when the provider returns an empty suggestion list", async () => {
  // Given
  const store = new MemoryRequestStore();

  // When
  const result = await executePlantIdentification(
    { uid: "user-a" }, input, store, provider({ result: { classification: { suggestions: [] } } }),
  );

  // Then
  assert.deepEqual(result, { kind: "no_candidates" });
});

test("rejects malformed callable input as invalid argument", async () => {
  await assert.rejects(
    executePlantIdentification(
      { uid: "user-a" },
      { requestId: "../owner-b", idempotencyKey: input.idempotencyKey },
      new MemoryRequestStore(),
      provider({ result: { classification: { suggestions: [] } } }),
    ),
    (error: unknown) => error instanceof PlantIdentificationError && error.reason === "invalid_argument",
  );
});

test("rejects malformed provider candidates instead of leaking partial data", async () => {
  // Given
  const store = new MemoryRequestStore();

  // When
  const result = await executePlantIdentification(
    { uid: "user-a" }, input, store, provider({ result: { classification: { suggestions: [{ id: 7 }] } } }),
  );

  // Then
  assert.deepEqual(result, { kind: "failed", reason: "malformed_response" });
});

for (const [providerReason, responseReason] of [
  ["timeout", "timeout"],
  ["rate_limited", "rate_limited"],
  ["provider_unavailable", "provider_unavailable"],
] as const) {
  test(`maps ${providerReason} to an actionable public failure`, async () => {
    // Given
    const store = new MemoryRequestStore();
    const failingProvider: PlantIdProvider = {
      async identify() { throw new PlantIdentificationError(providerReason); },
    };

    // When
    const result = await executePlantIdentification(
      { uid: "user-a" }, input, store, failingProvider,
    );

    // Then
    assert.deepEqual(result, { kind: "failed", reason: responseReason });
  });
}

test("deduplicates concurrent submissions with the same idempotency key", async () => {
  // Given
  const store = new MemoryRequestStore();
  let providerCalls = 0;
  const delayedProvider: PlantIdProvider = {
    async identify() {
      providerCalls += 1;
      return { result: { classification: { suggestions: rawSuggestions } } };
    },
  };

  // When
  const [first, second] = await Promise.all([
    executePlantIdentification({ uid: "user-a" }, input, store, delayedProvider),
    executePlantIdentification({ uid: "user-a" }, input, store, delayedProvider),
  ]);

  // Then
  assert.deepEqual(second, first);
  assert.equal(providerCalls, 1);
});

test("rejects unauthenticated and cross-owner requests before calling Plant.id", async () => {
  // Given
  const store = new MemoryRequestStore();
  let providerCalls = 0;
  const countingProvider: PlantIdProvider = {
    async identify() {
      providerCalls += 1;
      return { result: { classification: { suggestions: rawSuggestions } } };
    },
  };

  // When / Then
  await assert.rejects(
    executePlantIdentification(null, input, store, countingProvider),
    (error: unknown) =>
      error instanceof PlantIdentificationError && error.reason === "unauthenticated",
  );
  await assert.rejects(
    executePlantIdentification({ uid: "user-b" }, input, store, countingProvider),
    (error: unknown) =>
      error instanceof PlantIdentificationError && error.reason === "permission_denied",
  );
  assert.equal(providerCalls, 0);
});
