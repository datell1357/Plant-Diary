import assert from "node:assert/strict";
import test from "node:test";
import {
  ContractError,
  executeServerStateWrite,
  type MutationStore,
} from "./contracts.js";

const store: MutationStore = {
  async applyOwnerMutation() {
    return { kind: "applied", revision: 1 };
  },
  async writeServerState() {},
  async ownerZoneId() {
    return "Asia/Seoul";
  },
  async publicPlantContentExists() {
    return true;
  },
  async resolvePrivateMedia() {
    return null;
  },
};

const delivery = {
  plantId: "plant-a",
  dueDate: "2026-08-24",
  attempt: 0,
  status: "SENT",
  scheduledFor: "2026-08-24T00:00:00Z",
  deliveredAt: "2026-08-24T00:01:00Z",
  deduplicationKey: "user-a:plant-a:2026-08-24:0",
};

test("operational delivery schema rejects endpoint diagnostics before persistence", async () => {
  await assert.rejects(
    executeServerStateWrite(
      { trusted: true },
      "user-a",
      {
        collection: "notificationDeliveries",
        documentId: "delivery-a",
        payload: {
          ...delivery,
          endpointResults: [{ endpointId: "endpoint-a", token: "not-permitted" }],
        },
      },
      store,
    ),
    (error) => error instanceof ContractError && error.code === "invalid-argument",
  );
});
