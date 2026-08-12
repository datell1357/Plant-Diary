import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { ContractError, executeOwnerMutation } from "./contracts.js";
import { FirestoreMutationStore } from "./firestore-store.js";

if (getApps().length === 0) initializeApp();
const store = new FirestoreMutationStore(getFirestore());

export const applyRevisionedOwnerWrite = onCall(async (request) => {
  try {
    return await executeOwnerMutation(request.auth === undefined ? null : { uid: request.auth.uid }, request.data, store);
  } catch (error: unknown) {
    if (error instanceof ContractError) throw new HttpsError(error.code, error.message);
    throw error;
  }
});

export { executeOwnerMutation, executeServerStateWrite } from "./contracts.js";
