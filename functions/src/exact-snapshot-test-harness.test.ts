import assert from "node:assert/strict";
import test from "node:test";
import {
  ExactSnapshotClosedError,
  ExactSnapshotSafetyError,
  type ExactSnapshotSafetyScheduler,
  subscribeToExactSnapshot,
} from "./exact-snapshot-test-harness.js";

type ControlledSafety = Readonly<{
  scheduler: ExactSnapshotSafetyScheduler;
  expire: () => void;
  cancelCount: () => number;
}>;

function controlledSafety(): ControlledSafety {
  let expiration: (() => void) | undefined;
  let cancellations = 0;
  return {
    scheduler: {
      schedule(action) {
        expiration = action;
        return () => { cancellations += 1; };
      },
    },
    expire() {
      if (expiration === undefined) {
        throw new TypeError("Safety boundary was not scheduled");
      }
      expiration();
    },
    cancelCount: () => cancellations,
  };
}

type ControlledSnapshots<Snapshot> = Readonly<{
  subscribe: (
    onSnapshot: (snapshot: Snapshot) => void,
    onError: (error: unknown) => void,
  ) => () => void;
  emit: (snapshot: Snapshot) => void;
  fail: (error: Error) => void;
  unsubscribeCount: () => number;
}>;

function controlledSnapshots<Snapshot>(): ControlledSnapshots<Snapshot> {
  let snapshotObserver: ((snapshot: Snapshot) => void) | undefined;
  let errorObserver: ((error: unknown) => void) | undefined;
  let unsubscriptions = 0;
  return {
    subscribe(onSnapshot, onError) {
      snapshotObserver = onSnapshot;
      errorObserver = onError;
      return () => { unsubscriptions += 1; };
    },
    emit(snapshot) {
      if (snapshotObserver === undefined) {
        throw new TypeError("Snapshot observer was not subscribed");
      }
      snapshotObserver(snapshot);
    },
    fail(error) {
      if (errorObserver === undefined) {
        throw new TypeError("Error observer was not subscribed");
      }
      errorObserver(error);
    },
    unsubscribeCount: () => unsubscriptions,
  };
}

test("timeout rejects the exact value and automatically releases the listener and safety guard", async () => {
  // Given
  const safety = controlledSafety();
  const snapshots = controlledSnapshots<string>();
  const subscription = subscribeToExactSnapshot({
    label: "catalog projection",
    subscribe: snapshots.subscribe,
    matches: async () => false,
    safetyMilliseconds: 107,
    scheduler: safety.scheduler,
  });
  snapshots.emit("candidate");
  await subscription.ready;
  const valueRejection = assert.rejects(subscription.value, ExactSnapshotSafetyError);

  // When
  safety.expire();

  // Then
  await valueRejection;
  assert.equal(snapshots.unsubscribeCount(), 1);
  assert.equal(safety.cancelCount(), 1);
});

test("matching validation resolves once and automatically releases every resource", async () => {
  // Given
  const safety = controlledSafety();
  const snapshots = controlledSnapshots<string>();
  const subscription = subscribeToExactSnapshot({
    label: "catalog projection",
    subscribe: snapshots.subscribe,
    matches: async (snapshot) => snapshot === "exact",
    scheduler: safety.scheduler,
  });

  // When
  snapshots.emit("exact");

  // Then
  assert.equal(await subscription.value, "exact");
  assert.equal(snapshots.unsubscribeCount(), 1);
  assert.equal(safety.cancelCount(), 1);
});

test("snapshot callback errors reject the exact value and automatically release every resource", async () => {
  // Given
  const safety = controlledSafety();
  const snapshots = controlledSnapshots<string>();
  const subscription = subscribeToExactSnapshot({
    label: "catalog projection",
    subscribe: snapshots.subscribe,
    matches: async () => false,
    scheduler: safety.scheduler,
  });
  const expected = new TypeError("snapshot stream failed");
  snapshots.emit("candidate");
  await subscription.ready;
  const valueRejection = assert.rejects(subscription.value, expected);

  // When
  snapshots.fail(expected);

  // Then
  await valueRejection;
  assert.equal(snapshots.unsubscribeCount(), 1);
  assert.equal(safety.cancelCount(), 1);
});

test("explicit close rejects pending waits and late validation cannot settle again", async () => {
  // Given
  const safety = controlledSafety();
  const snapshots = controlledSnapshots<string>();
  let releaseValidation: ((matches: boolean) => void) | undefined;
  const validation = new Promise<boolean>((resolve) => {
    releaseValidation = resolve;
  });
  const subscription = subscribeToExactSnapshot({
    label: "catalog projection",
    subscribe: snapshots.subscribe,
    matches: () => validation,
    scheduler: safety.scheduler,
  });
  snapshots.emit("candidate");
  await subscription.ready;
  const valueRejection = assert.rejects(subscription.value, ExactSnapshotClosedError);

  // When
  subscription.close();
  if (releaseValidation === undefined) {
    throw new TypeError("Validation release was not registered");
  }
  releaseValidation(true);

  // Then
  await valueRejection;
  assert.equal(snapshots.unsubscribeCount(), 1);
  assert.equal(safety.cancelCount(), 1);
});

test("validation rejection automatically releases the listener and safety guard", async () => {
  // Given
  const safety = controlledSafety();
  const snapshots = controlledSnapshots<string>();
  const expected = new TypeError("projection read failed");
  const subscription = subscribeToExactSnapshot({
    label: "catalog projection",
    subscribe: snapshots.subscribe,
    matches: async () => { throw expected; },
    scheduler: safety.scheduler,
  });

  // When
  snapshots.emit("candidate");

  // Then
  await assert.rejects(subscription.value, expected);
  assert.equal(snapshots.unsubscribeCount(), 1);
  assert.equal(safety.cancelCount(), 1);
});
