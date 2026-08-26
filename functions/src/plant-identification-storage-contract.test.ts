import assert from "node:assert/strict";
import test from "node:test";
import { Timestamp } from "firebase-admin/firestore";
import {
  FirestoreIdentificationRequestStore,
  IdentificationRuntimeError,
} from "./plant-identification-runtime.js";
import { executePlantIdentification } from "./plant-identification.js";

type RequestData = Record<string, unknown>;

class ReferenceFixture {
  constructor(readonly path: string, readonly data: RequestData) {}

  async get() {
    return { exists: true, data: () => this.data, get: (field: string) => this.data[field] };
  }

  async update(value: RequestData): Promise<void> {
    Object.assign(this.data, value);
  }
}

class FirestoreFixture {
  readonly reference: ReferenceFixture;
  readonly lockReference = new ReferenceFixture("accountDeletionRequests/user-a", {});
  readonly reservationReference = new ReferenceFixture(
    "privateMediaReservations/reservation_12345678",
    reservationData(),
  );

  constructor(data: RequestData) {
    this.reference = new ReferenceFixture(
      "users/user-a/identificationRequests/request_12345678",
      data,
    );
  }

  doc(path: string): ReferenceFixture {
    if (path === this.lockReference.path) return this.lockReference;
    if (path === this.reservationReference.path) return this.reservationReference;
    return this.reference;
  }

  async runTransaction<T>(operation: (transaction: {
    get(reference: ReferenceFixture): Promise<{
      exists: boolean;
      data(): RequestData;
      get(field: string): unknown;
    }>;
    update(reference: ReferenceFixture, value: RequestData): void;
  }) => Promise<T>): Promise<T> {
    return operation({
      get: async (reference) => ({
        exists: reference !== this.lockReference,
        data: () => reference.data,
        get: (field) => reference.data[field],
      }),
      update: (reference, value) => Object.assign(reference.data, value),
    });
  }
}

function requestData(status = "APPROVED"): RequestData {
  const createdAt = Timestamp.fromMillis(Date.now());
  return {
    schemaVersion: 1,
    requestId: "request_12345678",
    ownerUid: "user-a",
    mediaReference: { reservationId: "reservation_12345678", generation: "7" },
    disclosureVersion: 1,
    status,
    claimGeneration: 0,
    claimOperationKey: null,
    claimExpiresAt: null,
    sendState: status === "APPROVED" ? "NOT_SENT" : "SENT",
    acknowledgedAt: createdAt,
    createdAt,
    hardExpiresAt: Timestamp.fromMillis(createdAt.toMillis() + 86_400_000),
    terminalAt: status === "CANDIDATES" ? createdAt : null,
    retentionExpiresAt: status === "CANDIDATES" ? Timestamp.fromMillis(createdAt.toMillis() + 86_400_000) : null,
  };
}

function reservationData(): RequestData {
  return {
    schemaVersion: 1,
    reservationId: "reservation_12345678",
    ownerUid: "user-a",
    mediaKind: "IDENTIFICATION_ORIGINAL",
    contentType: "image/webp",
    byteSize: 3,
    objectPath: "private-media-v2/reservation_12345678",
    identificationRequestId: "request_12345678",
    idempotencyKeyHash: "a".repeat(64),
    requestHash: "b".repeat(64),
    state: "COMMITTED",
    objectGeneration: "7",
    sealedGeneration: null,
    createdAt: Timestamp.fromMillis(1),
    expiresAt: Timestamp.fromMillis(2),
    committedAt: Timestamp.fromMillis(2),
    sealedAt: null,
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
      ...requestData("CANDIDATES"),
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
    ...requestData("CANDIDATES"),
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
