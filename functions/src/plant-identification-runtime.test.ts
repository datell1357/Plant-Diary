import assert from "node:assert/strict";
import test from "node:test";
import { TimeoutError } from "ky";
import {
  ClaimFencedIdentificationRequestStore,
  IdentificationRuntimeError,
  PlantIdHttpClient,
} from "./plant-identification-runtime.js";
import type { PlantIdTransport } from "./plant-identification-runtime.js";
import { PlantIdentificationError } from "./plant-identification.js";
import {
  IDENTIFICATION_CLAIM_LEASE_MILLIS,
  IDENTIFICATION_DISCLOSURE_VERSION,
  IDENTIFICATION_ORIGINAL_RETENTION_MILLIS,
} from "./identification-authorization.js";
import { MemoryIdentificationAuthorizationRepository } from "./identification-authorization-test-fixture.test.js";
import type {
  PrivateMediaReservation,
  PrivateMediaReservationRepository,
  ResolvePrivateMediaCommand,
  ResolvedPrivateMedia,
} from "./private-media-contract.js";

const OWNER = "user-a";
const REQUEST = "request_12345678";
const MEDIA = { reservationId: "reservation_12345678", generation: "7" };

class MemoryMedia implements PrivateMediaReservationRepository {
  async resolve(command: ResolvePrivateMediaCommand): Promise<ResolvedPrivateMedia | null> {
    if (command.ownerUid !== OWNER || command.reference.reservationId !== MEDIA.reservationId) return null;
    return {
      reference: MEDIA, ownerUid: OWNER, mediaKind: "IDENTIFICATION_ORIGINAL",
      objectPath: "private-media-v2/reservation_12345678", contentType: "image/webp", byteSize: 3,
    };
  }
  async load(): Promise<PrivateMediaReservation | null> { return null; }
  async reserve(value: PrivateMediaReservation): Promise<PrivateMediaReservation> { return value; }
  async commit(): Promise<PrivateMediaReservation> { throw new Error("not used"); }
  async claimExpiredReservedUpload(): Promise<PrivateMediaReservation | null> { return null; }
  async listOwner(): Promise<readonly PrivateMediaReservation[]> { return []; }
  async markSealed(): Promise<void> { throw new Error("not used"); }
  async shouldDeleteFinalized(): Promise<boolean> { return false; }
}

async function request(repository: MemoryIdentificationAuthorizationRepository, now = 1_000) {
  return repository.create({
    ownerUid: OWNER, requestId: REQUEST, mediaReference: MEDIA,
    disclosureVersion: IDENTIFICATION_DISCLOSURE_VERSION, nowMillis: now,
  });
}

function client(status: number): PlantIdHttpClient {
  const transport: PlantIdTransport = { async post() { return { status, body: { providerDiagnostic: "hidden" } }; } };
  return new PlantIdHttpClient("fixture-key", transport);
}

for (const [status, reason] of [[429, "rate_limited"], [503, "provider_unavailable"]] as const) {
  test(`Plant.id HTTP ${status} maps to ${reason} without exposing provider data`, async () => {
    await assert.rejects(
      client(status).identify(Buffer.from("photo"), "image/webp"),
      (error: unknown) => error instanceof PlantIdentificationError && error.reason === reason && !error.message.includes("Diagnostic"),
    );
  });
}

test("Plant.id timeout maps to actionable failure", async () => {
  const transport: PlantIdTransport = { async post() { throw new TimeoutError(new Request("https://fixture.invalid")); } };
  await assert.rejects(
    new PlantIdHttpClient("fixture-key", transport).identify(Buffer.from("photo"), "image/webp"),
    (error: unknown) => error instanceof PlantIdentificationError && error.reason === "timeout",
  );
});

test("expired, unapproved, and cancelled requests make zero provider calls", async () => {
  for (const status of ["expired", "unapproved", "cancelled"] as const) {
    const repository = new MemoryIdentificationAuthorizationRepository();
    const created = await request(repository);
    if (status === "unapproved") repository.records.set(REQUEST, { ...created, status: "PENDING", claimOperationKey: "other_operation_12345678", claimExpiresAtMillis: 9_999 });
    if (status === "cancelled") await repository.cancel({ ownerUid: OWNER, requestId: REQUEST, nowMillis: 1_001 });
    const now = status === "expired" ? created.hardExpiresAtMillis : 1_001;
    const store = new ClaimFencedIdentificationRequestStore(repository, new MemoryMedia(), () => now);
    let calls = 0;
    await assert.rejects(
      store.runOnce(OWNER, REQUEST, "operation_12345678", async () => { calls += 1; return { kind: "no_candidates" }; }),
      IdentificationRuntimeError,
    );
    assert.equal(calls, 0, status);
  }
});

