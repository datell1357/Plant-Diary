import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import {
  ANALYTICS_EVENT_TTL_MILLIS,
  AnalyticsError,
  executeGetAnalyticsConsent,
  executeRecordClientAnalyticsEvents,
  executeSetAnalyticsConsent,
  type AnalyticsEventInput,
} from "./analytics.js";
import { AccountMutationLockedError } from "./account-mutation-lock.js";
import { FirestoreAccountDeletionStore } from "./firestore-account-deletion-store.js";
import {
  ANALYTICS_CLEANUP_LIMIT,
  ANALYTICS_REVOKE_PURGE_LIMIT,
  FirestoreAnalyticsStore,
  analyticsDailyAggregateExpiresAt,
  cleanupExpiredAnalyticsDailyAggregates,
  cleanupExpiredAnalyticsEvents,
  cleanupExpiredAnalyticsRetention,
  recordServerAnalyticsEvent,
} from "./firestore-analytics-store.js";

type Deferred<T> = Readonly<{
  promise: Promise<T>;
  resolve: (value: T) => void;
}>;

function deferred<T>(): Deferred<T> {
  let resolver: ((value: T) => void) | undefined;
  const promise = new Promise<T>((resolve) => {
    resolver = resolve;
  });
  return {
    promise,
    resolve(value) {
      if (resolver === undefined)
        throw new Error("Deferred resolver is unavailable");
      resolver(value);
    },
  };
}

