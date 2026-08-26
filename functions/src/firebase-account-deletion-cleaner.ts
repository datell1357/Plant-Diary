import type { Auth } from "firebase-admin/auth";
import { FirebaseAuthError } from "firebase-admin/auth";
import {
  FieldPath,
  Timestamp,
  type Firestore,
  type Query,
} from "firebase-admin/firestore";
import type { Storage } from "firebase-admin/storage";
import {
  type AccountDeletionCleaner,
  AccountDeletionCleanupError,
  type AccountDeletionScope,
} from "./account-deletion-contract.js";
import { FirebasePrivateMediaObjectStore } from "./firebase-private-media.js";
import { FirestorePrivateMediaReservationRepository } from "./firestore-private-media.js";
import { sealOwnerPrivateMedia } from "./private-media-seal.js";

const PAGE_SIZE = 100;
const HASH = /^[a-f0-9]{64}$/;

export class FirebaseAccountDeletionCleaner implements AccountDeletionCleaner {
  private readonly privateMediaRepository: FirestorePrivateMediaReservationRepository;
  private readonly privateMediaObjects: FirebasePrivateMediaObjectStore;

  constructor(
    private readonly firestore: Firestore,
    private readonly storage: Storage,
    private readonly auth: Auth,
    private readonly nowMillis: () => number = Date.now,
  ) {
    this.privateMediaRepository = new FirestorePrivateMediaReservationRepository(firestore);
    this.privateMediaObjects = new FirebasePrivateMediaObjectStore(storage);
  }

  async clean(ownerUid: string, scope: AccountDeletionScope): Promise<void> {
    try {
      switch (scope) {
        case "PUBLIC_SHARES":
          await this.deletePublicShares(ownerUid);
          return;
        case "NOTIFICATION_ENDPOINT_OWNERS":
          await this.deleteNotificationEndpointOwners(ownerUid);
          return;
        case "PRIVATE_MEDIA_RESERVATIONS":
          await sealOwnerPrivateMedia({
            ownerUid,
            repository: this.privateMediaRepository,
            objects: this.privateMediaObjects,
            nowMillis: this.nowMillis,
          });
          await this.privateMediaRepository.purgeOwnerMetadata(ownerUid);
          return;
        case "IDENTIFICATION_ORIGINALS":
          await this.deleteStoragePrefix("identification-originals", ownerUid);
          return;
        case "PLANT_PHOTOS":
          await this.deleteStoragePrefix("plant-photos", ownerUid);
          return;
        case "SHARE_IMAGES":
          await this.deleteStoragePrefix("share-images", ownerUid);
          return;
        case "OWNER_GLOBAL_SWEEP":
          await this.verifyOwnerGlobalSweep(ownerUid);
          return;
        case "USER_DOCUMENTS":
          await this.firestore.recursiveDelete(this.firestore.doc(`users/${ownerUid}`));
          return;
        case "AUTH_ACCOUNT":
          await this.deleteAuthUser(ownerUid);
          return;
        default: {
          const unsupported: never = scope;
          throw new TypeError(`Unsupported account deletion scope: ${unsupported}`);
        }
      }
    } catch (error: unknown) {
      if (error instanceof AccountDeletionCleanupError) throw error;
      if (error instanceof Error) {
        throw new AccountDeletionCleanupError(ownerUid, scope, { cause: error });
      }
      throw error;
    }
  }

  private async deletePublicShares(ownerUid: string): Promise<void> {
    const links = this.firestore.collection(`users/${ownerUid}/shareLinks`);
    let query: Query = links.orderBy(FieldPath.documentId()).limit(PAGE_SIZE);
    while (true) {
      const page = await query.get();
      if (page.empty) return;
      const publicReferences = page.docs.map((share) => {
        if (share.get("ownerUid") !== ownerUid) {
          throw new TypeError("Stored share owner does not match account deletion owner");
        }
        const tokenHash = share.get("tokenHash");
        if (typeof tokenHash !== "string" || !HASH.test(tokenHash)) {
          throw new TypeError("Stored share token hash is malformed");
        }
        return this.firestore.doc(`publicShares/${tokenHash}`);
      });
      await Promise.all(
        publicReferences.map((reference) => this.firestore.recursiveDelete(reference)),
      );
      const cursor = page.docs.at(-1);
      if (cursor === undefined || page.size < PAGE_SIZE) return;
      query = links.orderBy(FieldPath.documentId()).startAfter(cursor).limit(PAGE_SIZE);
    }
  }

