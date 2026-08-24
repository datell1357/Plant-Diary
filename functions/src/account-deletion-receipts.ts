import type {
  DocumentReference,
  DocumentSnapshot,
  Firestore,
  Transaction,
} from "firebase-admin/firestore";
import type { AccountDeletionRecord } from "./account-deletion-contract.js";
import {
  type AccountDeletionReceiptKind,
  parseStoredReceipt,
  storedReceipt,
} from "./account-deletion-persistence.js";

export class AccountDeletionReceipts {
  constructor(private readonly firestore: Firestore) {}

  reference(
    ownerUid: string,
    kind: AccountDeletionReceiptKind,
    key: string,
  ): DocumentReference {
    return this.firestore.doc(
      `accountDeletionReceipts/${ownerUid}/commands/${kind.toLowerCase()}_${key}`,
    );
  }

  parse(
    snapshot: DocumentSnapshot,
    ownerUid: string,
    kind: AccountDeletionReceiptKind,
    key: string,
  ): AccountDeletionRecord | null {
    return snapshot.exists ? parseStoredReceipt(snapshot.data(), ownerUid, kind, key) : null;
  }

  create(
    transaction: Transaction,
    reference: DocumentReference,
    kind: AccountDeletionReceiptKind,
    result: AccountDeletionRecord,
    acceptedAtMillis: number,
  ): void {
    const key = reference.id.slice(kind.length + 1);
    transaction.create(
      reference,
      storedReceipt(result.ownerUid, kind, key, result, acceptedAtMillis),
    );
  }
}
