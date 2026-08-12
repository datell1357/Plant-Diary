import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import { ContractError, type MutationResult, type MutationStore, type OwnerMutationCommand, type ServerStateCommand } from "./contracts.js";

function timestampPayload(payload: Readonly<Record<string, unknown>>): Readonly<Record<string, unknown>> {
  return Object.fromEntries(Object.entries(payload).map(([key, value]) => {
    if ((key.endsWith("At") || key.endsWith("For")) && typeof value === "string") return [key, Timestamp.fromDate(new Date(value))];
    return [key, value];
  }));
}

export class FirestoreMutationStore implements MutationStore {
  constructor(private readonly firestore: Firestore) {}

  async applyOwnerMutation(command: OwnerMutationCommand): Promise<MutationResult> {
    return this.firestore.runTransaction(async (transaction) => {
      const operationRef = this.firestore.doc(`users/${command.ownerUid}/operations/${command.idempotencyKey}`);
      const documentRef = this.firestore.doc(command.documentPath);
      const operation = await transaction.get(operationRef);
      if (operation.exists) {
        const revision = operation.get("revision");
        const documentPath = operation.get("documentPath");
        if (typeof revision !== "number" || documentPath !== command.documentPath) {
          throw new ContractError("invalid-argument", "Operation receipt does not match the mutation");
        }
        return { kind: "duplicate", revision };
      }
      const document = await transaction.get(documentRef);
      const actualRevision = document.exists ? document.get("revision") : 0;
      if (typeof actualRevision !== "number") throw new ContractError("invalid-argument", "Stored revision is malformed");
      if (actualRevision !== command.expectedRevision) return { kind: "conflict", actualRevision };
      const revision = actualRevision + 1;
      transaction.set(documentRef, { ...command.payload, ownerUid: command.ownerUid, revision, expectedRevision: command.expectedRevision, idempotencyKey: command.idempotencyKey, updatedAt: FieldValue.serverTimestamp() });
      transaction.create(operationRef, { ownerUid: command.ownerUid, documentPath: command.documentPath, revision, expectedRevision: command.expectedRevision, idempotencyKey: command.idempotencyKey, updatedAt: FieldValue.serverTimestamp() });
      return { kind: "applied", revision };
    });
  }

  async writeServerState(command: ServerStateCommand): Promise<void> {
    await this.firestore.doc(command.documentPath).set({ ...timestampPayload(command.payload), ownerUid: command.ownerUid, updatedAt: FieldValue.serverTimestamp() }, { merge: false });
  }
}
