import type { AuthContext } from "./contracts.js";
import type { InventorySnapshot } from "./inventory.js";
import type { MiniHomeLoadResult } from "./mini-home.js";

export const MINI_HOME_SNAPSHOT_CONTRACT_VERSION = 1 as const;

export type MiniHomeSnapshotPlant = Readonly<{
  plantId: string;
  ownerUid: string;
  displayName: string;
  representativePhotoPath: string | null;
  revision: number;
  updatedAtEpochMillis: number;
}>;

export type MiniHomeSnapshot = Readonly<{
  contractVersion: typeof MINI_HOME_SNAPSHOT_CONTRACT_VERSION;
  ownerUid: string;
  snapshotToken: string;
  snapshotGeneration: number;
  serverReadTimeEpochMillis: number;
  layout: MiniHomeLoadResult;
  inventory: InventorySnapshot;
  plants: readonly MiniHomeSnapshotPlant[];
}>;

export interface MiniHomeSnapshotStore {
  load(ownerUid: string): Promise<MiniHomeSnapshot>;
}

export type MiniHomeSnapshotErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "resource-exhausted"
  | "data-loss";

export class MiniHomeSnapshotError extends Error {
  constructor(
    readonly code: MiniHomeSnapshotErrorCode,
    message: string,
    readonly reason: "PERMISSION_DENIED" | "INVALID_REQUEST" | "MALFORMED_RESPONSE",
    readonly details?: Readonly<Record<string, string | number>>,
  ) {
    super(message);
    this.name = "MiniHomeSnapshotError";
  }
}

const opaqueId = /^[A-Za-z0-9_-]{1,128}$/;

export async function executeLoadMiniHomeSnapshot(
  auth: AuthContext | null,
  input: unknown,
  store: MiniHomeSnapshotStore,
): Promise<MiniHomeSnapshot> {
  if (auth === null || !opaqueId.test(auth.uid)) {
    throw new MiniHomeSnapshotError("unauthenticated", "Authentication is required", "PERMISSION_DENIED", { field: "auth" });
  }
  if (input === null || typeof input !== "object" || Array.isArray(input)) {
    throw new MiniHomeSnapshotError("invalid-argument", "request must be an object", "INVALID_REQUEST", { field: "request" });
  }
  const request = input as Readonly<Record<string, unknown>>;
  if (Object.keys(request).length !== 1 || !("expectedOwnerUid" in request)) {
    throw new MiniHomeSnapshotError("invalid-argument", "Fields do not match the snapshot contract", "INVALID_REQUEST", { field: "request" });
  }
  const expectedOwnerUid = request.expectedOwnerUid;
  if (typeof expectedOwnerUid !== "string" || !opaqueId.test(expectedOwnerUid)) {
    throw new MiniHomeSnapshotError("invalid-argument", "expectedOwnerUid must be path-safe", "INVALID_REQUEST", { field: "expectedOwnerUid" });
  }
  if (expectedOwnerUid !== auth.uid) {
    throw new MiniHomeSnapshotError("permission-denied", "Owner mismatch", "PERMISSION_DENIED", { field: "expectedOwnerUid" });
  }
  const snapshot = await store.load(auth.uid);
  if (
    snapshot.contractVersion !== MINI_HOME_SNAPSHOT_CONTRACT_VERSION ||
    snapshot.ownerUid !== auth.uid ||
    snapshot.layout.ownerUid !== auth.uid ||
    snapshot.inventory.ownerUid !== auth.uid ||
    !/^[a-f0-9]{64}$/.test(snapshot.snapshotToken) ||
    !Number.isSafeInteger(snapshot.snapshotGeneration) || snapshot.snapshotGeneration < 1 ||
    !Number.isSafeInteger(snapshot.serverReadTimeEpochMillis) || snapshot.serverReadTimeEpochMillis < 0
  ) {
    throw new MiniHomeSnapshotError("data-loss", "Stored snapshot envelope is malformed", "MALFORMED_RESPONSE", { field: "snapshot" });
  }
  return snapshot;
}
