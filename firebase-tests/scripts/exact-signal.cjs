class ExactSignalSafetyError extends Error {
  constructor(name, safetyMs) {
    super(`Timed out waiting for exact signal "${name}" after ${safetyMs}ms`);
    this.name = "ExactSignalSafetyError";
  }
}

function waitForExactSignal({
  emitter,
  signal,
  trigger,
  safetyMs,
  errorSignal = "error",
  scheduleSafety = setTimeout,
  clearSafety = clearTimeout,
}) {
  if (!emitter || typeof emitter.once !== "function") throw new TypeError("emitter must support events");
  if (!signal || typeof signal !== "string") throw new TypeError("signal must be a non-empty string");
  if (typeof trigger !== "function") throw new TypeError("trigger must be a function");
  if (!Number.isSafeInteger(safetyMs) || safetyMs <= 0) throw new TypeError("safetyMs must be a positive integer");

  return new Promise((resolve, reject) => {
    let safety;
    let cancel;
    let settled = false;

    const removeListeners = () => {
      emitter.removeListener(signal, onSignal);
      emitter.removeListener(errorSignal, onError);
    };
    const settle = (operation, value) => {
      if (settled) return;
      settled = true;
      removeListeners();
      if (safety !== undefined) clearSafety(safety);
      operation(value);
    };
    const onSignal = (value) => settle(resolve, value);
    const onError = (error) => settle(reject, error instanceof Error ? error : new Error(String(error)));

    emitter.once(signal, onSignal);
    emitter.once(errorSignal, onError);
    safety = scheduleSafety(() => {
      settle(reject, new ExactSignalSafetyError(signal, safetyMs));
      if (typeof cancel === "function") cancel();
    }, safetyMs);

    try {
      cancel = trigger();
    } catch (error) {
      onError(error);
    }
  });
}

module.exports = { ExactSignalSafetyError, waitForExactSignal };
