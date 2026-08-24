import assert from "node:assert/strict";
import test from "node:test";
import { Timestamp } from "firebase-admin/firestore";
import { TimeoutError } from "ky";
import {
  FirestoreIdentificationRequestStore,
  IdentificationRuntimeError,
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
  readonly lockReference = new FakeReference("accountDeletionRequests/user-a", {});
  readonly reservationReference: FakeReference;
  readonly requestedPaths: string[] = [];
  readonly writtenPaths: string[] = [];
  /** Transaction queue is mutable because serial execution is the fake's behavior. */
  private transactionTail: Promise<void> = Promise.resolve();

  constructor(
    data: FakeRequestData,
    private readonly exists = true,
    reservation: FakeRequestData = reservationData(),
  ) {
    this.reference = new FakeReference("users/user-a/identificationRequests/request_12345678", data);
    this.reservationReference = new FakeReference(
      "privateMediaReservations/reservation_12345678",
      reservation,
    );
  }

  doc(path: string) {
    this.requestedPaths.push(path);
    if (path === this.lockReference.path) return this.lockReference;
    if (path === this.reservationReference.path) return this.reservationReference;
    assert.equal(path, this.reference.path);
    return this.reference;
  }

  async runTransaction<T>(operation: (transaction: {
    get(reference: FakeReference): Promise<{
      exists: boolean;
      data(): FakeRequestData;
      get(field: string): unknown;
    }>;
    update(reference: FakeReference, value: FakeRequestData): void;
  }) => Promise<T>): Promise<T> {
    const previous = this.transactionTail;
    let release: () => void = () => undefined;
    this.transactionTail = new Promise<void>((resolve) => {
      release = resolve;
    });
    await previous;
    try {
      return await operation({
        get: async (reference) => ({
          exists: reference === this.lockReference
            ? false
            : reference === this.reservationReference
              ? true
              : this.exists,
          data: () => reference.data,
          get: (field) => reference.data[field],
        }),
        update: (reference, value) => {
          this.writtenPaths.push(reference.path);
          reference.updates.push(value);
          Object.assign(reference.data, value);
        },
      });
    } finally {
      release();
    }
  }
}

function requestData() {
  return {
    ownerUid: "user-a",
    mediaReference: { reservationId: "reservation_12345678", generation: "7" },
  };
}

function reservationData(ownerUid = "user-a"): FakeRequestData {
  return {
    schemaVersion: 1,
    reservationId: "reservation_12345678",
    ownerUid,
    mediaKind: "IDENTIFICATION_ORIGINAL",
    contentType: "image/webp",
    byteSize: 3,
    objectPath: "private-media-v2/reservation_12345678",
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

test("production store returns not_found without calling the provider when the request is missing", async () => {
  // Given
  const firestore = new FakeFirestore({}, false);
  const store = new FirestoreIdentificationRequestStore(firestore as never);
  let providerCalls = 0;

  // When / Then
  await assert.rejects(
    store.runOnce("user-a", "request_12345678", "operation_12345678", async () => {
      providerCalls += 1;
      return { kind: "no_candidates" };
    }),
    (error: unknown) => error instanceof IdentificationRuntimeError
      && error.reason === "not_found",
  );
  assert.equal(providerCalls, 0);
});

test("production store rejects raw and unowned media references before download", async () => {
  for (const firestore of [
    new FakeFirestore({
      ownerUid: "user-a",
      temporaryOriginalPath: "private-media-v2/reservation_12345678",
    }),
    new FakeFirestore(requestData(), true, reservationData("user-b")),
  ]) {
    const store = new FirestoreIdentificationRequestStore(firestore as never);
    let downloadCount = 0;

    await assert.rejects(
      store.runOnce("user-a", "request_12345678", "operation_12345678", async () => {
        downloadCount += 1;
        return { kind: "no_candidates" };
      }),
      IdentificationRuntimeError,
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
