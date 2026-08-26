export const EXACT_SNAPSHOT_SAFETY_MILLISECONDS = 30_000;

export class ExactSnapshotSafetyError extends Error {
  readonly name = "ExactSnapshotSafetyError";

  constructor(
    readonly label: string,
    readonly safetyMilliseconds: number,
  ) {
    super(`Timed out waiting for exact ${label} after ${safetyMilliseconds}ms`);
  }
}

export class ExactSnapshotClosedError extends Error {
  readonly name = "ExactSnapshotClosedError";

  constructor(readonly label: string) {
    super(`Closed exact ${label} subscription before it settled`);
  }
}

export type ExactSnapshotSafetyScheduler = Readonly<{
  schedule: (action: () => void, delayMilliseconds: number) => () => void;
}>;

type ExactSnapshotOptions<Snapshot> = Readonly<{
  label: string;
  subscribe: (
    onSnapshot: (snapshot: Snapshot) => void,
    onError: (error: unknown) => void,
  ) => () => void;
  matches: (snapshot: Snapshot) => Promise<boolean>;
  safetyMilliseconds?: number;
  scheduler?: ExactSnapshotSafetyScheduler;
}>;

export type ExactSnapshotSubscription<Snapshot> = Readonly<{
  ready: Promise<void>;
  value: Promise<Snapshot>;
  close: () => void;
}>;

const defaultSafetyScheduler: ExactSnapshotSafetyScheduler = {
  schedule(action, delayMilliseconds) {
    const timer = setTimeout(action, delayMilliseconds);
    timer.unref();
    return () => clearTimeout(timer);
  },
};

export function subscribeToExactSnapshot<Snapshot>(
  options: ExactSnapshotOptions<Snapshot>,
): ExactSnapshotSubscription<Snapshot> {
  const safetyMilliseconds = options.safetyMilliseconds
    ?? EXACT_SNAPSHOT_SAFETY_MILLISECONDS;
  const scheduler = options.scheduler ?? defaultSafetyScheduler;
  let readyResolve: (() => void) | undefined;
  let readyReject: ((error: unknown) => void) | undefined;
  let valueResolve: ((snapshot: Snapshot) => void) | undefined;
  let valueReject: ((error: unknown) => void) | undefined;
  let readyObserved = false;
  let active = true;
  let cancelSafety = () => {};
  let unsubscribe = () => {};
  const ready = new Promise<void>((resolve, reject) => {
    readyResolve = resolve;
    readyReject = reject;
  });
  const value = new Promise<Snapshot>((resolve, reject) => {
    valueResolve = resolve;
    valueReject = reject;
  });
  const release = () => {
    cancelSafety();
    unsubscribe();
  };
  const reject = (error: unknown) => {
    if (!active) return;
    active = false;
    release();
    if (!readyObserved) readyReject?.(error);
    valueReject?.(error);
  };
  const resolve = (snapshot: Snapshot) => {
    if (!active) return;
    active = false;
    release();
    valueResolve?.(snapshot);
  };
  const registeredUnsubscribe = options.subscribe(
    (snapshot) => {
      if (!active) return;
      if (!readyObserved) {
        readyObserved = true;
        readyResolve?.();
      }
      void options.matches(snapshot).then((matches) => {
        if (active && matches) resolve(snapshot);
      }, reject);
    },
    reject,
  );
  unsubscribe = registeredUnsubscribe;
  if (!active) unsubscribe();
  if (active) {
    cancelSafety = scheduler.schedule(
      () => reject(new ExactSnapshotSafetyError(options.label, safetyMilliseconds)),
      safetyMilliseconds,
    );
    if (!active) cancelSafety();
  }
  return {
    ready,
    value,
    close: () => reject(new ExactSnapshotClosedError(options.label)),
  };
}
