import { getApps, initializeApp } from "firebase-admin/app";
import { randomUUID } from "node:crypto";
import { getFirestore } from "firebase-admin/firestore";
import { defineSecret } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { AppleAuthError, executeAppleCallback, executeBeginAppleSignIn, executeCompleteAppleSignIn } from "./apple-auth.js";
import { FirestoreAppleSessionStore, VerifiedAppleTokenExchange } from "./apple-auth-runtime.js";
import { ContractError, executeOwnerMutation } from "./contracts.js";
import { FirestoreMutationStore } from "./firestore-store.js";
import { FirestoreWateringCompletionStore } from "./firestore-watering-store.js";
import {
  FirestoreIdentificationRequestStore,
  IdentificationRuntimeError,
  PlantIdStorageProvider,
  productionPlantIdHttpClient,
} from "./plant-identification-runtime.js";
import { executePlantIdentification, PlantIdentificationError } from "./plant-identification.js";
import { WateringError, executeWateringCompletion } from "./watering.js";

if (getApps().length === 0) initializeApp();
const firestore = getFirestore();
const store = new FirestoreMutationStore(firestore);
const wateringStore = new FirestoreWateringCompletionStore(firestore);
const appleStore = new FirestoreAppleSessionStore(firestore);
const applePrivateKey = defineSecret("APPLE_PRIVATE_KEY");
const plantIdApiKey = defineSecret("PLANT_ID_API_KEY");

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

export const completeWatering = onCall(async (request) => {
  try {
    return await executeWateringCompletion(
      request.auth === undefined ? null : { uid: request.auth.uid },
      request.data,
      wateringStore,
    );
  } catch (error: unknown) {
    if (error instanceof WateringError) throw new HttpsError(error.code, error.message);
    throw error;
  }
});

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

export const identifyPlant = onCall({ enforceAppCheck: true, secrets: [plantIdApiKey] }, async (request) => {
  try {
    return await executePlantIdentification(
      request.auth === undefined ? null : { uid: request.auth.uid },
      request.data,
      new FirestoreIdentificationRequestStore(firestore),
      new PlantIdStorageProvider(productionPlantIdHttpClient(plantIdApiKey.value())),
    );
  } catch (error: unknown) {
    if (error instanceof PlantIdentificationError) {
      if (error.reason === "unauthenticated") throw new HttpsError("unauthenticated", "Sign-in is required");
      if (error.reason === "invalid_argument") throw new HttpsError("invalid-argument", "Identification request is invalid");
      if (error.reason === "permission_denied") throw new HttpsError("permission-denied", "Request is not owned by this user");
    }
    if (error instanceof IdentificationRuntimeError) {
      switch (error.reason) {
        case "not_found": throw new HttpsError("not-found", "Identification request was not found");
        case "permission_denied": throw new HttpsError("permission-denied", "Request is not owned by this user");
        case "malformed_state": throw new HttpsError("failed-precondition", "Identification request is unavailable");
      }
    }
    throw error;
  }
});
