import assert from "node:assert/strict";
import test from "node:test";
import { deleteApp, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { FirestoreAnalyticsStore } from "./firestore-analytics-store.js";
import { createFirestoreServerAnalyticsRecorder } from "./server-analytics.js";

const projectId = "demo-planterior";

async function clearOwner(
  firestore: ReturnType<typeof getFirestore>,
  ownerUid: string,
): Promise<void> {
  await Promise.all([
    firestore.recursiveDelete(firestore.doc(`users/${ownerUid}`)),
    firestore.doc(`accountDeletionRequests/${ownerUid}`).delete(),
  ]);
}

async function grantConsent(
  store: FirestoreAnalyticsStore,
  ownerUid: string,
): Promise<void> {
  await store.setConsent({
    ownerUid,
    granted: true,
    commandGeneration: 1,
    operationId: `consent-${ownerUid}`,
  });
}

test("safe Firestore recorder persists one redacted event for exact replay and classifies consent-off and lock", async () => {
  const app = initializeApp({ projectId }, "todo17-safe-server-analytics");
  const firestore = getFirestore(app);
  const store = new FirestoreAnalyticsStore(firestore);
  const recorder = createFirestoreServerAnalyticsRecorder(firestore);
  const ownerUid = "todo17-analytics-owner";
  const consentOffOwner = "todo17-consent-off-owner";
  const lockedOwner = "todo17-locked-owner";
  const rawDeliveryId = "123e4567-e89b-12d3-a456-426614174000";

  try {
    await Promise.all([
      clearOwner(firestore, ownerUid),
      clearOwner(firestore, consentOffOwner),
      clearOwner(firestore, lockedOwner),
    ]);
    await grantConsent(store, ownerUid);

    const operation = {
      ownerUid,
      eventName: "WATERING_NOTIFICATION_SENT" as const,
      operationIdentifier: rawDeliveryId,
    };
    const first = await recorder(operation);
    const replay = await recorder(operation);

    assert.equal(first.kind, "recorded");
    assert.equal(replay.kind, "recorded");
    assert.equal(replay.kind === "recorded" && replay.replayed, true);
    const events = await firestore
      .collection(`users/${ownerUid}/analyticsEvents`)
      .get();
    assert.equal(events.size, 1);
    assert.equal(
      events.docs[0]?.get("eventName"),
      "WATERING_NOTIFICATION_SENT",
    );
    assert.equal(events.docs[0]?.get("consentRevision"), 1);
    assert.equal(events.docs[0]?.ref.path.includes(rawDeliveryId), false);
    assert.equal(
      JSON.stringify(events.docs[0]?.data()).includes(rawDeliveryId),
      false,
    );

    assert.deepEqual(
      await recorder({
        ownerUid: consentOffOwner,
        eventName: "MINI_HOME_LAYOUT_SAVED",
        operationIdentifier: "raw-mini-home-operation-0001",
      }),
      { kind: "consent-off" },
    );
    assert.equal(
      (
        await firestore
          .collection(`users/${consentOffOwner}/analyticsEvents`)
          .get()
      ).size,
      0,
    );

    await grantConsent(store, lockedOwner);
    await firestore.doc(`accountDeletionRequests/${lockedOwner}`).set({
      status: "PROCESSING",
      completedScopes: [],
    });
    assert.deepEqual(
      await recorder({
        ownerUid: lockedOwner,
        eventName: "ACCOUNT_DELETION_REQUESTED",
        operationIdentifier: "raw-deletion-request-0001",
      }),
      { kind: "locked" },
    );
    assert.equal(
      (await firestore.collection(`users/${lockedOwner}/analyticsEvents`).get())
        .size,
      0,
    );
  } finally {
    await Promise.all([
      clearOwner(firestore, ownerUid),
      clearOwner(firestore, consentOffOwner),
      clearOwner(firestore, lockedOwner),
    ]);
    await deleteApp(app);
  }
});
