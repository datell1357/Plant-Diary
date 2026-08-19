import assert from "node:assert/strict";
import test from "node:test";
import {
  FirebaseWateringPushSender,
  WATERING_CLAIM_RECOVERY_CURSOR,
  WATERING_DELIVERY_CLAIMS_COLLECTION,
  runWateringDeliveryScan,
  selectWateringAttempt,
  type DueWateringAttempt,
  type EndpointDeliveryResult,
  type WateringDeliveryCandidate,
  type WateringDeliveryStore,
  type WateringEndpointTarget,
  type WateringPushSender,
} from "./watering-notifications.js";

const candidate = (overrides: Partial<WateringDeliveryCandidate> = {}): WateringDeliveryCandidate => ({
  ownerUid: "user-a",
  plantId: "plant-a",
  plantName: "몬스테라",
  dueDate: "2026-08-12",
  zoneId: "Asia/Seoul",
  globalEnabled: true,
  defaultTime: "09:00",
  plantEnabled: null,
  timeOverride: null,
  ...overrides,
});

class FakeStore implements WateringDeliveryStore {
  candidates: WateringDeliveryCandidate[] = [];
  endpoints: WateringEndpointTarget[] = [{ endpointIds: ["endpoint-a"], token: "fcm-a" }];
  readonly claims = new Map<
    string,
    {
      id: string;
      expiresAt: Date;
      state:
        | "CLAIMED"
        | "AUTHORIZED_PRE_SEND"
        | "SEND_MAY_HAVE_OCCURRED"
        | "SEND_UNKNOWN";
    }
  >();
  readonly sent = new Set<string>();
  readonly finalized: Array<{ attempt: DueWateringAttempt; results: readonly EndpointDeliveryResult[] }> = [];
  readonly released: Array<{
    claimId: string;
    results?: readonly EndpointDeliveryResult[];
  }> = [];
  readonly deleted: string[] = [];
  readonly ambiguousFinalizations: string[] = [];
  cursorOffset = 0;
  valid = true;
  failFinalizeOnce = false;
  listedLimits: number[] = [];

  async recoverExpiredClaims(_now: Date, _limit: number): Promise<void> {}

  async listCandidates(_now: Date, limit: number): Promise<readonly WateringDeliveryCandidate[]> {
    this.listedLimits.push(limit);
    const page = this.candidates.slice(this.cursorOffset, this.cursorOffset + limit);
    this.cursorOffset =
      this.cursorOffset + page.length >= this.candidates.length
        ? 0
        : this.cursorOffset + page.length;
    return page;
  }

  async claim(attempt: DueWateringAttempt, expiresAt: Date): Promise<string | null> {
    if (this.sent.has(attempt.deduplicationKey)) return null;
    const prior = this.claims.get(attempt.deduplicationKey);
    if (prior?.state === "SEND_UNKNOWN") return null;
    if (prior !== undefined && prior.expiresAt > attempt.evaluatedAt) return null;
    if (prior?.state === "SEND_MAY_HAVE_OCCURRED") {
      prior.state = "SEND_UNKNOWN";
      return null;
    }
    const id = `claim-${this.claims.size + 1}`;
    this.claims.set(attempt.deduplicationKey, { id, expiresAt, state: "CLAIMED" });
    return id;
  }

  async eligibleEndpoints(_ownerUid: string): Promise<readonly WateringEndpointTarget[]> {
    return this.endpoints;
  }

  async revalidateAndMarkSending(
    attempt: DueWateringAttempt,
    _claimId: string,
    endpoints: readonly WateringEndpointTarget[],
  ): Promise<readonly WateringEndpointTarget[] | null> {
    if (!this.valid) return null;
    const claim = this.claims.get(attempt.deduplicationKey);
    if (claim === undefined) return null;
    claim.state = "AUTHORIZED_PRE_SEND";
    return endpoints;
  }

  async markSendMayHaveOccurred(
    attempt: DueWateringAttempt,
    _claimId: string,
    _endpoints: readonly WateringEndpointTarget[],
  ): Promise<boolean> {
    const claim = this.claims.get(attempt.deduplicationKey);
    if (claim?.state !== "AUTHORIZED_PRE_SEND") return false;
    claim.state = "SEND_MAY_HAVE_OCCURRED";
    return true;
  }

