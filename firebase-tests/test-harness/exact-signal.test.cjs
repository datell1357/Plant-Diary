const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const test = require("node:test");
const { ExactSignalSafetyError, waitForExactSignal } = require("../scripts/exact-signal.cjs");

function controlledSafety() {
  let expire;
  let cleared = false;
  return {
    schedule(callback) {
      expire = callback;
      return "safety-handle";
    },
    clear(handle) {
      assert.equal(handle, "safety-handle");
      cleared = true;
    },
    expire() {
      assert.ok(expire, "safety boundary was not registered");
      expire();
    },
    get cleared() { return cleared; },
  };
}

test("subscribes before trigger and awaits delayed readiness exactly", async () => {
  const events = new EventEmitter();
  const safety = controlledSafety();
  const readiness = waitForExactSignal({
    emitter: events,
    signal: "ready",
    safetyMs: 73,
    scheduleSafety: safety.schedule,
    clearSafety: safety.clear,
    trigger: () => {
      assert.equal(events.listenerCount("ready"), 1);
      return () => assert.fail("completed readiness must not be cancelled");
    },
  });
  let settled = false;
  readiness.finally(() => { settled = true; });

  await Promise.resolve();
  assert.equal(settled, false);
  events.emit("ready", { firestore: { port: 8180 } });
  assert.deepEqual(await readiness, { firestore: { port: 8180 } });
  assert.equal(safety.cleared, true);
});

test("awaits the exact cleanup signal before completion", async () => {
  const events = new EventEmitter();
  const safety = controlledSafety();
  const cleanup = waitForExactSignal({
    emitter: events,
    signal: "cleanup-complete",
    safetyMs: 91,
    scheduleSafety: safety.schedule,
    clearSafety: safety.clear,
    trigger: () => undefined,
  });
  let settled = false;
  cleanup.finally(() => { settled = true; });

  await Promise.resolve();
  assert.equal(settled, false);
  events.emit("cleanup-complete", "closed");
  assert.equal(await cleanup, "closed");
  assert.equal(safety.cleared, true);
});

test("missing signal fails at the bounded safety boundary with an explicit error", async () => {
  const events = new EventEmitter();
  const safety = controlledSafety();
  let cancelled = false;
  const missing = waitForExactSignal({
    emitter: events,
    signal: "never-arrives",
    safetyMs: 107,
    scheduleSafety: safety.schedule,
    clearSafety: safety.clear,
    trigger: () => {
      assert.equal(events.listenerCount("never-arrives"), 1);
      return () => { cancelled = true; };
    },
  });

  safety.expire();
  await assert.rejects(
    missing,
    (error) => error instanceof ExactSignalSafetyError && error.message === 'Timed out waiting for exact signal "never-arrives" after 107ms',
  );
  assert.equal(cancelled, true);
  assert.equal(safety.cleared, true);
  assert.equal(events.listenerCount("never-arrives"), 0);
});
