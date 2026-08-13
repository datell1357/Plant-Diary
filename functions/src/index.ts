import { getApps, initializeApp } from "firebase-admin/app";
import { randomUUID } from "node:crypto";
import { getFirestore } from "firebase-admin/firestore";
import { defineSecret } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { AppleAuthError, executeAppleCallback, executeBeginAppleSignIn, executeCompleteAppleSignIn } from "./apple-auth.js";
import { FirestoreAppleSessionStore, VerifiedAppleTokenExchange } from "./apple-auth-runtime.js";
import { ContractError, executeOwnerMutation } from "./contracts.js";
import { FirestoreMutationStore } from "./firestore-store.js";

if (getApps().length === 0) initializeApp();
const firestore = getFirestore();
const store = new FirestoreMutationStore(firestore);
const appleStore = new FirestoreAppleSessionStore(firestore);
const applePrivateKey = defineSecret("APPLE_PRIVATE_KEY");

function requiredEnvironment(name: "APPLE_CLIENT_ID" | "APPLE_REDIRECT_URI" | "APPLE_TEAM_ID" | "APPLE_KEY_ID"): string {
  const value = process.env[name];
  if (value === undefined || value.length === 0) throw new AppleAuthError("failed-precondition", `${name} is not configured`);
  return value;
}

function appleConfig() {
  return {
    clientId: requiredEnvironment("APPLE_CLIENT_ID"),
    redirectUri: requiredEnvironment("APPLE_REDIRECT_URI"),
  };
}

function appleHttpsError(error: unknown): never {
  if (!(error instanceof AppleAuthError)) throw error;
  switch (error.code) {
    case "invalid-argument": throw new HttpsError("invalid-argument", error.message);
    case "not-found": throw new HttpsError("not-found", error.message);
    case "already-exists": throw new HttpsError("already-exists", error.message);
    case "failed-precondition": throw new HttpsError("failed-precondition", error.message);
    case "unauthenticated": throw new HttpsError("unauthenticated", error.message);
    case "unavailable": throw new HttpsError("unavailable", error.message);
  }
}

export const applyRevisionedOwnerWrite = onCall(async (request) => {
  try {
    return await executeOwnerMutation(request.auth === undefined ? null : { uid: request.auth.uid }, request.data, store);
  } catch (error: unknown) {
    if (error instanceof ContractError) throw new HttpsError(error.code, error.message);
    throw error;
  }
});

export { executeOwnerMutation, executeServerStateWrite } from "./contracts.js";


export const beginAppleSignIn = onCall(async (request) => {
  try {
    return await executeBeginAppleSignIn(
      request.data,
      appleStore,
      appleConfig(),
      new Date(),
      () => randomUUID().replaceAll("-", ""),
    );
  } catch (error: unknown) {
    return appleHttpsError(error);
  }
});

export const appleOAuthCallback = onRequest(async (request, response) => {
  try {
    const result = await executeAppleCallback(request.body, appleStore, new Date());
    response.redirect(302, result.redirectUri);
  } catch (error: unknown) {
    if (error instanceof AppleAuthError) {
      response.status(400).send("Apple sign-in could not be completed.");
      return;
    }
    throw error;
  }
});

export const completeAppleSignIn = onCall({ secrets: [applePrivateKey] }, async (request) => {
  try {
    const exchange = new VerifiedAppleTokenExchange({
      ...appleConfig(),
      teamId: requiredEnvironment("APPLE_TEAM_ID"),
      keyId: requiredEnvironment("APPLE_KEY_ID"),
      privateKey: applePrivateKey.value(),
    });
    return await executeCompleteAppleSignIn(request.data, appleStore, exchange, new Date());
  } catch (error: unknown) {
    return appleHttpsError(error);
  }
});
