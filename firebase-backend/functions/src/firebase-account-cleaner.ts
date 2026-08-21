import type { Auth } from "firebase-admin/auth"
import { FirebaseAuthError } from "firebase-admin/auth"
import type { Firestore } from "firebase-admin/firestore"
import type { Storage } from "firebase-admin/storage"
import type { AccountCleaner, CleanupCategory, OwnerId } from "./deletion-contract.js"

const DELETE_BATCH_SIZE = 100
const OWNER_FIELDS = ["ownerID", "ownerUid"] as const
const IDENTIFICATION_PREFIXES = ["identification-originals"] as const
const ACCOUNT_MEDIA_PREFIXES = ["plant-photos", "share-images"] as const

export class AccountCleanupError extends Error {
  override readonly name = "AccountCleanupError"

  constructor(
    readonly category: CleanupCategory,
    readonly ownerID: OwnerId,
    options?: ErrorOptions,
  ) {
    super(`Account cleanup failed for ${category}`, options)
  }
}

function assertNever(value: never): never {
  throw new TypeError(`Unsupported cleanup category: ${String(value)}`)
}

export class FirebaseAccountCleaner implements AccountCleaner {
  constructor(
    private readonly firestore: Firestore,
    private readonly auth: Auth,
    private readonly storage: Storage,
  ) {}

  async clean(ownerID: OwnerId, category: CleanupCategory): Promise<void> {
    try {
      switch (category) {
        case "FIRESTORE_ACCOUNT_DATA":
          await this.firestore.recursiveDelete(this.firestore.doc(`users/${ownerID}`))
          return
        case "NOTIFICATION_LINKS":
          await this.deleteOwnedDocuments("notificationEndpointOwners", ownerID)
          return
        case "PUBLIC_SHARES":
          await this.deleteOwnedDocuments("publicShares", ownerID)
          return
        case "IDENTIFICATION_MEDIA":
          await this.deletePrefixes(IDENTIFICATION_PREFIXES, ownerID)
          return
        case "ACCOUNT_MEDIA":
          await this.deletePrefixes(ACCOUNT_MEDIA_PREFIXES, ownerID)
          return
        case "AUTH_ACCOUNT":
          await this.deleteAuthUser(ownerID)
          return
        default:
          return assertNever(category)
      }
    } catch (error: unknown) {
      if (error instanceof AccountCleanupError) throw error
      if (error instanceof Error) {
        throw new AccountCleanupError(category, ownerID, { cause: error })
      }
      throw error
    }
  }

  private async deleteOwnedDocuments(collection: string, ownerID: OwnerId): Promise<void> {
    for (const ownerField of OWNER_FIELDS) {
      let snapshot = await this.firestore
        .collection(collection)
        .where(ownerField, "==", ownerID)
        .limit(DELETE_BATCH_SIZE)
        .get()
      while (!snapshot.empty) {
        await Promise.all(
          snapshot.docs.map((document) => this.firestore.recursiveDelete(document.ref)),
        )
        snapshot = await this.firestore
          .collection(collection)
          .where(ownerField, "==", ownerID)
          .limit(DELETE_BATCH_SIZE)
          .get()
      }
    }
  }

  private async deletePrefixes(prefixes: readonly string[], ownerID: OwnerId): Promise<void> {
    await Promise.all(
      prefixes.map((prefix) =>
        this.storage.bucket().deleteFiles({ prefix: `${prefix}/${ownerID}/` }),
      ),
    )
  }

  private async deleteAuthUser(ownerID: OwnerId): Promise<void> {
    try {
      await this.auth.deleteUser(ownerID)
    } catch (error: unknown) {
      if (error instanceof FirebaseAuthError && error.code === "auth/user-not-found") return
      throw error
    }
  }
}
