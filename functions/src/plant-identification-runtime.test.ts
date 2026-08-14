import assert from "node:assert/strict";
import test from "node:test";
import { TimeoutError } from "ky";
import {
  FirestoreIdentificationRequestStore,
  PlantIdHttpClient,
} from "./plant-identification-runtime.js";
import type { PlantIdTransport } from "./plant-identification-runtime.js";
import { PlantIdentificationError } from "./plant-identification.js";

function client(status: number): PlantIdHttpClient {
  const transport: PlantIdTransport = {
    async post() {
      return { status, body: { secret_provider_diagnostic: "do not expose" } };
    },
  };
  return new PlantIdHttpClient("fixture-key", transport);
}

for (const [status, reason] of [
  [429, "rate_limited"],
  [503, "provider_unavailable"],
] as const) {
  test(`Plant.id HTTP ${status} maps to ${reason} without exposing the provider body`, async () => {
    // Given
    const fixture = client(status);

    // When / Then
    await assert.rejects(
      fixture.identify(Buffer.from("photo"), "image/webp"),
      (error: unknown) => error instanceof PlantIdentificationError
        && error.reason === reason
        && !error.message.includes("diagnostic"),
    );
  });
}

test("Plant.id HTTP timeout maps to an actionable timeout failure", async () => {
  // Given
  const transport: PlantIdTransport = {
    async post() {
      throw new TimeoutError(new Request("https://fixture.invalid"));
    },
  };
  const fixture = new PlantIdHttpClient("fixture-key", transport);

  // When / Then
  await assert.rejects(
    fixture.identify(Buffer.from("photo"), "image/webp"),
    (error: unknown) => error instanceof PlantIdentificationError && error.reason === "timeout",
  );
});

type FakeRequestData = Record<string, unknown>;

class FakeReference {
  readonly updates: FakeRequestData[] = [];

  constructor(readonly path: string, readonly data: FakeRequestData) {}

  async update(value: FakeRequestData) {
    this.updates.push(value);
    Object.assign(this.data, value);
  }
}

class FakeFirestore {
  readonly reference: FakeReference;
  readonly requestedPaths: string[] = [];
  readonly writtenPaths: string[] = [];

  constructor(data: FakeRequestData) {
    this.reference = new FakeReference("users/user-a/identificationRequests/request_12345678", data);
  }

  doc(path: string) {
    this.requestedPaths.push(path);
    assert.equal(path, this.reference.path);
    return this.reference;
  }

  async runTransaction<T>(operation: (transaction: {
    get(reference: FakeReference): Promise<{ exists: boolean; data(): FakeRequestData }>;
    update(reference: FakeReference, value: FakeRequestData): void;
  }) => Promise<T>): Promise<T> {
    return operation({
      get: async (reference) => ({ exists: true, data: () => reference.data }),
      update: (reference, value) => {
        this.writtenPaths.push(reference.path);
        reference.updates.push(value);
        Object.assign(reference.data, value);
      },
    });
  }
}

function requestData(path = "identification-originals/user-a/request_12345678/original.webp") {
  return { ownerUid: "user-a", temporaryOriginalPath: path };
}

test("production store rejects arbitrary and cross-owner original paths before download", async () => {
  for (const path of [
    "identification-originals/user-b/request_12345678/original.webp",
    "identification-originals/user-a/another_request/original.webp",
    "arbitrary/user-a/request_12345678/original.webp",
  ]) {
    const firestore = new FakeFirestore(requestData(path));
    const store = new FirestoreIdentificationRequestStore(firestore as never);
    let downloadCount = 0;

    await assert.rejects(
      store.runOnce("user-a", "request_12345678", "operation_12345678", async () => {
        downloadCount += 1;
        return { kind: "no_candidates" };
      }),
      (error: unknown) => error instanceof Error && error.message === "malformed_state",
    );
    assert.equal(downloadCount, 0);
  }
});

test("production store serializes concurrent claims and leaves personal plant storage unchanged", async () => {
  const firestore = new FakeFirestore(requestData());
  const store = new FirestoreIdentificationRequestStore(firestore as never);
  let operationCount = 0;

  const first = store.runOnce("user-a", "request_12345678", "operation_12345678", async () => {
    operationCount += 1;
    return { kind: "no_candidates" };
  });
  const second = store.runOnce("user-a", "request_12345678", "operation_12345678", async () => {
    operationCount += 1;
    return { kind: "no_candidates" };
  });

  assert.deepEqual(await Promise.all([first, second]), [
    { kind: "no_candidates" },
    { kind: "pending" },
  ]);
  assert.equal(operationCount, 1);
  assert.equal(
    [...firestore.requestedPaths, ...firestore.writtenPaths]
      .some((path) => path.includes("/personalPlants/")),
    false,
  );
});

test("production store reclaims a stuck pending claim with a fresh retry key", async () => {
  const firestore = new FakeFirestore({
    ...requestData(),
    identificationOperationKey: "operation_12345678",
    identificationOperationStartedAt: new Date("2026-08-14T00:00:00Z"),
  });
  const store = new FirestoreIdentificationRequestStore(
    firestore as never,
    () => new Date("2026-08-14T00:03:00Z"),
  );
  let operationCount = 0;

  const result = await store.runOnce(
    "user-a",
    "request_12345678",
    "operation_retry_12345678",
    async () => {
      operationCount += 1;
      return { kind: "no_candidates" };
    },
  );

  assert.deepEqual(result, { kind: "no_candidates" });
  assert.equal(operationCount, 1);
  assert.equal(
    firestore.reference.data.identificationOperationKey,
    "operation_retry_12345678",
  );
});
