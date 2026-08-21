import { Timestamp, type Firestore } from "firebase-admin/firestore";
import {
  MINI_HOME_PROJECTION_BOOTSTRAP_MAX_DOCUMENT_READS,
  MINI_HOME_PROJECTION_MAX_DOCUMENT_READS,
  projectionSnapshot,
  readAndRefreshPublishedOwnerProjection,
} from "./firestore-mini-home-projection.js";
import type { MiniHomeSnapshot, MiniHomeSnapshotStore } from "./mini-home-snapshot.js";

type SnapshotReadHooks = Readonly<{
  afterPointerRead?: (attempt: number) => Promise<void>;
  /** Compatibility name retained for deterministic pre-projection race probes. */
  afterLayoutRead?: (attempt: number) => Promise<void>;
  now?: () => Timestamp;
}>;

export class FirestoreMiniHomeSnapshotStore implements MiniHomeSnapshotStore {
  private attempts = 0;
  private readonly now: () => Timestamp;

  constructor(
    private readonly firestore: Firestore,
    private readonly hooks: SnapshotReadHooks = {},
  ) {
    this.now = hooks.now ?? Timestamp.now;
  }

  async load(ownerUid: string): Promise<MiniHomeSnapshot> {
    return this.firestore.runTransaction(async (transaction) => {
      const attempt = ++this.attempts;
      const readTime = this.now();
      const published = await readAndRefreshPublishedOwnerProjection(
        transaction,
        this.firestore,
        ownerUid,
        readTime,
        {
          afterPointerRead: async () => {
            await this.hooks.afterPointerRead?.(attempt);
            await this.hooks.afterLayoutRead?.(attempt);
          },
        },
      );
      return projectionSnapshot(published, readTime);
    }, { maxAttempts: 5 });
  }
}

/** Steady state reads current catalog and owner pointers plus their immutable projections. */
export const MINI_HOME_SNAPSHOT_MAX_DOCUMENT_READS = MINI_HOME_PROJECTION_MAX_DOCUMENT_READS;

/** A writer-side legacy bootstrap remains bounded by the original 429-document ceiling. */
export const MINI_HOME_SNAPSHOT_BOOTSTRAP_MAX_DOCUMENT_READS =
  MINI_HOME_PROJECTION_BOOTSTRAP_MAX_DOCUMENT_READS;
