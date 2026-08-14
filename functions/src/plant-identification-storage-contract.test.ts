import assert from "node:assert/strict";
import test from "node:test";
import {
  FirestoreIdentificationRequestStore,
  IdentificationRuntimeError,
} from "./plant-identification-runtime.js";
import { executePlantIdentification } from "./plant-identification.js";

type RequestData = Record<string, unknown>;

class ReferenceFixture {
  constructor(readonly data: RequestData) {}

  async update(value: RequestData): Promise<void> {
    Object.assign(this.data, value);
  }
}

class FirestoreFixture {
  readonly reference: ReferenceFixture;

  constructor(data: RequestData) {
    this.reference = new ReferenceFixture(data);
  }

  doc(): ReferenceFixture {
    return this.reference;
  }

  async runTransaction<T>(operation: (transaction: {
    get(reference: ReferenceFixture): Promise<{ exists: boolean; data(): RequestData }>;
    update(reference: ReferenceFixture, value: RequestData): void;
  }) => Promise<T>): Promise<T> {
    return operation({
      get: async (reference) => ({ exists: true, data: () => reference.data }),
      update: (reference, value) => Object.assign(reference.data, value),
    });
  }
}

function requestData(): RequestData {
  return {
    ownerUid: "user-a",
    temporaryOriginalPath: "identification-originals/user-a/request_12345678/original.webp",
  };
}

function storedCandidates(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    publicContentId: `species-${index}`,
    koreanName: null,
    commonName: null,
    scientificName: `Species ${index}`,
    confidence: 1 - index / 10,
    thumbnailUrl: null,
  }));
}

test("production store replays one to three stored candidates", async () => {
  for (const count of [1, 2, 3]) {
    // Given
    const firestore = new FirestoreFixture({
      ...requestData(),
      identificationResult: { kind: "candidates", candidates: storedCandidates(count) },
    });
    const store = new FirestoreIdentificationRequestStore(firestore as never);

    // When
    const result = await store.runOnce(
      "user-a",
      "request_12345678",
      "operation_12345678",
      async () => ({ kind: "no_candidates" }),
    );

    // Then
    assert.equal(result.kind, "candidates");
    if (result.kind === "candidates") assert.equal(result.candidates.length, count);
  }
});

test("production store rejects four stored candidates", async () => {
  // Given
  const firestore = new FirestoreFixture({
    ...requestData(),
    identificationResult: { kind: "candidates", candidates: storedCandidates(4) },
  });
  const store = new FirestoreIdentificationRequestStore(firestore as never);

  // When / Then
  await assert.rejects(
    store.runOnce(
      "user-a",
      "request_12345678",
      "operation_12345678",
      async () => ({ kind: "no_candidates" }),
    ),
    (error: unknown) => error instanceof IdentificationRuntimeError
      && error.reason === "malformed_state",
  );
});

test("production store persists only the confidence-descending top three provider candidates", async () => {
  // Given
  const firestore = new FirestoreFixture(requestData());
  const store = new FirestoreIdentificationRequestStore(firestore as never);
  const provider = {
    async identify() {
      return {
        result: {
          classification: {
            suggestions: [
              { id: "species-low", name: "Low", probability: 0.1 },
              { id: "species-high", name: "High", probability: 0.9 },
              { id: "species-middle", name: "Middle", probability: 0.6 },
              { id: "species-fourth", name: "Fourth", probability: 0.4 },
            ],
          },
        },
      };
    },
  };

  // When
  const result = await executePlantIdentification(
    { uid: "user-a" },
    { requestId: "request_12345678", idempotencyKey: "operation_12345678" },
    store,
    provider,
  );

  // Then
  assert.equal(result.kind, "candidates");
  if (result.kind !== "candidates") return;
  assert.deepEqual(
    result.candidates.map((candidate) => candidate.publicContentId),
    ["species-high", "species-middle", "species-fourth"],
  );
  assert.deepEqual(firestore.reference.data.identificationResult, result);
});