test("lease expiry lets B start while stale A cannot cross the send boundary or finalize", async () => {
  const repository = new MemoryIdentificationAuthorizationRepository();
  await request(repository);
  const claimA = await repository.claim({ ownerUid: OWNER, requestId: REQUEST, operationKey: "operation_A_12345678", nowMillis: 1_000 });
  assert.equal(claimA.kind, "start");
  if (claimA.kind !== "start") return;
  const claimB = await repository.claim({
    ownerUid: OWNER, requestId: REQUEST, operationKey: "operation_B_12345678",
    nowMillis: 1_000 + IDENTIFICATION_CLAIM_LEASE_MILLIS,
  });
  assert.equal(claimB.kind, "start");
  await assert.rejects(
    repository.markSending({ ownerUid: OWNER, requestId: REQUEST, operationKey: "operation_A_12345678", claimGeneration: claimA.request.claimGeneration, nowMillis: 1_000 + IDENTIFICATION_CLAIM_LEASE_MILLIS }),
    /stale/,
  );
  await assert.rejects(
    repository.finalize({
      ownerUid: OWNER, requestId: REQUEST, operationKey: "operation_A_12345678",
      claimGeneration: claimA.request.claimGeneration, status: "NO_CANDIDATES", result: { kind: "no_candidates" }, nowMillis: 2_000,
    }),
    /stale/,
  );
});

test("final provider-send transition fences exact claim and hard expiry without invoking the provider", async () => {
  for (const boundary of ["claim", "hard"] as const) {
    const repository = new MemoryIdentificationAuthorizationRepository();
    const created = await request(repository);
    const boundaryNow = boundary === "claim"
      ? 1_000 + IDENTIFICATION_CLAIM_LEASE_MILLIS
      : created.hardExpiresAtMillis;
    let clockReads = 0;
    const store = new ClaimFencedIdentificationRequestStore(
      repository,
      new MemoryMedia(),
      () => clockReads++ === 0 ? 1_000 : boundaryNow,
    );
    let calls = 0;
    await assert.rejects(
      store.runOnce(OWNER, REQUEST, "operation_12345678", async () => { calls += 1; return { kind: "no_candidates" }; }),
      IdentificationRuntimeError,
    );
    assert.equal(calls, 0, boundary);
    assert.equal((await repository.load(OWNER, REQUEST))?.sendState, "NOT_SENT", boundary);
  }
});

test("final provider-send transition accepts expiry minus one from the injected runtime clock", async () => {
  const repository = new MemoryIdentificationAuthorizationRepository();
  await request(repository);
  let clockReads = 0;
  const store = new ClaimFencedIdentificationRequestStore(
    repository,
    new MemoryMedia(),
    () => clockReads++ === 0 ? 1_000 : 1_000 + IDENTIFICATION_CLAIM_LEASE_MILLIS - 1,
  );
  let calls = 0;
  assert.deepEqual(
    await store.runOnce(OWNER, REQUEST, "operation_12345678", async () => { calls += 1; return { kind: "no_candidates" }; }),
    { kind: "no_candidates" },
  );
  assert.equal(calls, 1);
});

test("ambiguous post-send retry never transmits twice while pre-send lease retry remains safe", async () => {
  const repository = new MemoryIdentificationAuthorizationRepository();
  await request(repository);
  let now = 1_000;
  const store = new ClaimFencedIdentificationRequestStore(repository, new MemoryMedia(), () => now);
  let sends = 0;
  await assert.rejects(
    store.runOnce(OWNER, REQUEST, "operation_A_12345678", async () => {
      sends += 1;
      throw new Error("provider response ambiguous");
    }),
    /ambiguous/,
  );
  now += IDENTIFICATION_CLAIM_LEASE_MILLIS;
  await assert.rejects(
    store.runOnce(OWNER, REQUEST, "operation_B_12345678", async () => { sends += 1; return { kind: "no_candidates" }; }),
    IdentificationRuntimeError,
  );
  assert.equal(sends, 1);

  const retryable = new MemoryIdentificationAuthorizationRepository();
  await request(retryable);
  await retryable.claim({ ownerUid: OWNER, requestId: REQUEST, operationKey: "operation_A_12345678", nowMillis: 1_000 });
  const preSend = new ClaimFencedIdentificationRequestStore(
    retryable, new MemoryMedia(), () => 1_000 + IDENTIFICATION_CLAIM_LEASE_MILLIS,
  );
  let safeSends = 0;
  assert.deepEqual(
    await preSend.runOnce(OWNER, REQUEST, "operation_B_12345678", async () => { safeSends += 1; return { kind: "no_candidates" }; }),
    { kind: "no_candidates" },
  );
  assert.equal(safeSends, 1);
});

test("terminal result writes exact terminal and 24-hour retention timestamps", async () => {
  const repository = new MemoryIdentificationAuthorizationRepository();
  await request(repository);
  const terminalAt = 9_876;
  const store = new ClaimFencedIdentificationRequestStore(repository, new MemoryMedia(), () => terminalAt);
  await store.runOnce(OWNER, REQUEST, "operation_12345678", async () => ({ kind: "no_candidates" }));
  const stored = await repository.load(OWNER, REQUEST);
  assert.equal(stored?.terminalAtMillis, terminalAt);
  assert.equal(stored?.retentionExpiresAtMillis, terminalAt + IDENTIFICATION_ORIGINAL_RETENTION_MILLIS);
});
