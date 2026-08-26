import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

type IndexConfiguration = Readonly<{
  indexes: readonly Readonly<{
    collectionGroup: string;
    queryScope: string;
    fields: readonly Readonly<{ fieldPath: string; order: string }>[];
  }>[];
  fieldOverrides: readonly Readonly<{
    collectionGroup: string;
    fieldPath: string;
    ttl: boolean;
    indexes: readonly unknown[];
  }>[];
}>;

test("owner retention collections have only the required cleanup indexes and TTL policies", async () => {
  const configuration = JSON.parse(
    await readFile(resolve(process.cwd(), "../firestore.indexes.json"), "utf8"),
  ) as IndexConfiguration;
  const cleanupGroups = [
    ["notificationHistory", "COLLECTION_GROUP"],
    ["notificationDeliveries", "COLLECTION_GROUP"],
    ["notificationDeliveryDiagnostics", "COLLECTION"],
    ["accountDeletionTombstones", "COLLECTION_GROUP"],
  ] as const;
  for (const [collectionGroup, queryScope] of cleanupGroups) {
    assert.ok(
      configuration.indexes.some(
        (index) =>
          index.collectionGroup === collectionGroup &&
          index.queryScope === queryScope &&
          index.fields.map((field) => field.fieldPath).join(",") ===
            "expiresAt,__name__",
      ),
      collectionGroup,
    );
    assert.deepEqual(
      configuration.fieldOverrides.find(
        (override) =>
          override.collectionGroup === collectionGroup &&
          override.fieldPath === "expiresAt",
      ),
      { collectionGroup, fieldPath: "expiresAt", ttl: true, indexes: [] },
    );
  }

  assert.equal(
    configuration.fieldOverrides.some(
      (override) => override.collectionGroup === "notificationDeliveryClaims",
    ),
    false,
    "live claim lease expiry must never be configured as Firestore TTL",
  );

  for (const [collectionGroup, anchor] of [
    ["weatherSnapshots", "observedAt"],
    ["weatherRisks", "observedAt"],
    ["weatherAlerts", "expiresAt"],
  ] as const) {
    assert.ok(
      configuration.indexes.some(
        (index) =>
          index.collectionGroup === collectionGroup &&
          index.queryScope === "COLLECTION_GROUP" &&
          index.fields.map((field) => field.fieldPath).join(",") ===
            `${anchor},__name__`,
      ),
      collectionGroup,
    );
    assert.equal(
      configuration.fieldOverrides.some(
        (override) => override.collectionGroup === collectionGroup,
      ),
      false,
      `${collectionGroup} requires lease-aware bounded cleanup, not native TTL`,
    );
  }
});