  async markSendAmbiguous(attempt: DueWateringAttempt): Promise<void> {
    const claim = this.claims.get(attempt.deduplicationKey);
    if (claim !== undefined) claim.state = "SEND_UNKNOWN";
  }

  async finalizeSent(attempt: DueWateringAttempt, _claimId: string, results: readonly EndpointDeliveryResult[]): Promise<void> {
    if (this.failFinalizeOnce) {
      this.failFinalizeOnce = false;
      throw new Error("ambiguous receipt persistence");
    }
    this.sent.add(attempt.deduplicationKey);
    this.finalized.push({ attempt, results });
  }

  async releaseSendAuthorization(): Promise<void> {}

  async markFinalizationAmbiguous(
    attempt: DueWateringAttempt,
    claimId: string,
  ): Promise<void> {
    this.ambiguousFinalizations.push(claimId);
    const claim = this.claims.get(attempt.deduplicationKey);
    if (claim !== undefined) claim.state = "SEND_UNKNOWN";
  }

  async releaseClaim(
    _attempt: DueWateringAttempt,
    claimId: string,
    results?: readonly EndpointDeliveryResult[],
  ): Promise<void> {
    this.released.push({ claimId, ...(results === undefined ? {} : { results }) });
    const entry = [...this.claims.entries()].find(([, claim]) => claim.id === claimId);
    if (entry === undefined) return;
    if (results === undefined) this.claims.delete(entry[0]);
    else entry[1].state = "SEND_UNKNOWN";
  }

  async deleteEndpoints(
    _ownerUid: string,
    results: readonly EndpointDeliveryResult[],
  ): Promise<void> {
    this.deleted.push(...results.flatMap((result) => result.endpointIds));
  }
}

class FakeSender implements WateringPushSender {
  readonly attempts: DueWateringAttempt[] = [];
  results: EndpointDeliveryResult[] = [{ endpointIds: ["endpoint-a"], token: "fcm-a", success: true, permanent: false }];

  async send(attempt: DueWateringAttempt, _endpoints: readonly WateringEndpointTarget[]): Promise<readonly EndpointDeliveryResult[]> {
    this.attempts.push(attempt);
    return this.results;
  }
}

test("orphan recovery uses fixed server-only collection and cursor contracts", () => {
  assert.equal(WATERING_DELIVERY_CLAIMS_COLLECTION, "notificationDeliveryClaims");
  assert.equal(
    WATERING_CLAIM_RECOVERY_CURSOR,
    "notificationRuntime/wateringClaimRecovery",
  );
});

test("scheduler selects attempts using the authoritative global timezone, never a schedule timezone", () => {
  const instant = new Date("2026-08-12T00:05:00Z");
  assert.equal(selectWateringAttempt(candidate({ zoneId: "Asia/Seoul" }), instant)?.attempt, 0);
  assert.equal(selectWateringAttempt(candidate({ zoneId: "America/Los_Angeles" }), instant), null);
});

test("scheduler queries one bounded indexed candidate page", async () => {
  const store = new FakeStore();
  const sender = new FakeSender();
  store.candidates = Array.from({ length: 150 }, (_, index) => candidate({ plantId: `plant-${index}` }));

  await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:00:00Z"), 40);

  assert.deepEqual(store.listedLimits, [40]);
  assert.equal(sender.attempts.length, 40);
});

test("denied or disabled endpoints release the expiring claim without consuming an attempt", async () => {
  const store = new FakeStore();
  const sender = new FakeSender();
  store.candidates = [candidate()];
  store.endpoints = [];

  const first = await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:00:00Z"));
  store.endpoints = [{ endpointIds: ["endpoint-a"], token: "fcm-a" }];
  const retry = await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:06:00Z"));

  assert.deepEqual(first, { sent: 0, failed: 0, skipped: 1 });
  assert.equal(retry.sent, 1);
  assert.equal(store.finalized.length, 1);
});

test("completion or disable after claim and endpoint lookup cancels before send", async () => {
  const store = new FakeStore();
  const sender = new FakeSender();
  store.candidates = [candidate()];
  store.eligibleEndpoints = async () => {
    store.valid = false;
    return store.endpoints;
  };

  await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:00:00Z"));

  assert.equal(sender.attempts.length, 0);
  assert.equal(store.released.length, 1);
});

