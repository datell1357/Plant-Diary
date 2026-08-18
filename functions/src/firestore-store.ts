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

  async publicPlantContentExists(contentId: string): Promise<boolean> {
    const content = await this.firestore.doc(`plantContents/${contentId}`).get();
    return content.exists && content.get("publicationState") === "PUBLIC";
  }

  async ownerZoneId(ownerUid: string): Promise<string> {
    const account = await this.firestore.doc(`users/${ownerUid}`).get();
    const value = account.get("zoneId");
    if (typeof value !== "string") throw new ContractError("invalid-argument", "Account timezone is unavailable");
    return value;
  }

  async applyOwnerMutation(command: OwnerMutationCommand): Promise<MutationResult> {
    return this.firestore.runTransaction(async (transaction) => {
      const operationRef = this.firestore.doc(`users/${command.ownerUid}/operations/${command.idempotencyKey}`);
      const documentRef = this.firestore.doc(command.documentPath);
      const operation = await transaction.get(operationRef);
      if (operation.exists) {
        const revision = operation.get("revision");
        const documentPath = operation.get("documentPath");
        const requestHash = operation.get("requestHash");
        if (typeof revision !== "number" || documentPath !== command.documentPath || requestHash !== command.requestHash) {
          throw new ContractError("invalid-argument", "Operation receipt does not match the mutation");
        }
        return { kind: "duplicate", revision };
      }
      const document = await transaction.get(documentRef);
      const actualRevision = document.exists ? document.get("revision") : 0;
      if (typeof actualRevision !== "number") throw new ContractError("invalid-argument", "Stored revision is malformed");
      if (
        actualRevision !== command.expectedRevision ||
        (command.mutationType === "CREATE" && document.exists) ||
        (command.mutationType === "UPDATE" && !document.exists)
      ) return { kind: "conflict", actualRevision };
      const revision = actualRevision + 1;
      const write = { ...command.payload, ownerUid: command.ownerUid, revision, expectedRevision: command.expectedRevision, idempotencyKey: command.idempotencyKey, updatedAt: FieldValue.serverTimestamp() };
      if (command.mutationType === "UPDATE") transaction.set(documentRef, write, { merge: true });
      else transaction.create(documentRef, write);
      transaction.create(operationRef, { ownerUid: command.ownerUid, documentPath: command.documentPath, requestHash: command.requestHash, revision, expectedRevision: command.expectedRevision, idempotencyKey: command.idempotencyKey, updatedAt: FieldValue.serverTimestamp() });
      return { kind: "applied", revision };
    });
  }

  async writeServerState(command: ServerStateCommand): Promise<void> {
    await this.firestore.doc(command.documentPath).set({ ...timestampPayload(command.payload), ownerUid: command.ownerUid, updatedAt: FieldValue.serverTimestamp() }, { merge: false });
  }
}