  private async deleteNotificationEndpointOwners(ownerUid: string): Promise<void> {
    const claims = this.firestore
      .collection("notificationDeliveryClaims")
      .where("ownerUid", "==", ownerUid);
    let claimPage = await claims.limit(PAGE_SIZE).get();
    while (!claimPage.empty) {
      const nowMillis = this.nowMillis();
      for (const claim of claimPage.docs) {
        const state = claim.get("state");
        const leaseExpiresAt = claim.get("expiresAt");
        if (
          isInFlightNotificationClaim(state) &&
          (!(leaseExpiresAt instanceof Timestamp) || leaseExpiresAt.toMillis() > nowMillis)
        ) {
          throw new Error("Notification send is still in flight");
        }
      }
      const batch = this.firestore.batch();
      for (const claim of claimPage.docs) batch.delete(claim.ref);
      await batch.commit();
      claimPage = await claims.limit(PAGE_SIZE).get();
    }

    await this.deleteQuery(
      this.firestore.collection("notificationDeliveryDiagnostics").where("ownerUid", "==", ownerUid),
    );

    const endpoints = this.firestore
      .collection("notificationEndpointOwners")
      .where("ownerUid", "==", ownerUid);
    let page = await endpoints.limit(PAGE_SIZE).get();
    while (!page.empty) {
      await Promise.all(page.docs.map((document) =>
        this.firestore.runTransaction(async (transaction) => {
          const current = await transaction.get(document.ref);
          if (!current.exists) return;
          if (current.get("ownerUid") !== ownerUid) {
            throw new TypeError("Stored endpoint owner does not match account deletion owner");
          }
          if (hasActiveSendLease(current.get("activeSendLeases"), this.nowMillis())) {
            throw new Error("Notification send is still in flight");
          }
          transaction.delete(document.ref);
        })
      ));
      page = await endpoints.limit(PAGE_SIZE).get();
    }

    const [remainingClaims, remainingDiagnostics, remainingEndpoints] = await Promise.all([
      claims.limit(1).get(),
      this.firestore.collection("notificationDeliveryDiagnostics").where("ownerUid", "==", ownerUid).limit(1).get(),
      endpoints.limit(1).get(),
    ]);
    if (!remainingClaims.empty || !remainingDiagnostics.empty || !remainingEndpoints.empty) {
      throw new Error("Notification owner-global cleanup did not converge");
    }
  }

  private async verifyOwnerGlobalSweep(ownerUid: string): Promise<void> {
    const collections = [
      "notificationEndpointOwners",
      "notificationDeliveryClaims",
      "notificationDeliveryDiagnostics",
      "privateMediaReservations",
      "privateMediaReservationReceipts",
    ] as const;
    const remaining = await Promise.all(
      collections.map((collection) =>
        this.firestore.collection(collection).where("ownerUid", "==", ownerUid).limit(1).get()
      ),
    );
    if (remaining.some((snapshot) => !snapshot.empty)) {
      throw new Error("Owner-global account deletion sweep is incomplete");
    }
  }

  private async deleteQuery(query: Query): Promise<void> {
    let page = await query.limit(PAGE_SIZE).get();
    while (!page.empty) {
      await Promise.all(
        page.docs.map((document) => this.firestore.recursiveDelete(document.ref)),
      );
      page = await query.limit(PAGE_SIZE).get();
    }
  }

  private async deleteStoragePrefix(prefix: string, ownerUid: string): Promise<void> {
    const ownerPrefix = `${prefix}/${ownerUid}/`;
    await this.storage.bucket().deleteFiles({ prefix: ownerPrefix });
    const [remaining] = await this.storage.bucket().getFiles({
      prefix: ownerPrefix,
      maxResults: 1,
    });
    if (remaining.length > 0) {
      throw new Error(`Legacy private media prefix is not empty: ${ownerPrefix}`);
    }
  }

  private async deleteAuthUser(ownerUid: string): Promise<void> {
    try {
      await this.auth.deleteUser(ownerUid);
    } catch (error: unknown) {
      if (error instanceof FirebaseAuthError && error.code === "auth/user-not-found") return;
      throw error;
    }
  }
}

function isInFlightNotificationClaim(state: unknown): boolean {
  return state === "CLAIMED" ||
    state === "AUTHORIZED_PRE_SEND" ||
    state === "SEND_MAY_HAVE_OCCURRED" ||
    state === "SENDING";
}

function hasActiveSendLease(value: unknown, nowMillis: number): boolean {
  return value !== null &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    Object.values(value).some(
      (expiresAt) => expiresAt instanceof Timestamp && expiresAt.toMillis() > nowMillis,
    );
}