test("ambiguous receipt finalization never retries an externally observable send", async () => {
  const store = new FakeStore();
  const sender = new FakeSender();
  store.candidates = [candidate()];
  store.failFinalizeOnce = true;

  const ambiguous = await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:00:00Z"));
  const retry = await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:06:00Z"));
  const deduped = await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:07:00Z"));

  assert.equal(ambiguous.failed, 1);
  assert.equal(retry.sent, 0);
  assert.equal(deduped.skipped, 1);
  assert.equal(sender.attempts.length, 1);
  assert.deepEqual(store.ambiguousFinalizations, ["claim-1"]);
});

test("durable scan cursor reaches later eligible schedules instead of starving on the first page", async () => {
  const store = new FakeStore();
  const sender = new FakeSender();
  store.candidates = [
    candidate({ ownerUid: "no-endpoint-a", plantId: "plant-a" }),
    candidate({ ownerUid: "no-endpoint-b", plantId: "plant-b" }),
    candidate({ ownerUid: "user-later", plantId: "plant-later" }),
  ];
  store.eligibleEndpoints = async (ownerUid: string) =>
    ownerUid === "user-later" ? store.endpoints : [];

  await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:00:00Z"), 2);
  await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:01:00Z"), 2);

  assert.equal(sender.attempts.length, 1);
  assert.equal(sender.attempts[0]?.ownerUid, "user-later");
});

test("per-endpoint FCM results delete permanent failures and persist successes and transient failures", async () => {
  const store = new FakeStore();
  const sender = new FakeSender();
  store.candidates = [candidate()];
  store.endpoints = [
    { endpointIds: ["endpoint-a", "endpoint-duplicate"], token: "same-token" },
    { endpointIds: ["endpoint-b"], token: "other-token" },
  ];
  sender.results = [
    { endpointIds: ["endpoint-a", "endpoint-duplicate"], token: "same-token", success: false, permanent: true, errorCode: "messaging/registration-token-not-registered" },
    { endpointIds: ["endpoint-b"], token: "other-token", success: true, permanent: false },
  ];

  await runWateringDeliveryScan(store, sender, new Date("2026-08-12T00:00:00Z"));

  assert.deepEqual(store.deleted.sort(), ["endpoint-a", "endpoint-duplicate"]);
  assert.deepEqual(store.finalized[0]?.results, sender.results);
});

test("all endpoint failures remain on the expiring claim for endpoint-specific diagnostics", async () => {
  const store = new FakeStore();
  const sender = new FakeSender();
  store.candidates = [candidate()];
  sender.results = [
    {
      endpointIds: ["endpoint-a"],
      token: "fcm-a",
      success: false,
      permanent: false,
      errorCode: "messaging/internal-error",
    },
  ];

  const result = await runWateringDeliveryScan(
    store,
    sender,
    new Date("2026-08-12T00:00:00Z"),
  );

  assert.equal(result.failed, 1);
  assert.deepEqual(store.released[0]?.results, sender.results);
  assert.equal(store.finalized.length, 0);
});

test("Firebase sender maps each multicast response back to endpoint IDs", async () => {
  const messaging = {
    async sendEachForMulticast() {
      return {
        successCount: 1,
        failureCount: 1,
        responses: [
          { success: true },
          { success: false, error: { code: "messaging/registration-token-not-registered" } },
        ],
      };
    },
  };
  const sender = new FirebaseWateringPushSender(messaging as never);

  const results = await sender.send(
    { ...candidate(), attempt: 0, deduplicationKey: "key", evaluatedAt: new Date("2026-08-12T00:00:00Z") },
    [
      { endpointIds: ["endpoint-a"], token: "token-a" },
      { endpointIds: ["endpoint-b"], token: "token-b" },
    ],
    "123e4567-e89b-12d3-a456-426614174000",
  );

  assert.equal(results[0]?.success, true);
  assert.equal(results[1]?.permanent, true);
  assert.deepEqual(results[1]?.endpointIds, ["endpoint-b"]);
});