async function bounded<T>(promise: Promise<T>, label: string): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(
      () => reject(new Error(`Timed out waiting for ${label}`)),
      5_000,
    );
    timer.unref();
  });
  try {
    return await Promise.race([promise, timeout]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

const projectId = "demo-planterior";
const ownerUid = "todo17-owner";
const nowMillis = Date.parse("2026-08-24T00:00:00.000Z");
const firstEvent: AnalyticsEventInput = {
  schemaVersion: 1,
  eventId: "11111111-1111-4111-8111-111111111111",
  eventName: "APP_SESSION_STARTED",
  consentRevision: 1,
};
const secondEvent: AnalyticsEventInput = {
  ...firstEvent,
  eventId: "22222222-2222-4222-8222-222222222222",
  eventName: "CARE_INFORMATION_VIEWED",
};

async function clearOwner(
  firestore: FirebaseFirestore.Firestore,
): Promise<void> {
  await Promise.all([
    firestore.recursiveDelete(firestore.doc(`users/${ownerUid}`)),
    firestore.recursiveDelete(
      firestore.doc(`accountDeletionReceipts/${ownerUid}`),
    ),
    firestore.doc(`accountDeletionRequests/${ownerUid}`).delete(),
  ]);
}

async function acceptDeletion(
  firestore: FirebaseFirestore.Firestore,
  requestId: string,
): Promise<void> {
  await new FirestoreAccountDeletionStore(firestore).request({
    record: {
      schemaVersion: 1,
      ownerUid,
      requestId,
      idempotencyKeyHash: "a".repeat(64),
      status: "RECEIVED",
      requestedAtMillis: nowMillis,
      scheduledForMillis: nowMillis + 60_000,
      nextAttemptAtMillis: nowMillis + 60_000,
      leaseExpiresAtMillis: null,
      completedAtMillis: null,
      completedScopes: [],
      failedScopes: [],
      claimGeneration: 0,
      analyticsResultEligible: false,
      analyticsRequestOutcome: "CONSENT_OFF",
      analyticsRecordedResultKeys: [],
      updatedAtMillis: nowMillis,
    },
  });
}

async function setConsent(
  store: FirestoreAnalyticsStore,
  granted: boolean,
  commandGeneration: number,
  operationId: string,
) {
  return executeSetAnalyticsConsent(
    { uid: ownerUid },
    { ownerUid, granted, commandGeneration, operationId },
    store,
  );
}

async function recordClient(
  store: FirestoreAnalyticsStore,
  events: readonly AnalyticsEventInput[],
) {
  return executeRecordClientAnalyticsEvents(
    { uid: ownerUid },
    { ownerUid, events },
    store,
  );
}

test("Android-exact consent wire defaults off and grant/revoke replay exact-next generations", async () => {
  const app = initializeApp({ projectId }, "todo17-consent-generation");
  const firestore = getFirestore(app);
  const store = new FirestoreAnalyticsStore(firestore, () =>
    Timestamp.fromMillis(nowMillis),
  );
  try {
    await clearOwner(firestore);
    assert.deepEqual(
      await executeGetAnalyticsConsent({ uid: ownerUid }, { ownerUid }, store),
      {
        schemaVersion: 1,
        granted: false,
        commandGeneration: 0,
        grantedAtEpochMillis: null,
        revokedAtEpochMillis: null,
      },
    );
    const granted = await setConsent(
      store,
      true,
      1,
      "analytics-consent-grant-0001",
    );
    assert.equal(granted.commandGeneration, 1);
    assert.equal(granted.grantedAtEpochMillis, nowMillis);
    assert.equal(granted.replayed, false);
    assert.equal(
      (await setConsent(store, true, 1, "analytics-consent-grant-0001"))
        .replayed,
      true,
    );
    await assert.rejects(
      setConsent(store, false, 1, "analytics-consent-grant-0001"),
      (error) =>
        error instanceof AnalyticsError && error.code === "already-exists",
    );
    for (const commandGeneration of [1, 3, Number.MAX_SAFE_INTEGER]) {
      await assert.rejects(
        setConsent(
          store,
          true,
          commandGeneration,
          `analytics-consent-wrong-${commandGeneration}`,
        ),
        (error) => error instanceof AnalyticsError && error.code === "aborted",
      );
    }
    const revoked = await setConsent(
      store,
      false,
      2,
      "analytics-consent-revoke-0002",
    );
    assert.equal(revoked.granted, false);
    assert.equal(revoked.commandGeneration, 2);
    assert.equal(revoked.revokedAtEpochMillis, nowMillis);
    const consent = await firestore
      .doc(`users/${ownerUid}/consents/analytics`)
      .get();
    assert.deepEqual(Object.keys(consent.data() ?? {}).sort(), [
      "commandGeneration",
      "granted",
      "grantedAt",
      "operationId",
      "revokedAt",
      "schemaVersion",
      "updatedAt",
    ]);
    assert.equal(consent.get("revision"), undefined);
    assert.equal(consent.get("consentRevision"), undefined);
  } finally {
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("account deletion system revoke fences collection and exact replay converges purge", async () => {
  const app = initializeApp({ projectId }, "todo17-account-deletion-analytics-revoke");
  const firestore = getFirestore(app);
  const store = new FirestoreAnalyticsStore(firestore, () =>
    Timestamp.fromMillis(nowMillis),
  );
  try {
    await clearOwner(firestore);
    await setConsent(store, true, 1, "analytics-deletion-grant-0001");
    await recordClient(store, [firstEvent, secondEvent]);
    await acceptDeletion(firestore, "analytics-deletion-request-0001");

    const first = await store.revokeForAccountDeletion(
      ownerUid,
      "analytics-deletion-system-revoke-0001",
    );
    await firestore.doc(`users/${ownerUid}/analyticsEvents/stranded`).set({
      schemaVersion: 1,
      eventName: "APP_SESSION_STARTED",
      consentRevision: 1,
      occurredAt: Timestamp.fromMillis(nowMillis),
      expiresAt: Timestamp.fromMillis(nowMillis + ANALYTICS_EVENT_TTL_MILLIS),
    });
    const replay = await store.revokeForAccountDeletion(
      ownerUid,
      "analytics-deletion-system-revoke-0001",
    );

    assert.equal(first.purgedEventCount, 2);
    assert.equal(replay.purgedEventCount, 1);
    assert.equal(
      (await firestore.collection(`users/${ownerUid}/analyticsEvents`).get()).size,
      0,
    );
    assert.equal((await store.getConsent(ownerUid)).granted, false);
    await assert.rejects(
      recordClient(store, [{ ...firstEvent, consentRevision: 2 }]),
      AccountMutationLockedError,
    );
  } finally {
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("grant based on consent read before deletion cannot re-enable after RECEIVED revoke", async () => {
  const app = initializeApp({ projectId }, "todo17-deletion-before-grant-race");
  const firestore = getFirestore(app);
  const grantStarted = deferred<void>();
  const continueGrant = deferred<void>();
  let paused = false;
  const store = new FirestoreAnalyticsStore(
    firestore,
    () => Timestamp.fromMillis(nowMillis),
    {
      async beforeConsentMutation(command) {
        if (command.operationId !== "analytics-race-stale-grant" || paused) return;
        paused = true;
        grantStarted.resolve();
        await continueGrant.promise;
      },
    },
  );
  try {
    await clearOwner(firestore);
    await setConsent(store, true, 1, "analytics-race-initial-grant");
    await setConsent(store, false, 2, "analytics-race-initial-revoke");
    const staleDisabled = await store.getConsent(ownerUid);

    const granting = setConsent(
      store,
      true,
      staleDisabled.commandGeneration + 1,
      "analytics-race-stale-grant",
    );
    await bounded(grantStarted.promise, "stale grant invocation");
    await acceptDeletion(firestore, "analytics-race-deletion-request");
    await store.revokeForAccountDeletion(
      ownerUid,
      "analytics-race-deletion-revoke",
    );
    continueGrant.resolve();

    await assert.rejects(
      bounded(granting, "blocked stale grant"),
      AccountMutationLockedError,
    );
    assert.equal((await store.getConsent(ownerUid)).granted, false);
  } finally {
    continueGrant.resolve();
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("grant transaction that commits before RECEIVED is followed by authoritative revoke and purge", async () => {
  const app = initializeApp({ projectId }, "todo17-grant-before-deletion-race");
  const firestore = getFirestore(app);
  const grantRead = deferred<void>();
  const continueGrant = deferred<void>();
  let paused = false;
  const store = new FirestoreAnalyticsStore(
    firestore,
    () => Timestamp.fromMillis(nowMillis),
    {
      async afterConsentStateRead(command) {
        if (command.operationId !== "analytics-race-winning-grant" || paused) return;
        paused = true;
        grantRead.resolve();
        await continueGrant.promise;
      },
    },
  );
  try {
    await clearOwner(firestore);
    await setConsent(store, true, 1, "analytics-race-before-grant");
    await setConsent(store, false, 2, "analytics-race-before-revoke");
    await firestore.doc(`users/${ownerUid}/analyticsEvents/stranded`).set({
      schemaVersion: 1,
      eventName: "APP_SESSION_STARTED",
      consentRevision: 1,
      occurredAt: Timestamp.fromMillis(nowMillis),
      expiresAt: Timestamp.fromMillis(nowMillis + ANALYTICS_EVENT_TTL_MILLIS),
    });

    const granting = setConsent(store, true, 3, "analytics-race-winning-grant");
    await bounded(grantRead.promise, "winning grant transaction read");
    const deleting = acceptDeletion(
      firestore,
      "analytics-race-winning-deletion",
    ).then(async () => {
      await store.revokeForAccountDeletion(
        ownerUid,
        "analytics-race-winning-revoke",
      );
    });
    continueGrant.resolve();
    await Promise.all([
      bounded(granting, "winning grant commit"),
      bounded(deleting, "following deletion revoke"),
    ]);

    assert.equal((await store.getConsent(ownerUid)).granted, false);
    assert.equal(
      (await firestore.collection(`users/${ownerUid}/analyticsEvents`).get())
        .size,
      0,
    );
  } finally {
    continueGrant.resolve();
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("accepted deletion revoke advances past a stale generation and exact replay stays disabled", async () => {
  const app = initializeApp({ projectId }, "todo17-stale-deletion-revoke");
  const firestore = getFirestore(app);
  const revokeRead = deferred<void>();
  const continueRevoke = deferred<void>();
  let paused = false;
  const pauseAfterRead = async (): Promise<void> => {
    if (paused) return;
    paused = true;
    revokeRead.resolve();
    await continueRevoke.promise;
  };
  const store = new FirestoreAnalyticsStore(
    firestore,
    () => Timestamp.fromMillis(nowMillis),
    {
      async beforeConsentMutation(command) {
        if (!command.granted) await pauseAfterRead();
      },
      beforeDeletionRevokeTransaction: pauseAfterRead,
    },
  );
  const operationId = "analytics-stale-deletion-revoke";
  try {
    await clearOwner(firestore);
    await setConsent(store, true, 1, "analytics-stale-initial-grant");
    await acceptDeletion(firestore, "analytics-stale-deletion-request");

    const revoking = store.revokeForAccountDeletion(ownerUid, operationId);
    await bounded(revokeRead.promise, "deletion revoke consent read");
    const at = Timestamp.fromMillis(nowMillis + 1);
    await Promise.all([
      firestore.doc(`users/${ownerUid}/consents/analytics`).set({
        schemaVersion: 1,
        granted: true,
        commandGeneration: 2,
        operationId: "analytics-concurrent-generation",
        grantedAt: at,
        revokedAt: null,
        updatedAt: at,
      }),
      firestore.doc(`users/${ownerUid}/analyticsEvents/concurrent-event`).set({
        schemaVersion: 1,
        eventName: "APP_SESSION_STARTED",
        consentRevision: 2,
        occurredAt: at,
        expiresAt: Timestamp.fromMillis(
          at.toMillis() + ANALYTICS_EVENT_TTL_MILLIS,
        ),
      }),
    ]);
    continueRevoke.resolve();

    const first = await bounded(revoking, "stale deletion revoke convergence");
    const replay = await store.revokeForAccountDeletion(ownerUid, operationId);
    const consent = await store.getConsent(ownerUid);
    assert.equal(first.purgedEventCount, 1);
    assert.equal(replay.purgedEventCount, 0);
    assert.equal(consent.granted, false);
    assert.equal(consent.commandGeneration, 3);
    assert.equal(
      (await firestore.collection(`users/${ownerUid}/analyticsEvents`).get())
        .size,
      0,
    );
  } finally {
    continueRevoke.resolve();
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("one transaction creates or replay-checks an ordered batch without partial outcomes", async () => {
  const app = initializeApp({ projectId }, "todo17-event-batch");
  const firestore = getFirestore(app);
  const store = new FirestoreAnalyticsStore(firestore, () =>
    Timestamp.fromMillis(nowMillis),
  );
  try {
    await clearOwner(firestore);
    await setConsent(store, true, 1, "analytics-consent-batch-grant");
    const first = await recordClient(store, [
      firstEvent,
      secondEvent,
      firstEvent,
    ]);
    assert.deepEqual(first.results, [
      { eventId: firstEvent.eventId, accepted: true, duplicate: false },
      { eventId: secondEvent.eventId, accepted: true, duplicate: false },
      { eventId: firstEvent.eventId, accepted: true, duplicate: true },
    ]);
    const replay = await recordClient(store, [secondEvent, firstEvent]);
    assert.deepEqual(replay.results, [
      { eventId: secondEvent.eventId, accepted: true, duplicate: true },
      { eventId: firstEvent.eventId, accepted: true, duplicate: true },
    ]);
    const firstDocument = await firestore
      .doc(`users/${ownerUid}/analyticsEvents/${firstEvent.eventId}`)
      .get();
    assert.deepEqual(Object.keys(firstDocument.data() ?? {}).sort(), [
      "consentRevision",
      "eventName",
      "expiresAt",
      "occurredAt",
      "schemaVersion",
    ]);
    assert.equal(firstDocument.get("occurredAt").toMillis(), nowMillis);
    assert.equal(
      firstDocument.get("expiresAt").toMillis(),
      nowMillis + ANALYTICS_EVENT_TTL_MILLIS,
    );
    const strandedId = "33333333-3333-4333-8333-333333333333";
    await assert.rejects(
      recordClient(store, [
        { ...firstEvent, eventName: "CARE_INFORMATION_VIEWED" },
        { ...firstEvent, eventId: strandedId },
      ]),
      (error) =>
        error instanceof AnalyticsError && error.code === "already-exists",
    );
    assert.equal(
      (
        await firestore
          .doc(`users/${ownerUid}/analyticsEvents/${strandedId}`)
          .get()
      ).exists,
      false,
    );
    await assert.rejects(
      recordClient(store, [
        { ...firstEvent, eventId: strandedId, consentRevision: 2 },
      ]),
      (error) =>
        error instanceof AnalyticsError && error.code === "failed-precondition",
    );
    await setConsent(store, false, 2, "analytics-consent-batch-revoke");
    await assert.rejects(
      recordClient(store, [
        { ...firstEvent, eventId: strandedId, consentRevision: 2 },
      ]),
      (error) =>
        error instanceof AnalyticsError && error.code === "failed-precondition",
    );
  } finally {
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("server helper stays single-event but requires the live consent generation", async () => {
  const app = initializeApp({ projectId }, "todo17-server-event");
  const firestore = getFirestore(app);
  const store = new FirestoreAnalyticsStore(firestore, () =>
    Timestamp.fromMillis(nowMillis),
  );
  const serverEvent = {
    ownerUid,
    schemaVersion: 1 as const,
    eventId: "44444444-4444-4444-8444-444444444444",
    eventName: "WATERING_NOTIFICATION_SENT" as const,
    consentRevision: 1,
  };
  try {
    await clearOwner(firestore);
    await assert.rejects(
      recordServerAnalyticsEvent(firestore, serverEvent, () =>
        Timestamp.fromMillis(nowMillis),
      ),
      (error) =>
        error instanceof AnalyticsError && error.code === "failed-precondition",
    );
    await setConsent(store, true, 1, "analytics-consent-server-event");
    const result = await recordServerAnalyticsEvent(
      firestore,
      serverEvent,
      () => Timestamp.fromMillis(nowMillis),
    );
    assert.equal(result.accepted, true);
    assert.equal(result.replayed, false);
  } finally {
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("deferred batch record versus revoke serializes and purge leaves no stale event", async () => {
  const app = initializeApp({ projectId }, "todo17-race");
  const firestore = getFirestore(app);
  const transactionRead = deferred<void>();
  const revokeStarted = deferred<void>();
  const continueRecord = deferred<void>();
  let paused = false;
  const racingStore = new FirestoreAnalyticsStore(
    firestore,
    () => Timestamp.fromMillis(nowMillis),
    {
      async beforeConsentMutation(command) {
        if (!command.granted) revokeStarted.resolve();
      },
      async afterEventConsentRead() {
        if (paused) return;
        paused = true;
        transactionRead.resolve();
        await continueRecord.promise;
      },
    },
  );
  try {
    await clearOwner(firestore);
    await setConsent(racingStore, true, 1, "analytics-consent-race-grant");
    const recording = recordClient(racingStore, [firstEvent, secondEvent]);
    await bounded(transactionRead.promise, "analytics batch consent read");
    const revoking = setConsent(
      racingStore,
      false,
      2,
      "analytics-consent-race-revoke",
    );
    await bounded(revokeStarted.promise, "analytics revoke invocation");
    continueRecord.resolve();
    const [recorded, revoked] = await Promise.all([
      bounded(recording, "analytics batch commit"),
      bounded(revoking, "analytics revoke convergence"),
    ]);
    assert.equal(recorded.results.length, 2);
    assert.equal(revoked.purgedEventCount, 2);
    assert.equal(
      (await firestore.collection(`users/${ownerUid}/analyticsEvents`).get())
        .size,
      0,
    );
  } finally {
    continueRecord.resolve();
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("revoke purges multiple pages and exact replay resumes after an incomplete deadline", async () => {
  const app = initializeApp({ projectId }, "todo17-revoke-pages");
  const firestore = getFirestore(app);
  const stableNow = () => Timestamp.fromMillis(nowMillis);
  try {
    await clearOwner(firestore);
    const initialStore = new FirestoreAnalyticsStore(firestore, stableNow);
    await setConsent(initialStore, true, 1, "analytics-consent-page-grant");
    const writes: Promise<FirebaseFirestore.WriteResult>[] = [];
    for (let index = 0; index < ANALYTICS_REVOKE_PURGE_LIMIT + 5; index += 1) {
      writes.push(
        firestore
          .doc(
            `users/${ownerUid}/analyticsEvents/${String(index).padStart(4, "0")}`,
          )
          .set({
            schemaVersion: 1,
            eventName: "APP_SESSION_STARTED",
            consentRevision: 1,
            occurredAt: Timestamp.fromMillis(nowMillis),
            expiresAt: Timestamp.fromMillis(
              nowMillis + ANALYTICS_EVENT_TTL_MILLIS,
            ),
          }),
      );
    }
    await Promise.all(writes);
    let clockCall = 0;
    const interruptedStore = new FirestoreAnalyticsStore(
      firestore,
      stableNow,
      {},
      () => {
        clockCall += 1;
        return clockCall < 3 ? 0 : 60_000;
      },
    );
    await assert.rejects(
      setConsent(interruptedStore, false, 2, "analytics-consent-page-revoke"),
      (error) =>
        error instanceof AnalyticsError && error.code === "deadline-exceeded",
    );
    assert.equal(
      (await firestore.collection(`users/${ownerUid}/analyticsEvents`).get())
        .size,
      5,
    );
    const replayed = await setConsent(
      initialStore,
      false,
      2,
      "analytics-consent-page-revoke",
    );
    assert.equal(replayed.replayed, true);
    assert.equal(replayed.purgedEventCount, 5);
    assert.equal(
      (await firestore.collection(`users/${ownerUid}/analyticsEvents`).get())
        .size,
      0,
    );
  } finally {
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("account deletion lock rejects consent and batch event transactions", async () => {
  const app = initializeApp({ projectId }, "todo17-lock");
  const firestore = getFirestore(app);
  const store = new FirestoreAnalyticsStore(firestore, () =>
    Timestamp.fromMillis(nowMillis),
  );
  try {
    await clearOwner(firestore);
    await firestore.doc(`accountDeletionRequests/${ownerUid}`).set({
      ownerUid,
      status: "PROCESSING",
      completedScopes: [],
    });
    await assert.rejects(
      setConsent(store, true, 1, "analytics-consent-locked"),
      /permanently frozen/,
    );
    await assert.rejects(
      recordClient(store, [firstEvent]),
      /permanently frozen/,
    );
  } finally {
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("aggregate cleanup preserves 34d23h59m and legacy data then removes the valid 35d bucket only", async () => {
  const app = initializeApp({ projectId }, "todo17-aggregate-retention-boundary");
  const firestore = getFirestore(app);
  const aggregates = firestore.collection("analyticsDailyAggregates");
  const expiry = analyticsDailyAggregateExpiresAt("2026-07-20");
  const aggregate = (date: string) => ({
    schemaVersion: 1,
    date,
    counts: { ACCOUNT_DELETION_FAILED: 1 },
    updatedAt: Timestamp.fromDate(new Date(`${date}T12:00:00.000Z`)),
    expiresAt: analyticsDailyAggregateExpiresAt(date),
  });
  try {
    await firestore.recursiveDelete(aggregates);
    await clearOwner(firestore);
    await Promise.all([
      aggregates.doc("2026-07-20").set(aggregate("2026-07-20")),
      aggregates.doc("2026-07-21").set(aggregate("2026-07-21")),
      aggregates.doc("legacy-without-expiry").set({
        schemaVersion: 1,
        date: "2026-07-19",
        counts: { ACCOUNT_DELETION_FAILED: 1 },
        updatedAt: Timestamp.fromMillis(nowMillis),
      }),
      aggregates.doc("malformed-expired").set({
        ...aggregate("2026-07-19"),
        date: "2026-07-18",
      }),
      firestore.doc(`users/${ownerUid}/analyticsEvents/expired-owner-event`).set({
        schemaVersion: 1,
        eventName: "APP_SESSION_STARTED",
        consentRevision: 1,
        occurredAt: Timestamp.fromMillis(nowMillis - ANALYTICS_EVENT_TTL_MILLIS),
        expiresAt: Timestamp.fromMillis(nowMillis),
      }),
    ]);

    assert.deepEqual(
      await cleanupExpiredAnalyticsDailyAggregates(
        firestore,
        Timestamp.fromMillis(expiry.toMillis() - 60_000),
      ),
      { scanned: 1, deleted: 0, skippedMalformed: 1 },
    );
    assert.equal((await aggregates.doc("2026-07-20").get()).exists, true);

    assert.deepEqual(
      await cleanupExpiredAnalyticsDailyAggregates(firestore, expiry),
      { scanned: 2, deleted: 1, skippedMalformed: 1 },
    );
    assert.equal((await aggregates.doc("2026-07-20").get()).exists, false);
    assert.equal((await aggregates.doc("2026-07-21").get()).exists, true);
    assert.equal((await aggregates.doc("legacy-without-expiry").get()).exists, true);
    assert.equal((await aggregates.doc("malformed-expired").get()).exists, true);
    assert.equal(
      (await firestore.doc(`users/${ownerUid}/analyticsEvents/expired-owner-event`).get())
        .exists,
      true,
    );
    assert.deepEqual(
      await cleanupExpiredAnalyticsRetention(firestore, expiry),
      {
        eventsDeleted: 1,
        aggregates: { scanned: 1, deleted: 0, skippedMalformed: 1 },
      },
    );
    assert.equal(
      (await firestore.doc(`users/${ownerUid}/analyticsEvents/expired-owner-event`).get())
        .exists,
      false,
    );
  } finally {
    await firestore.recursiveDelete(aggregates);
    await clearOwner(firestore);
    await deleteApp(app);
  }
});

test("aggregate cleanup converges over more than 200 expired buckets and exact retry is empty", async () => {
  const app = initializeApp({ projectId }, "todo17-aggregate-retention-pages");
  const firestore = getFirestore(app);
  const aggregates = firestore.collection("analyticsDailyAggregates");
  const cleanupAt = Timestamp.fromDate(new Date("2026-08-24T00:00:00.000Z"));
  try {
    await firestore.recursiveDelete(aggregates);
    const writes: Promise<FirebaseFirestore.WriteResult>[] = [];
    for (let index = 0; index < ANALYTICS_CLEANUP_LIMIT + 2; index += 1) {
      const bucketStart = Date.parse("2025-01-01T00:00:00.000Z") + index * 86_400_000;
      const date = new Date(bucketStart).toISOString().slice(0, 10);
      writes.push(
        aggregates.doc(date).set({
          schemaVersion: 1,
          date,
          counts: { ACCOUNT_DELETION_COMPLETED: 1 },
          updatedAt: Timestamp.fromMillis(bucketStart + 1),
          expiresAt: analyticsDailyAggregateExpiresAt(date),
        }),
      );
    }
    await Promise.all(writes);

    assert.deepEqual(
      await cleanupExpiredAnalyticsDailyAggregates(firestore, cleanupAt),
      { scanned: ANALYTICS_CLEANUP_LIMIT, deleted: ANALYTICS_CLEANUP_LIMIT, skippedMalformed: 0 },
    );
    assert.deepEqual(
      await cleanupExpiredAnalyticsDailyAggregates(firestore, cleanupAt),
      { scanned: 2, deleted: 2, skippedMalformed: 0 },
    );
    assert.deepEqual(
      await cleanupExpiredAnalyticsDailyAggregates(firestore, cleanupAt),
      { scanned: 0, deleted: 0, skippedMalformed: 0 },
    );
  } finally {
    await firestore.recursiveDelete(aggregates);
    await deleteApp(app);
  }
});

test("scheduled cleanup deletes only one deterministic expired page at the TTL boundary", async () => {
  const app = initializeApp({ projectId }, "todo17-cleanup");
  const firestore = getFirestore(app);
  const collection = firestore.collection(`users/${ownerUid}/analyticsEvents`);
  try {
    await clearOwner(firestore);
    const writes: Promise<FirebaseFirestore.WriteResult>[] = [];
    for (let index = 0; index < ANALYTICS_CLEANUP_LIMIT + 2; index += 1) {
      writes.push(
        collection.doc(`expired-${String(index).padStart(4, "0")}`).set({
          schemaVersion: 1,
          eventName: "APP_SESSION_STARTED",
          consentRevision: 1,
          occurredAt: Timestamp.fromMillis(nowMillis - 1),
          expiresAt: Timestamp.fromMillis(nowMillis),
        }),
      );
    }
    writes.push(
      collection.doc("future").set({
        schemaVersion: 1,
        eventName: "APP_SESSION_STARTED",
        consentRevision: 1,
        occurredAt: Timestamp.fromMillis(nowMillis),
        expiresAt: Timestamp.fromMillis(nowMillis + 1),
      }),
    );
    await Promise.all(writes);
    assert.equal(
      await cleanupExpiredAnalyticsEvents(
        firestore,
        Timestamp.fromMillis(nowMillis),
      ),
      ANALYTICS_CLEANUP_LIMIT,
    );
    const remaining = await collection.get();
    assert.equal(remaining.size, 3);
    assert.equal(
      remaining.docs.some((document) => document.id === "future"),
      true,
    );
  } finally {
    await clearOwner(firestore);
    await deleteApp(app);
  }
});
