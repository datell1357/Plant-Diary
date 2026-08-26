import { createHash, randomUUID } from "node:crypto";
import {
  FieldPath,
  FieldValue,
  Timestamp,
  type DocumentData,
  type DocumentSnapshot,
  type Firestore,
} from "firebase-admin/firestore";
import type { Messaging } from "firebase-admin/messaging";
import { runAccountMutationTransaction } from "./account-mutation-lock.js";
import type {
  ServerAnalyticsOperation,
  ServerAnalyticsRecorder,
} from "./server-analytics.js";
import {
  terminalWeatherAlertFields,
  weatherRetentionTimestamp,
} from "./weather-retention.js";
import {
  WeatherConsentConflictError,
  WeatherError,
  canonicalWeather,
  evaluatePlantRisks,
  isWeatherStale,
  shouldDeliverWeatherAlert,
  type PlantEnvironment,
  type WeatherRegion,
  type WeatherRiskType,
  type WeatherSnapshot,
} from "./weather.js";
import {
  executeRefreshWeather,
  type WeatherContext,
  type WeatherEvaluationCommand,
  type WeatherProvider,
  type WeatherRefreshResult,
  type WeatherStore,
} from "./weather-service.js";

const opaqueId = /^[A-Za-z0-9_-]{1,128}$/;
const riskTypes: readonly WeatherRiskType[] = [
  "HIGH_TEMPERATURE",
  "LOW_TEMPERATURE",
  "DRY",
  "OVERHUMID",
];

export type WeatherFetch = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

export class OpenWeatherProvider implements WeatherProvider {
  constructor(
    private readonly apiKey: string,
    private readonly request: WeatherFetch = fetch,
  ) {
    if (apiKey.length === 0) throw new WeatherError("failed-precondition", "Weather provider is not configured");
  }

  async current(region: WeatherRegion, signal?: AbortSignal): Promise<WeatherSnapshot> {
    const url = new URL("https://api.openweathermap.org/data/3.0/onecall");
    url.searchParams.set("lat", String(region.latitude));
    url.searchParams.set("lon", String(region.longitude));
    url.searchParams.set("exclude", "minutely,hourly,daily,alerts");
    url.searchParams.set("units", "metric");
    url.searchParams.set("appid", this.apiKey);
    const response = await this.safeFetch(url, signal);
    return canonicalWeather(await response.json(), region);
  }

  async search(query: string): Promise<readonly WeatherRegion[]> {
    const url = new URL("https://api.openweathermap.org/geo/1.0/direct");
    url.searchParams.set("q", query);
    url.searchParams.set("limit", "10");
    url.searchParams.set("appid", this.apiKey);
    const response = await this.safeFetch(url);
    const payload: unknown = await response.json();
    if (!Array.isArray(payload)) throw new WeatherError("unavailable", "Region provider response is malformed");
    return payload.map((item) => {
      if (typeof item !== "object" || item === null || Array.isArray(item)) {
        throw new WeatherError("unavailable", "Region provider response is malformed");
      }
      const value = Object.fromEntries(Object.entries(item));
      const latitude = value.lat;
      const longitude = value.lon;
      const name = value.local_names;
      const localNames =
        typeof name === "object" && name !== null && !Array.isArray(name)
          ? Object.fromEntries(Object.entries(name))
          : {};
      const displayName = localNames.ko ?? value.name;
      const country = value.country;
      const state = value.state;
      if (
        typeof latitude !== "number" || !Number.isFinite(latitude) ||
        typeof longitude !== "number" || !Number.isFinite(longitude) ||
        typeof displayName !== "string" || displayName.length === 0 ||
        (country !== undefined && typeof country !== "string") ||
        (state !== undefined && typeof state !== "string")
      ) throw new WeatherError("unavailable", "Region provider response is malformed");
      const regionName = [displayName, state, country].filter((part): part is string => typeof part === "string" && part.length > 0).join(", ");
      return {
        regionCode: `region-${createHash("sha256").update(`${latitude},${longitude},${regionName}`).digest("hex").slice(0, 20)}`,
        regionName,
        latitude,
        longitude,
        source: "MANUAL" as const,
      };
    });
  }

  private async safeFetch(url: URL, signal?: AbortSignal): Promise<Response> {
    let response: Response;
    try {
      response = await this.request(url, {
        signal:
          signal === undefined
            ? AbortSignal.timeout(10_000)
            : AbortSignal.any([signal, AbortSignal.timeout(10_000)]),
      });
    } catch {
      throw new WeatherError("unavailable", "Weather provider is unavailable");
    }
    if (!response.ok) throw new WeatherError("unavailable", "Weather provider is unavailable");
    return response;
  }
}

export async function executeScheduledWeatherRefresh(
  ownerUid: string,
  store: WeatherStore,
  provider: WeatherProvider,
  signal: AbortSignal,
  clock: () => Date = () => new Date(),
): Promise<WeatherRefreshResult> {
  return executeRefreshWeather(
    { uid: ownerUid },
    { expectedOwnerUid: ownerUid },
    store,
    provider,
    clock,
    signal,
  );
}

export class FirestoreWeatherStore implements WeatherStore {
  constructor(
    private readonly firestore: Firestore,
    private readonly analytics?: ServerAnalyticsRecorder,
  ) {}

  async loadContext(ownerUid: string): Promise<WeatherContext> {
    const accountRef = this.firestore.doc(`users/${ownerUid}`);
    const settingsRef = this.firestore.doc(`users/${ownerUid}/weatherSettings/current`);
    const consentRef = this.firestore.doc(`users/${ownerUid}/consents/location`);
    const plantsQuery = this.firestore.collection(`users/${ownerUid}/personalPlants`).limit(200);
    const preferencesQuery = this.firestore.collection(`users/${ownerUid}/weatherPlantSettings`).limit(200);
    const risksQuery = this.firestore.collection(`users/${ownerUid}/weatherRisks`).where("active", "==", true).limit(800);
    const [account, settings, consent, plants, preferences, risks] = await Promise.all([
      accountRef.get(),
      settingsRef.get(),
      consentRef.get(),
      plantsQuery.get(),
      preferencesQuery.get(),
      risksQuery.get(),
    ]);
    if (!account.exists || account.get("ownerUid") !== ownerUid) {
      throw new WeatherError("not-found", "Account is unavailable");
    }
    const zoneId = account.get("zoneId");
    if (typeof zoneId !== "string") throw new WeatherError("failed-precondition", "Account timezone is unavailable");
    const ownedPlants = plants.docs.filter((plant) => plant.get("ownerUid") === ownerUid);
    const ownedPlantIds = new Set(ownedPlants.map((plant) => plant.id));
    const contentIds = [...new Set(ownedPlants.map((plant) => plant.get("contentId")).filter((id): id is string => typeof id === "string" && opaqueId.test(id)))];
    const contents = await Promise.all(contentIds.map((id) => this.firestore.doc(`plantContents/${id}`).get()));
    const contentById = new Map(contents.filter((content) => content.exists && content.get("publicationState") === "PUBLIC").map((content) => [content.id, content]));
    const environments = ownedPlants.map((plant) =>
      environmentFromPlant(plant, contentById)
    );
    const consentGenerationValue = consent.get("commandGeneration");
    const locationConsentGeneration =
      consent.exists &&
      consent.get("ownerUid") === ownerUid &&
      consent.get("granted") === true &&
      typeof consentGenerationValue === "number" &&
      Number.isSafeInteger(consentGenerationValue) &&
      consentGenerationValue >= 1
        ? consentGenerationValue
        : null;
    const plantAlerts = new Map<string, boolean>();
    for (const preference of preferences.docs) {
      if (preference.get("ownerUid") === ownerUid && preference.get("enabled") === false) plantAlerts.set(preference.id, false);
      else if (preference.get("ownerUid") === ownerUid && preference.get("enabled") === true) plantAlerts.set(preference.id, true);
    }
    return {
      ownerUid,
      zoneId,
      manualRegion: parseRegion(settings, "manualRegion", "MANUAL"),
      deviceRegion: parseRegion(settings, "deviceRegion", "DEVICE"),
      locationConsent: locationConsentGeneration !== null,
      locationConsentGeneration,
      globalAlertsEnabled: !settings.exists || settings.get("globalAlertsEnabled") !== false,
      plantAlerts,
      revision: settings.exists ? integer(settings.get("revision"), "Weather settings revision") : 0,
      plants: environments,
      lastKnownRisks: risks.docs.flatMap((risk) => {
        const plantId = risk.get("plantId");
        const plantName = risk.get("plantName");
        const type = risk.get("type");
        const reason = risk.get("reason");
        const action = risk.get("action");
        const detectedAt = risk.get("detectedAt");
        return typeof plantId === "string" && ownedPlantIds.has(plantId) &&
          risk.get("ownerUid") === ownerUid && typeof plantName === "string" &&
          typeof type === "string" && riskTypes.includes(type as WeatherRiskType) &&
          typeof reason === "string" && typeof action === "string"
          ? [{
              plantId,
              plantName,
              type: type as WeatherRiskType,
              reason,
              action,
              ...(detectedAt instanceof Timestamp ? { detectedAt: detectedAt.toDate() } : {}),
            }]
          : [];
      }),
    };
  }

  async recomputeSnapshotStaleness(
    ownerUid: string,
    evaluatedAt: Date,
    expectedLocationConsentGeneration: number | null,
  ): Promise<Readonly<{ stale: boolean }>> {
    const snapshotRef = this.firestore.doc(`users/${ownerUid}/weatherSnapshots/current`);
    const consentRef = this.firestore.doc(`users/${ownerUid}/consents/location`);
    return runAccountMutationTransaction(this.firestore, ownerUid, async (transaction) => {
      const [snapshot, consent] = await Promise.all([
        transaction.get(snapshotRef),
        transaction.get(consentRef),
      ]);
      assertLocationConsentPrecondition(
        consent,
        ownerUid,
        expectedLocationConsentGeneration,
      );
      if (!snapshot.exists || snapshot.get("ownerUid") !== ownerUid) return { stale: false };
      const observedAt = snapshot.get("observedAt");
      const stale = snapshot.get("stale") === true ||
        !(observedAt instanceof Timestamp) ||
        isWeatherStale(observedAt.toDate(), evaluatedAt);
      if (snapshot.get("stale") !== stale) {
        transaction.update(snapshotRef, {
          stale,
          ...(observedAt instanceof Timestamp ? {
            freshUntil: Timestamp.fromMillis(observedAt.toMillis() + WEATHER_FRESHNESS_MS),
            expiresAt: weatherRetentionTimestamp(observedAt.toDate()),
          } : {}),
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
      return { stale };
    });
  }

  async setLocationConsent(
    ownerUid: string,
    granted: boolean,
    commandGeneration: number,
  ): Promise<Readonly<{ commandGeneration: number; granted: boolean }>> {
    for (let page = 0; page < WEATHER_CONSENT_CLEANUP_MAX_PAGES; page += 1) {
      const result = await this.setLocationConsentPage(
        ownerUid,
        granted,
        commandGeneration,
      );
      if (granted || result.cleanupComplete) {
        return { commandGeneration: result.commandGeneration, granted: result.granted };
      }
    }
    throw new WeatherError(
      "unavailable",
      "Location consent cleanup is incomplete; retry the exact revoke command",
    );
  }

  private async setLocationConsentPage(
    ownerUid: string,
    granted: boolean,
    commandGeneration: number,
  ): Promise<Readonly<{
    commandGeneration: number;
    granted: boolean;
    cleanupComplete: boolean;
  }>> {
    const ref = this.firestore.doc(`users/${ownerUid}/consents/location`);
    const settingsRef = this.firestore.doc(`users/${ownerUid}/weatherSettings/current`);
    const snapshotRef = this.firestore.doc(`users/${ownerUid}/weatherSnapshots/current`);
    const deviceRisks = this.firestore
      .collection(`users/${ownerUid}/weatherRisks`)
      .where("source", "==", "DEVICE")
      .orderBy(FieldPath.documentId())
      .limit(WEATHER_CONSENT_CLEANUP_PAGE_SIZE + 1);
    const revocableDeviceAlerts = this.firestore
      .collection(`users/${ownerUid}/weatherAlerts`)
      .where("source", "==", "DEVICE")
      .where("status", "in", ["PENDING", "CLAIMED"])
      .orderBy(FieldPath.documentId())
      .limit(WEATHER_CONSENT_CLEANUP_PAGE_SIZE + 1);
    const authorizedDeviceAlerts = this.firestore
      .collection(`users/${ownerUid}/weatherAlerts`)
      .where("source", "==", "DEVICE")
      .where("status", "==", "SEND_MAY_HAVE_OCCURRED")
      .limit(1);
    return runAccountMutationTransaction(this.firestore, ownerUid, async (transaction) => {
      const [existing, settings] = await Promise.all([
        transaction.get(ref),
        transaction.get(settingsRef),
      ]);
      if (existing.exists && existing.get("ownerUid") !== ownerUid) {
        throw new WeatherError("permission-denied", "Weather consent scope is invalid");
      }
      const generationValue = existing.get("commandGeneration");
      if (
        existing.exists &&
        (typeof generationValue !== "number" ||
          !Number.isSafeInteger(generationValue) ||
          generationValue < 1)
      ) {
        throw new WeatherError("aborted", "Consent generation requires recovery");
      }
      const existingGeneration = existing.exists ? generationValue as number : 0;
      const existingGranted = existing.exists && existing.get("granted") === true;
      const replay = commandGeneration === existingGeneration;
      if (replay && granted !== existingGranted) {
        throw new WeatherConsentConflictError(
          existingGeneration,
          existingGranted,
          "Consent generation replay payload changed",
        );
      }
      if (!replay && commandGeneration !== existingGeneration + 1) {
        throw new WeatherConsentConflictError(existingGeneration, existingGranted);
      }
      if (granted) {
        if (!replay) {
          const revision = existing.exists && existing.get("revision") !== undefined
            ? integer(existing.get("revision"), "Consent revision")
            : 0;
          transaction.set(ref, consentDocument(ownerUid, granted, commandGeneration, revision));
        }
        return { commandGeneration, granted, cleanupComplete: true };
      }

      const [snapshot, risks, alerts, authorizedAlerts] = await Promise.all([
        transaction.get(snapshotRef),
        transaction.get(deviceRisks),
        transaction.get(revocableDeviceAlerts),
        transaction.get(authorizedDeviceAlerts),
      ]);
      if (!replay) {
        const revision = existing.exists && existing.get("revision") !== undefined
          ? integer(existing.get("revision"), "Consent revision")
          : 0;
        transaction.set(ref, consentDocument(ownerUid, false, commandGeneration, revision));
      }
      if (settings.exists && settings.get("deviceRegion") !== undefined) {
        const settingsRevision = integer(settings.get("revision"), "Weather settings revision");
        transaction.set(
          settingsRef,
          settingsDocument(ownerUid, settingsRevision, { deviceRegion: FieldValue.delete() }),
          { merge: true },
        );
      }
      if (snapshot.exists && snapshot.get("ownerUid") === ownerUid && snapshot.get("source") === "DEVICE") {
        transaction.delete(snapshotRef);
      }
      risks.docs.slice(0, WEATHER_CONSENT_CLEANUP_PAGE_SIZE).forEach((risk) => {
        if (risk.get("ownerUid") === ownerUid) transaction.delete(risk.ref);
      });
      alerts.docs.slice(0, WEATHER_CONSENT_CLEANUP_PAGE_SIZE).forEach((alert) => {
        if (alert.get("ownerUid") === ownerUid) transaction.delete(alert.ref);
      });
      return {
        commandGeneration,
        granted: false,
        cleanupComplete:
          risks.size <= WEATHER_CONSENT_CLEANUP_PAGE_SIZE &&
          alerts.size <= WEATHER_CONSENT_CLEANUP_PAGE_SIZE &&
          authorizedAlerts.empty,
      };
    });
  }

  async recoverLocationConsent(
    ownerUid: string,
  ): Promise<Readonly<{ commandGeneration: number; granted: boolean; recovered: true }>> {
    const ref = this.firestore.doc(`users/${ownerUid}/consents/location`);
    const settingsRef = this.firestore.doc(`users/${ownerUid}/weatherSettings/current`);
    return runAccountMutationTransaction(this.firestore, ownerUid, async (transaction) => {
      const [existing, settings] = await Promise.all([
        transaction.get(ref),
        transaction.get(settingsRef),
      ]);
      if (!existing.exists || existing.get("ownerUid") !== ownerUid) {
        throw new WeatherError("aborted", "Consent recovery is not available");
      }
      const generationValue = existing.get("commandGeneration");
      const idempotentRecovery =
        generationValue === 1 &&
        existing.get("granted") === false &&
        existing.get("legacyRecovery") === true;
      if (idempotentRecovery) {
        return { commandGeneration: 1, granted: false, recovered: true as const };
      }
      const exhaustedOrNoncanonical =
        typeof generationValue !== "number" ||
        !Number.isSafeInteger(generationValue) ||
        generationValue < 1 ||
        generationValue === Number.MAX_SAFE_INTEGER;
      if (!exhaustedOrNoncanonical) {
        throw new WeatherError("aborted", "Consent recovery is not available");
      }
      const revisionValue = existing.get("revision");
      const previousRevision =
        typeof revisionValue === "number" &&
        Number.isSafeInteger(revisionValue) &&
        revisionValue >= 0 &&
        revisionValue < Number.MAX_SAFE_INTEGER
          ? revisionValue
          : 0;
      transaction.set(ref, {
        ownerUid,
        type: "LOCATION",
        granted: false,
        commandGeneration: 1,
        legacyRecovery: true,
        recordedAt: FieldValue.serverTimestamp(),
        revision: previousRevision + 1,
        expectedRevision: previousRevision,
        idempotencyKey: "weather-consent-legacy-recovery",
        updatedAt: FieldValue.serverTimestamp(),
      });
      if (settings.exists && settings.get("deviceRegion") !== undefined) {
        const settingsRevision = integer(settings.get("revision"), "Weather settings revision");
        transaction.set(
          settingsRef,
          settingsDocument(ownerUid, settingsRevision, { deviceRegion: FieldValue.delete() }),
          { merge: true },
        );
      }
      return { commandGeneration: 1, granted: false, recovered: true as const };
    }).then(async (result) => {
      await this.setLocationConsent(ownerUid, false, result.commandGeneration);
      return result;
    });
  }

  async setManualRegion(ownerUid: string, region: WeatherRegion, expectedRevision: number): Promise<number> {
    return this.updateSettings(ownerUid, expectedRevision, {
      manualRegion: regionDocument(region),
    });
  }

  async updateAlerts(ownerUid: string, globalEnabled: boolean, plants: ReadonlyMap<string, boolean>, expectedRevision: number): Promise<number> {
    const settingsRef = this.firestore.doc(`users/${ownerUid}/weatherSettings/current`);
    return runAccountMutationTransaction(this.firestore, ownerUid, async (transaction) => {
      const settings = await transaction.get(settingsRef);
      const revision = settings.exists ? integer(settings.get("revision"), "Weather settings revision") : 0;
      if (revision !== expectedRevision) throw new WeatherError("aborted", "Weather settings changed on another device");
      const plantIds = [...plants.keys()];
      const preferenceRefs = plantIds.map((plantId) => this.firestore.doc(`users/${ownerUid}/weatherPlantSettings/${plantId}`));
      const [authoritativePlants, preferenceDocs] = await Promise.all([
        transaction.get(this.firestore.collection(`users/${ownerUid}/personalPlants`).limit(201)),
        Promise.all(preferenceRefs.map((ref) => transaction.get(ref))),
      ]);
      const authoritativeIds = authoritativePlants.docs
        .filter((plant) => plant.get("ownerUid") === ownerUid)
        .map((plant) => plant.id)
        .sort();
      if (
        authoritativeIds.length !== plantIds.length ||
        plantIds.slice().sort().some((plantId, index) => plantId !== authoritativeIds[index])
      ) {
        throw new WeatherError("aborted", "Weather alert targets changed; reload required");
      }
      transaction.set(settingsRef, settingsDocument(ownerUid, revision, { globalAlertsEnabled: globalEnabled }), { merge: settings.exists });
      [...plants.entries()].forEach(([plantId, enabled], index) => {
        const previousRevision = preferenceDocs[index]!.exists
          ? integer(preferenceDocs[index]!.get("revision"), "Weather preference revision")
          : 0;
        transaction.set(preferenceRefs[index]!, {
          ownerUid,
          plantId,
          enabled,
          revision: previousRevision + 1,
          expectedRevision: previousRevision,
          idempotencyKey: `weather-alert-${revision + 1}-${index}`,
          updatedAt: FieldValue.serverTimestamp(),
        });
      });
      return revision + 1;
    });
  }

  async commitEvaluation(command: WeatherEvaluationCommand): Promise<Readonly<{ revision: number }>> {
    const settingsRef = this.firestore.doc(`users/${command.ownerUid}/weatherSettings/current`);
    const snapshotRef = this.firestore.doc(`users/${command.ownerUid}/weatherSnapshots/current`);
    const consentRef = this.firestore.doc(`users/${command.ownerUid}/consents/location`);
    const activeRisks = await this.firestore.collection(`users/${command.ownerUid}/weatherRisks`).where("active", "==", true).get();
    const evaluated = new Map(command.risks.map((risk) => [`${risk.plantId}_${risk.type}`, risk]));
    const refs = new Map<string, FirebaseFirestore.DocumentReference>();
    activeRisks.docs.forEach((risk) => refs.set(risk.id, risk.ref));
    evaluated.forEach((_, id) => refs.set(id, this.firestore.doc(`users/${command.ownerUid}/weatherRisks/${id}`)));
    const result = await runAccountMutationTransaction(this.firestore, command.ownerUid, async (transaction) => {
      const [settings, consent, existingRisks, authoritativePlants] = await Promise.all([
        transaction.get(settingsRef),
        transaction.get(consentRef),
        Promise.all([...refs.values()].map((ref) => transaction.get(ref))),
        transaction.get(this.firestore.collection(`users/${command.ownerUid}/personalPlants`).limit(201)),
      ]);
      if (
        (command.region.source === "DEVICE") !==
        (command.expectedLocationConsentGeneration !== null)
      ) {
        throw new WeatherError("aborted", "Weather location source changed during refresh");
      }
      assertLocationConsentPrecondition(
        consent,
        command.ownerUid,
        command.expectedLocationConsentGeneration,
      );
      const currentPlants = authoritativePlants.docs.filter((plant) =>
        plant.get("ownerUid") === command.ownerUid
      );
      if (currentPlants.length > 200) {
        throw new WeatherError("aborted", "Weather plant criteria changed during refresh");
      }
      const currentContentIds = [...new Set(currentPlants
        .map((plant) => plant.get("contentId"))
        .filter((id): id is string => typeof id === "string" && opaqueId.test(id)))];
      const currentContents = await Promise.all(currentContentIds.map((id) =>
        transaction.get(this.firestore.doc(`plantContents/${id}`))
      ));
      const currentContentById = new Map(currentContents
        .filter((content) => content.exists && content.get("publicationState") === "PUBLIC")
        .map((content) => [content.id, content]));
      const currentEnvironments = currentPlants.map((plant) =>
        environmentFromPlant(plant, currentContentById)
      );
      const currentUnavailablePlantIds = currentEnvironments
        .filter((plant) => !completePlantEnvironment(plant))
        .map((plant) => plant.plantId)
        .sort();
      if (
        !samePlantEnvironments(currentEnvironments, command.plants) ||
        !sameStrings(currentUnavailablePlantIds, [...command.unavailablePlantIds].sort())
      ) {
        throw new WeatherError("aborted", "Weather plant criteria changed during refresh");
      }
      const settingsRevision = settings.exists ? integer(settings.get("revision"), "Weather settings revision") : 0;
      if (settingsRevision !== command.expectedRevision) throw new WeatherError("aborted", "Weather settings changed during refresh");
      const revision = settingsRevision + 1;
      transaction.set(settingsRef, settingsDocument(command.ownerUid, settingsRevision, {
        globalAlertsEnabled: command.globalAlertsEnabled,
        [command.region.source === "MANUAL" ? "manualRegion" : "deviceRegion"]: regionDocument(command.region),
        ...(command.switchToDeviceRegion && settings.exists
          ? { manualRegion: FieldValue.delete() }
          : {}),
        lastRefreshAt: Timestamp.fromDate(command.evaluatedAt),
      }), { merge: settings.exists });
      transaction.set(snapshotRef, {
        ownerUid: command.ownerUid,
        regionCode: command.snapshot.regionCode,
        regionName: command.snapshot.regionName,
        temperatureCelsius: command.snapshot.temperatureCelsius,
        humidityPercent: command.snapshot.humidityPercent,
        precipitationMillimeters: command.snapshot.precipitationMillimeters,
        observedAt: Timestamp.fromDate(command.snapshot.observedAt),
        freshUntil: Timestamp.fromMillis(command.snapshot.observedAt.valueOf() + WEATHER_FRESHNESS_MS),
        expiresAt: weatherRetentionTimestamp(command.snapshot.observedAt),
        zoneId: command.snapshot.zoneId ?? "UTC",
        stale: command.stale,
        unavailablePlantIds: currentUnavailablePlantIds,
        source: command.region.source,
        locationConsentGeneration: command.expectedLocationConsentGeneration,
        revision,
        expectedRevision: settingsRevision,
        idempotencyKey: `weather-snapshot-${revision}`,
        updatedAt: FieldValue.serverTimestamp(),
      });
      if (command.stale) return { revision, createdAlertIds: [] };
      const createdAlertIds: string[] = [];
      for (const existing of existingRisks) {
        const risk = evaluated.get(existing.id);
        const wasActive = existing.exists && existing.get("active") === true;
        if (risk === undefined && !wasActive) continue;
        const previousRevision = existing.exists ? integer(existing.get("revision"), "Weather risk revision") : 0;
        const previousTransition = existing.exists && typeof existing.get("transition") === "number" ? integer(existing.get("transition"), "Weather risk transition") : 0;
        const transition = risk !== undefined && !wasActive ? previousTransition + 1 : previousTransition;
        const deliveredTransition = existing.exists && typeof existing.get("deliveredTransition") === "number" ? integer(existing.get("deliveredTransition"), "Delivered transition") : null;
        transaction.set(existing.ref, {
          ownerUid: command.ownerUid,
          plantId: risk?.plantId ?? existing.get("plantId"),
          plantName: risk?.plantName ?? existing.get("plantName"),
          snapshotId: "current",
          type: risk?.type ?? existing.get("type"),
          reason: risk?.reason ?? existing.get("reason") ?? "",
          action: risk?.action ?? existing.get("action") ?? null,
          detectedAt: risk === undefined ? existing.get("detectedAt") : Timestamp.fromDate(command.evaluatedAt),
          observedAt: Timestamp.fromDate(command.snapshot.observedAt),
          expiresAt: weatherRetentionTimestamp(command.snapshot.observedAt),
          active: risk !== undefined,
          source: command.region.source,
          locationConsentGeneration: command.expectedLocationConsentGeneration,
          transition,
          deliveredTransition,
          revision: previousRevision + 1,
          expectedRevision: previousRevision,
          idempotencyKey: `weather-risk-${revision}-${existing.id}`,
          updatedAt: FieldValue.serverTimestamp(),
        });
        if (risk !== undefined && shouldDeliverWeatherAlert({
          globalEnabled: command.globalAlertsEnabled,
          plantEnabled: command.plantAlerts.get(risk.plantId) !== false,
          stale: command.stale,
          wasActive,
          active: true,
          transition,
          deliveredTransition,
        })) {
          const alertId = weatherAlertId(risk.plantId, risk.type, transition);
          transaction.create(this.firestore.doc(`users/${command.ownerUid}/weatherAlerts/${alertId}`), {
            ownerUid: command.ownerUid,
            plantId: risk.plantId,
            plantName: risk.plantName,
            riskId: existing.id,
            riskType: risk.type,
            transition,
            action: risk.action,
            source: command.region.source,
            locationConsentGeneration: command.expectedLocationConsentGeneration,
            status: "PENDING",
            freshUntil: Timestamp.fromMillis(command.snapshot.observedAt.valueOf() + WEATHER_FRESHNESS_MS),
            expiresAt: Timestamp.fromMillis(command.snapshot.observedAt.valueOf() + WEATHER_FRESHNESS_MS),
            createdAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp(),
          });
          createdAlertIds.push(alertId);
        }
      }
      return { revision, createdAlertIds };
    });
    for (const alertId of result.createdAlertIds) {
      await recordWeatherAnalytics(this.analytics, {
        ownerUid: command.ownerUid,
        eventName: "WEATHER_RISK_ALERT_CREATED",
        operationIdentifier: alertId,
      });
    }
    return { revision: result.revision };
  }

  private async updateSettings(ownerUid: string, expectedRevision: number, patch: Readonly<Record<string, unknown>>): Promise<number> {
    const ref = this.firestore.doc(`users/${ownerUid}/weatherSettings/current`);
    return runAccountMutationTransaction(this.firestore, ownerUid, async (transaction) => {
      const existing = await transaction.get(ref);
      const revision = existing.exists ? integer(existing.get("revision"), "Weather settings revision") : 0;
      if (revision !== expectedRevision) throw new WeatherError("aborted", "Weather settings changed on another device");
      transaction.set(
        ref,
        settingsDocument(ownerUid, revision, {
          ...(!existing.exists ? { globalAlertsEnabled: true } : {}),
          ...patch,
        }),
        { merge: existing.exists },
      );
      return revision + 1;
    });
  }
}

export const WEATHER_REFRESH_CURSOR = "notificationRuntime/weatherRefreshCursor";

export type ConfiguredWeatherRefreshOptions = Readonly<{
  pageSize?: number;
  maxPages?: number;
  deadlineMs?: number;
  perUserTimeoutMs?: number;
  now?: Date;
  clock?: () => Date;
  createTimeoutSignal?: (timeoutMs: number) => AbortSignal;
  invocationId?: string;
  afterCheckpoint?: (documentPath: string) => Promise<void>;
}>;

export type ConfiguredWeatherRefreshResult = Readonly<{
  processed: number;
  failed: number;
  busy: boolean;
  wrapped: boolean;
}>;

type ConfiguredWeatherRefreshOwner = (
  ownerUid: string,
  signal: AbortSignal,
) => Promise<void>;

export async function runConfiguredWeatherRefreshScan(
  firestore: Firestore,
  refreshOwner: ConfiguredWeatherRefreshOwner,
  options: ConfiguredWeatherRefreshOptions = {},
): Promise<ConfiguredWeatherRefreshResult> {
  const pageSize = options.pageSize ?? 100;
  const maxPages = options.maxPages ?? 5;
  const deadlineMs = options.deadlineMs ?? WEATHER_REFRESH_DEADLINE_MS;
  const perUserTimeoutMs = options.perUserTimeoutMs ?? WEATHER_REFRESH_USER_TIMEOUT_MS;
  if (!Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 500) {
    throw new Error("Weather refresh page size is invalid");
  }
  if (!Number.isSafeInteger(maxPages) || maxPages < 1 || maxPages > 20) {
    throw new Error("Weather refresh page count is invalid");
  }
  if (!Number.isSafeInteger(deadlineMs) || deadlineMs < 1 || deadlineMs > 480_000) {
    throw new Error("Weather refresh deadline is invalid");
  }
  if (
    !Number.isSafeInteger(perUserTimeoutMs) ||
    perUserTimeoutMs < 1 ||
    perUserTimeoutMs > 30_000
  ) {
    throw new Error("Weather refresh user timeout is invalid");
  }
  const clock = options.clock ?? (options.now === undefined ? () => new Date() : () => options.now!);
  const createTimeoutSignal = options.createTimeoutSignal ?? AbortSignal.timeout;
  const startedAt = clock();
  const invocationId = options.invocationId ?? randomUUID();
  const cursorRef = firestore.doc(WEATHER_REFRESH_CURSOR);
  const acquired = await firestore.runTransaction(async (transaction) => {
    const cursor = await transaction.get(cursorRef);
    const leaseExpiresAt = cursor.get("leaseExpiresAt");
    if (
      leaseExpiresAt instanceof Timestamp &&
      leaseExpiresAt.toDate() > startedAt &&
      cursor.get("leaseOwner") !== invocationId
    ) {
      return undefined;
    }
    const documentPath = cursor.get("documentPath");
    const inFlightPath = cursor.get("inFlightPath");
    const resumePath =
      typeof inFlightPath === "string"
        ? inFlightPath
        : typeof documentPath === "string"
          ? documentPath
          : null;
    transaction.set(
      cursorRef,
      {
        documentPath: resumePath,
        inFlightPath: FieldValue.delete(),
        leaseOwner: invocationId,
        leaseExpiresAt: Timestamp.fromMillis(startedAt.valueOf() + WEATHER_REFRESH_LEASE_MS),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    return { documentPath: resumePath };
  });
  if (acquired === undefined) {
    return { processed: 0, failed: 0, busy: true, wrapped: false };
  }

  let documentPath: string | null = acquired.documentPath;
  let processed = 0;
  let failed = 0;
  let wrapped = false;
  let deadlineReached = false;
  try {
    pages: for (let page = 0; page < maxPages; page += 1) {
      if (clock().valueOf() - startedAt.valueOf() >= deadlineMs) break;
      let query = firestore
        .collectionGroup("weatherSettings")
        .orderBy(FieldPath.documentId())
        .limit(pageSize);
      if (documentPath !== null) query = query.startAfter(firestore.doc(documentPath));
      const settings = await query.get();
      if (settings.empty) {
        documentPath = null;
        wrapped = true;
        await persistWeatherRefreshCursor(
          firestore,
          cursorRef,
          invocationId,
          documentPath,
          null,
          clock(),
        );
        break;
      }
      let settledInPage = 0;
      for (const setting of settings.docs) {
        if (clock().valueOf() - startedAt.valueOf() >= deadlineMs) {
          deadlineReached = true;
          break pages;
        }
        const currentPath = setting.ref.path;
        await persistWeatherRefreshCursor(
          firestore,
          cursorRef,
          invocationId,
          documentPath,
          currentPath,
          clock(),
        );
        const ownerUid = setting.ref.parent.parent?.id;
        processed += 1;
        if (ownerUid === undefined) {
          failed += 1;
        } else {
          const signal = createTimeoutSignal(perUserTimeoutMs);
          try {
            await runConfiguredWeatherRefreshOwner(refreshOwner, ownerUid, signal);
          } catch {
            failed += 1;
          }
        }
        documentPath = currentPath;
        settledInPage += 1;
        await persistWeatherRefreshCursor(
          firestore,
          cursorRef,
          invocationId,
          documentPath,
          null,
          clock(),
        );
        await options.afterCheckpoint?.(documentPath);
      }
      if (!deadlineReached && settledInPage === settings.size && settings.size < pageSize) {
        documentPath = null;
        wrapped = true;
        await persistWeatherRefreshCursor(
          firestore,
          cursorRef,
          invocationId,
          documentPath,
          null,
          clock(),
        );
        break;
      }
    }
  } finally {
    await firestore.runTransaction(async (transaction) => {
      const cursor = await transaction.get(cursorRef);
      if (cursor.get("leaseOwner") !== invocationId) return;
      transaction.set(
        cursorRef,
        {
          leaseOwner: FieldValue.delete(),
          leaseExpiresAt: FieldValue.delete(),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
    });
  }
  return { processed, failed, busy: false, wrapped };
}

async function runConfiguredWeatherRefreshOwner(
  refreshOwner: ConfiguredWeatherRefreshOwner,
  ownerUid: string,
  signal: AbortSignal,
): Promise<void> {
  if (signal.aborted) {
    await refreshOwner(ownerUid, signal);
    signal.throwIfAborted();
    return;
  }
  let rejectAbort!: (reason?: unknown) => void;
  const aborted = new Promise<never>((_, reject) => {
    rejectAbort = reject;
  });
  const onAbort = () => rejectAbort(signal.reason ?? new Error("Weather refresh timed out"));
  signal.addEventListener("abort", onAbort, { once: true });
  try {
    await Promise.race([refreshOwner(ownerUid, signal), aborted]);
  } finally {
    signal.removeEventListener("abort", onAbort);
  }
}

async function persistWeatherRefreshCursor(
  firestore: Firestore,
  cursorRef: FirebaseFirestore.DocumentReference,
  invocationId: string,
  documentPath: string | null,
  inFlightPath: string | null,
  now: Date,
): Promise<void> {
  await firestore.runTransaction(async (transaction) => {
    const cursor = await transaction.get(cursorRef);
    if (cursor.get("leaseOwner") !== invocationId) {
      throw new Error("Weather refresh lease was lost");
    }
    transaction.set(
      cursorRef,
      {
        documentPath,
        inFlightPath: inFlightPath ?? FieldValue.delete(),
        leaseExpiresAt: Timestamp.fromMillis(now.valueOf() + WEATHER_REFRESH_LEASE_MS),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
  });
}

const WEATHER_REFRESH_DEADLINE_MS = 7 * 60 * 1000 + 30 * 1000;
const WEATHER_REFRESH_USER_TIMEOUT_MS = 10 * 1000;
const WEATHER_REFRESH_LEASE_MS = 10 * 60 * 1000;

export type WeatherDeliveryHooks = Readonly<{
  beforeClaim?: (alertRef: FirebaseFirestore.DocumentReference) => Promise<void>;
  afterClaim?: (alertRef: FirebaseFirestore.DocumentReference) => Promise<void>;
  beforeSendBoundary?: (alertRef: FirebaseFirestore.DocumentReference) => Promise<void>;
  clock?: () => Date;
  analytics?: ServerAnalyticsRecorder;
}>;

export async function deliverPendingWeatherAlerts(
  firestore: Firestore,
  messaging: Messaging,
  ownerUid?: string,
  limit = 100,
  now?: Date,
  hooks: WeatherDeliveryHooks = {},
): Promise<void> {
  const invocationTime = now ?? new Date();
  const boundaryClock = hooks.clock ?? (now === undefined ? () => new Date() : () => invocationTime);
  await recoverExpiredWeatherAlerts(firestore, ownerUid, limit, invocationTime);
  let query: FirebaseFirestore.Query = ownerUid === undefined
    ? firestore.collectionGroup("weatherAlerts")
    : firestore.collection(`users/${ownerUid}/weatherAlerts`);
  query = query.where("status", "==", "PENDING").orderBy("createdAt", "asc").limit(limit);
  const alerts = await query.get();
  for (const alert of alerts.docs) {
    await hooks.beforeClaim?.(alert.ref);
    const claimed = await claimWeatherAlert(firestore, alert.ref, invocationTime);
    if (claimed === null) continue;
    await hooks.afterClaim?.(alert.ref);
    const endpoints = await firestore.collection(`users/${claimed.ownerUid}/notificationEndpoints`).where("notificationsEnabled", "==", true).limit(500).get();
    const endpointVersions = endpoints.docs.flatMap((endpoint) => {
      const ownerUidValue = endpoint.get("ownerUid");
      const token = endpoint.get("token");
      const generation = endpoint.get("generation");
      return ownerUidValue === claimed.ownerUid && typeof token === "string" && token.length > 0 &&
        typeof generation === "number" && Number.isSafeInteger(generation)
        ? [{ endpointId: endpoint.id, token, generation }]
        : [];
    });
    if (endpointVersions.length === 0) {
      const failedAt = boundaryClock();
      await runAccountMutationTransaction(
        firestore,
        claimed.ownerUid,
        async (transaction) => {
          const current = await transaction.get(alert.ref);
          if (!current.exists || current.get("status") !== "CLAIMED") return;
          transaction.update(alert.ref, {
            status: "FAILED",
            failureKind: "NO_ENDPOINT",
            ...terminalWeatherAlertFields(failedAt),
            leaseExpiresAt: FieldValue.delete(),
            updatedAt: FieldValue.serverTimestamp(),
          });
        },
      );
      continue;
    }
    await hooks.beforeSendBoundary?.(alert.ref);
    const sendBoundaryTime = boundaryClock();
    const authorized = await authorizeWeatherAlertSend(
      firestore,
      alert.ref,
      claimed,
      endpointVersions,
      sendBoundaryTime,
    );
    if (authorized === null) continue;
    const tokens = authorized.map((endpoint) => endpoint.token);
    let success = false;
    let transportAmbiguous = false;
    try {
      const response = await messaging.sendEachForMulticast({
        tokens,
        data: {
          title: `${claimed.plantName} ${weatherRiskLabel(claimed.riskType)}`,
          body: claimed.action,
          type: "WEATHER_RISK",
          ownerUid: claimed.ownerUid,
          plantName: claimed.plantName,
          route: `planterior://weather/plant/${claimed.plantId}`,
          alertId: alert.id,
          riskId: claimed.riskId,
          riskType: claimed.riskType,
          transition: String(claimed.transition),
        },
      });
      success = response.successCount > 0;
    } catch {
      transportAmbiguous = true;
    }
    const finalizedAt = boundaryClock();
    try {
      const finalizedSent = await runAccountMutationTransaction(
        firestore,
        claimed.ownerUid,
        async (transaction) => {
          const riskRef = firestore.doc(
            `users/${claimed.ownerUid}/weatherRisks/${claimed.riskId}`,
          );
          const [currentAlert, risk] = await Promise.all([
            transaction.get(alert.ref),
            transaction.get(riskRef),
          ]);
          if (
            !currentAlert.exists ||
            currentAlert.get("status") !== "SEND_MAY_HAVE_OCCURRED"
          ) return false;
          transaction.update(alert.ref, {
            status: transportAmbiguous ? "SENT_AMBIGUOUS" : success ? "SENT" : "FAILED",
            sentAt: success ? FieldValue.serverTimestamp() : null,
            failureKind: transportAmbiguous
              ? "TRANSPORT_AMBIGUOUS"
              : success
                ? null
                : "FCM_REJECTED",
            ...terminalWeatherAlertFields(finalizedAt),
            leaseExpiresAt: FieldValue.delete(),
            updatedAt: FieldValue.serverTimestamp(),
          });
          if (success && risk.exists && risk.get("transition") === claimed.transition) {
            const riskRevision = integer(risk.get("revision"), "Weather risk revision");
            const riskObservedAt = risk.get("observedAt");
            transaction.update(riskRef, {
              deliveredTransition: claimed.transition,
              ...(riskObservedAt instanceof Timestamp
                ? { expiresAt: weatherRetentionTimestamp(riskObservedAt.toDate()) }
                : {}),
              revision: riskRevision + 1,
              expectedRevision: riskRevision,
              idempotencyKey: `weather-delivered-${alert.id}`,
              updatedAt: FieldValue.serverTimestamp(),
            });
          }
          return success && !transportAmbiguous;
        },
      );
      if (finalizedSent) {
        await recordWeatherAnalytics(hooks.analytics, {
          ownerUid: claimed.ownerUid,
          eventName: "WEATHER_RISK_NOTIFICATION_SENT",
          operationIdentifier: alert.id,
        });
      }
    } finally {
      await releaseWeatherEndpointLeases(
        firestore,
        claimed.ownerUid,
        alert.id,
        authorized,
      );
    }
  }
}

type ClaimedAlert = Readonly<{
  ownerUid: string;
  plantId: string;
  plantName: string;
  riskId: string;
  transition: number;
  action: string;
  riskType: WeatherRiskType;
  riskRevision: number;
  snapshotRevision: number;
  settingsRevision: number;
  preferenceRevision: number;
  contentId: string;
  contentRevision: number;
  source: "MANUAL" | "DEVICE";
  locationConsentGeneration: number | null;
}>;

type WeatherEndpointVersion = Readonly<{
  endpointId: string;
  token: string;
  generation: number;
}>;

async function claimWeatherAlert(
  firestore: Firestore,
  alertRef: FirebaseFirestore.DocumentReference,
  now: Date,
): Promise<ClaimedAlert | null> {
  const ownerUid = alertRef.parent.parent?.id;
  if (ownerUid === undefined || !opaqueId.test(ownerUid)) return null;
  return runAccountMutationTransaction(firestore, ownerUid, async (transaction) => {
    const alert = await transaction.get(alertRef);
    if (!alert.exists || alert.get("status") !== "PENDING") return null;
    const storedOwnerUid = alert.get("ownerUid");
    const plantId = alert.get("plantId");
    const riskId = alert.get("riskId");
    const transition = alert.get("transition");
    if (storedOwnerUid !== ownerUid || typeof plantId !== "string" || typeof riskId !== "string" || typeof transition !== "number") {
      transaction.update(alertRef, {
        status: "CANCELLED",
        failureKind: "MALFORMED",
        ...terminalWeatherAlertFields(now),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return null;
    }
    const [plant, risk, settings, preference, consent] = await Promise.all([
      transaction.get(firestore.doc(`users/${ownerUid}/personalPlants/${plantId}`)),
      transaction.get(firestore.doc(`users/${ownerUid}/weatherRisks/${riskId}`)),
      transaction.get(firestore.doc(`users/${ownerUid}/weatherSettings/current`)),
      transaction.get(firestore.doc(`users/${ownerUid}/weatherPlantSettings/${plantId}`)),
      transaction.get(firestore.doc(`users/${ownerUid}/consents/location`)),
    ]);
    if (
      !plant.exists || plant.get("ownerUid") !== ownerUid ||
      !risk.exists || risk.get("active") !== true || risk.get("transition") !== transition ||
      risk.get("type") !== alert.get("riskType") ||
      !settings.exists || settings.get("globalAlertsEnabled") === false ||
      (preference.exists && preference.get("enabled") === false)
    ) {
      transaction.update(alertRef, {
        status: "CANCELLED",
        failureKind: "TARGET_CHANGED",
        ...terminalWeatherAlertFields(now),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return null;
    }
    const contentId = plant.get("contentId");
    if (typeof contentId !== "string" || !opaqueId.test(contentId)) {
      transaction.update(alertRef, {
        status: "CANCELLED",
        failureKind: "TARGET_CHANGED",
        ...terminalWeatherAlertFields(now),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return null;
    }
    const [content, snapshot] = await Promise.all([
      transaction.get(firestore.doc(`plantContents/${contentId}`)),
      transaction.get(firestore.doc(`users/${ownerUid}/weatherSnapshots/current`)),
    ]);
    const provenance = matchingWeatherProvenance(alert, risk, snapshot);
    const currentType = risk.get("type");
    const alertExpiresAt = alert.get("expiresAt");
    const riskObservedAt = risk.get("observedAt");
    const snapshotObservedAt = snapshot.get("observedAt");
    if (
      !content.exists || content.get("publicationState") !== "PUBLIC" ||
      provenance === null ||
      !weatherConsentAuthorizes(consent, ownerUid, provenance) ||
      !weatherSnapshotFresh(snapshot, now) ||
      !(alertExpiresAt instanceof Timestamp) || alertExpiresAt.toDate() <= now ||
      !(riskObservedAt instanceof Timestamp) || !(snapshotObservedAt instanceof Timestamp) ||
      riskObservedAt.toMillis() !== snapshotObservedAt.toMillis() ||
      typeof currentType !== "string" ||
      !riskTypes.includes(currentType as WeatherRiskType) ||
      !riskStillApplies(snapshot, content, plantId, plant.get("displayName"), currentType as WeatherRiskType)
    ) {
      transaction.update(alertRef, {
        status: "CANCELLED",
        failureKind: "TARGET_CHANGED",
        ...terminalWeatherAlertFields(now),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return null;
    }
    const plantName = alert.get("plantName");
    const action = alert.get("action");
    if (typeof plantName !== "string" || typeof action !== "string") return null;
    transaction.update(alertRef, {
      status: "CLAIMED",
      claimedAt: FieldValue.serverTimestamp(),
      leaseExpiresAt: Timestamp.fromMillis(now.valueOf() + WEATHER_ALERT_LEASE_MS),
      updatedAt: FieldValue.serverTimestamp(),
    });
    return {
      ownerUid,
      plantId,
      plantName,
      riskId,
      transition,
      action,
      riskType: currentType as WeatherRiskType,
      riskRevision: integer(risk.get("revision"), "Weather risk revision"),
      snapshotRevision: integer(snapshot.get("revision"), "Weather snapshot revision"),
      settingsRevision: integer(settings.get("revision"), "Weather settings revision"),
      preferenceRevision: preference.exists
        ? integer(preference.get("revision"), "Weather preference revision")
        : 0,
      contentId,
      contentRevision: integerOrNull(content.get("revision")) ?? 0,
      source: provenance.source,
      locationConsentGeneration: provenance.locationConsentGeneration,
    };
  });
}

async function authorizeWeatherAlertSend(
  firestore: Firestore,
  alertRef: FirebaseFirestore.DocumentReference,
  claimed: ClaimedAlert,
  endpoints: readonly WeatherEndpointVersion[],
  now: Date,
): Promise<readonly WeatherEndpointVersion[] | null> {
  return runAccountMutationTransaction(firestore, claimed.ownerUid, async (transaction) => {
    const plantRef = firestore.doc(`users/${claimed.ownerUid}/personalPlants/${claimed.plantId}`);
    const riskRef = firestore.doc(`users/${claimed.ownerUid}/weatherRisks/${claimed.riskId}`);
    const snapshotRef = firestore.doc(`users/${claimed.ownerUid}/weatherSnapshots/current`);
    const settingsRef = firestore.doc(`users/${claimed.ownerUid}/weatherSettings/current`);
    const preferenceRef = firestore.doc(`users/${claimed.ownerUid}/weatherPlantSettings/${claimed.plantId}`);
    const contentRef = firestore.doc(`plantContents/${claimed.contentId}`);
    const consentRef = firestore.doc(`users/${claimed.ownerUid}/consents/location`);
    const endpointRefs = endpoints.map((endpoint) =>
      firestore.doc(`users/${claimed.ownerUid}/notificationEndpoints/${endpoint.endpointId}`)
    );
    const ownerRefs = endpoints.map((endpoint) =>
      firestore.doc(`notificationEndpointOwners/${endpoint.endpointId}`)
    );
    const [alert, plant, risk, snapshot, settings, preference, content, consent, endpointDocs, ownerDocs] = await Promise.all([
      transaction.get(alertRef),
      transaction.get(plantRef),
      transaction.get(riskRef),
      transaction.get(snapshotRef),
      transaction.get(settingsRef),
      transaction.get(preferenceRef),
      transaction.get(contentRef),
      transaction.get(consentRef),
      Promise.all(endpointRefs.map((ref) => transaction.get(ref))),
      Promise.all(ownerRefs.map((ref) => transaction.get(ref))),
    ]);
    const leaseExpiresAt = alert.get("leaseExpiresAt");
    const claimMatches = alert.exists && alert.get("status") === "CLAIMED" &&
      alert.get("ownerUid") === claimed.ownerUid && alert.get("plantId") === claimed.plantId &&
      alert.get("riskId") === claimed.riskId && alert.get("riskType") === claimed.riskType &&
      alert.get("transition") === claimed.transition && leaseExpiresAt instanceof Timestamp &&
      leaseExpiresAt.toDate() > now;
    if (!claimMatches) return null;

    const alertFreshUntilValue = alert.get("freshUntil");
    const alertFreshUntil = alertFreshUntilValue instanceof Timestamp
      ? alertFreshUntilValue
      : alert.get("expiresAt");
    const snapshotFreshUntil = weatherFreshUntil(snapshot);
    const temporalValid = weatherSnapshotFresh(snapshot, now) &&
      alertFreshUntil instanceof Timestamp && alertFreshUntil.toDate() > now &&
      snapshotFreshUntil !== null &&
      alertFreshUntil.toMillis() === snapshotFreshUntil.toMillis();
    if (!temporalValid) {
      transaction.update(alertRef, {
        status: "CANCELLED",
        failureKind: "PRE_SEND_EXPIRED",
        ...terminalWeatherAlertFields(now),
        leaseExpiresAt: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return null;
    }

    const riskObservedAt = risk.get("observedAt");
    const snapshotObservedAt = snapshot.get("observedAt");
    const provenance = matchingWeatherProvenance(alert, risk, snapshot);
    const targetValid = provenance !== null &&
      provenance.source === claimed.source &&
      provenance.locationConsentGeneration === claimed.locationConsentGeneration &&
      weatherConsentAuthorizes(consent, claimed.ownerUid, provenance) &&
      plant.exists && plant.get("ownerUid") === claimed.ownerUid &&
      plant.get("contentId") === claimed.contentId && content.exists &&
      content.get("publicationState") === "PUBLIC" &&
      (integerOrNull(content.get("revision")) ?? 0) === claimed.contentRevision &&
      riskStillApplies(snapshot, content, claimed.plantId, plant.get("displayName"), claimed.riskType) &&
      risk.exists && risk.get("active") === true && risk.get("type") === claimed.riskType &&
      risk.get("transition") === claimed.transition &&
      integerOrNull(risk.get("revision")) === claimed.riskRevision &&
      riskObservedAt instanceof Timestamp && snapshotObservedAt instanceof Timestamp &&
      riskObservedAt.toMillis() === snapshotObservedAt.toMillis() &&
      integerOrNull(snapshot.get("revision")) === claimed.snapshotRevision &&
      settings.exists && settings.get("globalAlertsEnabled") !== false &&
      integerOrNull(settings.get("revision")) === claimed.settingsRevision &&
      (!preference.exists || (
        preference.get("ownerUid") === claimed.ownerUid && preference.get("enabled") !== false &&
        integerOrNull(preference.get("revision")) === claimed.preferenceRevision
      ));
    if (!targetValid) {
      transaction.update(alertRef, {
        status: "CANCELLED",
        failureKind: "PRE_SEND_TARGET_CHANGED",
        ...terminalWeatherAlertFields(now),
        leaseExpiresAt: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return null;
    }

    const authorized = endpoints.filter((endpoint, index) => {
      const document = endpointDocs[index]!;
      const owner = ownerDocs[index]!;
      return document.exists && document.get("ownerUid") === claimed.ownerUid &&
        document.get("notificationsEnabled") === true && document.get("token") === endpoint.token &&
        document.get("generation") === endpoint.generation && owner.exists &&
        owner.get("state") === "REGISTERED" && owner.get("ownerUid") === claimed.ownerUid &&
        owner.get("notificationsEnabled") === true && owner.get("token") === endpoint.token &&
        owner.get("generation") === endpoint.generation;
    });
    if (authorized.length !== endpoints.length || authorized.length === 0) {
      transaction.update(alertRef, {
        status: "PENDING",
        failureKind: "PRE_SEND_ENDPOINT_CHANGED",
        leaseExpiresAt: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return null;
    }
    for (const endpoint of authorized) {
      const index = endpoints.indexOf(endpoint);
      const owner = ownerDocs[index]!;
      const leases = weatherActiveSendLeases(owner.get("activeSendLeases"), now);
      transaction.set(ownerRefs[index]!, {
        activeSendLeases: {
          ...leases,
          [alertRef.id]: Timestamp.fromMillis(now.valueOf() + WEATHER_ALERT_ENDPOINT_LEASE_MS),
        },
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: true });
    }
    transaction.update(alertRef, {
      status: "SEND_MAY_HAVE_OCCURRED",
      authorizedEndpointIds: authorized.map((endpoint) => endpoint.endpointId),
      leaseExpiresAt: Timestamp.fromMillis(now.valueOf() + WEATHER_ALERT_LEASE_MS),
      updatedAt: FieldValue.serverTimestamp(),
    });
    return authorized;
  });
}

async function releaseWeatherEndpointLeases(
  firestore: Firestore,
  ownerUid: string,
  alertId: string,
  endpoints: readonly WeatherEndpointVersion[],
): Promise<void> {
  await firestore.runTransaction(async (transaction) => {
    const refs = endpoints.map((endpoint) => firestore.doc(`notificationEndpointOwners/${endpoint.endpointId}`));
    const owners = await Promise.all(refs.map((ref) => transaction.get(ref)));
    owners.forEach((owner, index) => {
      if (!owner.exists || owner.get("ownerUid") !== ownerUid) return;
      const leases = weatherActiveSendLeases(owner.get("activeSendLeases"), new Date());
      if (!(alertId in leases)) return;
      delete leases[alertId];
      transaction.set(refs[index]!, { activeSendLeases: leases, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    });
  });
}

function weatherActiveSendLeases(value: unknown, now: Date): Record<string, Timestamp> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return {};
  return Object.fromEntries(Object.entries(value).filter(([key, expiresAt]) =>
    opaqueId.test(key) && expiresAt instanceof Timestamp && expiresAt.toDate() > now
  ));
}

function weatherSnapshotFresh(snapshot: DocumentSnapshot, now: Date): boolean {
  if (!snapshot.exists || snapshot.get("stale") === true) return false;
  const observedAt = snapshot.get("observedAt");
  const freshUntil = weatherFreshUntil(snapshot);
  return observedAt instanceof Timestamp && freshUntil !== null &&
    observedAt.toDate() <= now && freshUntil.toDate() > now &&
    freshUntil.toMillis() === observedAt.toMillis() + WEATHER_FRESHNESS_MS;
}

function weatherFreshUntil(snapshot: DocumentSnapshot): Timestamp | null {
  const freshUntil = snapshot.get("freshUntil");
  if (freshUntil instanceof Timestamp) return freshUntil;
  const legacyExpiresAt = snapshot.get("expiresAt");
  return legacyExpiresAt instanceof Timestamp ? legacyExpiresAt : null;
}

type WeatherProvenance = Readonly<{
  source: "MANUAL" | "DEVICE";
  locationConsentGeneration: number | null;
}>;

function matchingWeatherProvenance(
  ...documents: readonly DocumentSnapshot[]
): WeatherProvenance | null {
  const provenances = documents.map(weatherProvenance);
  const first = provenances[0];
  if (
    first === undefined ||
    first === null ||
    provenances.some((provenance) =>
      provenance === null ||
      provenance.source !== first.source ||
      provenance.locationConsentGeneration !== first.locationConsentGeneration
    )
  ) return null;
  return first;
}

function weatherProvenance(document: DocumentSnapshot): WeatherProvenance | null {
  if (!document.exists) return null;
  const source = document.get("source");
  const generation = document.get("locationConsentGeneration");
  if (source === "MANUAL" && generation === null) {
    return { source, locationConsentGeneration: null };
  }
  if (
    source === "DEVICE" &&
    typeof generation === "number" &&
    Number.isSafeInteger(generation) &&
    generation >= 1
  ) {
    return { source, locationConsentGeneration: generation };
  }
  return null;
}

function weatherConsentAuthorizes(
  consent: DocumentSnapshot,
  ownerUid: string,
  provenance: WeatherProvenance,
): boolean {
  return provenance.source === "MANUAL" || (
    consent.exists &&
    consent.get("ownerUid") === ownerUid &&
    consent.get("granted") === true &&
    consent.get("commandGeneration") === provenance.locationConsentGeneration
  );
}

async function recordWeatherAnalytics(
  analytics: ServerAnalyticsRecorder | undefined,
  operation: ServerAnalyticsOperation,
): Promise<void> {
  if (analytics === undefined) return;
  try {
    await analytics(operation);
  } catch {
    // Analytics is explicitly best-effort and must never change weather outcomes.
  }
}

function integerOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

async function recoverExpiredWeatherAlerts(
  firestore: Firestore,
  ownerUid: string | undefined,
  limit: number,
  now: Date,
): Promise<void> {
  let query: FirebaseFirestore.Query = ownerUid === undefined
    ? firestore.collectionGroup("weatherAlerts")
    : firestore.collection(`users/${ownerUid}/weatherAlerts`);
  const candidates = await query
    .where("status", "in", ["CLAIMED", "SEND_MAY_HAVE_OCCURRED"])
    .where("leaseExpiresAt", "<=", Timestamp.fromDate(now))
    .orderBy("leaseExpiresAt", "asc")
    .limit(limit)
    .get();
  for (const candidate of candidates.docs) {
    const candidateOwnerUid = candidate.ref.parent.parent?.id;
    if (candidateOwnerUid === undefined) continue;
    await runAccountMutationTransaction(firestore, candidateOwnerUid, async (transaction) => {
      const current = await transaction.get(candidate.ref);
      const expiresAt = current.get("leaseExpiresAt");
      if (!(expiresAt instanceof Timestamp) || expiresAt.toDate() > now) return;
      if (current.get("status") === "CLAIMED") {
        transaction.update(candidate.ref, {
          status: "PENDING",
          failureKind: "CLAIM_RECOVERED",
          leaseExpiresAt: FieldValue.delete(),
          updatedAt: FieldValue.serverTimestamp(),
        });
      } else if (current.get("status") === "SEND_MAY_HAVE_OCCURRED") {
        transaction.update(candidate.ref, {
          status: "SENT_AMBIGUOUS",
          failureKind: "PROCESS_DIED_AFTER_SEND_BOUNDARY",
          ...terminalWeatherAlertFields(now),
          leaseExpiresAt: FieldValue.delete(),
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
    });
  }
}

const WEATHER_FRESHNESS_MS = 3 * 60 * 60 * 1000;
const WEATHER_ALERT_LEASE_MS = 5 * 60 * 1000;
const WEATHER_CONSENT_CLEANUP_PAGE_SIZE = 200;
const WEATHER_CONSENT_CLEANUP_MAX_PAGES = 8;
const WEATHER_ALERT_ENDPOINT_LEASE_MS = 10 * 60 * 1000;

function weatherRiskLabel(type: WeatherRiskType): string {
  switch (type) {
    case "HIGH_TEMPERATURE": return "고온 주의";
    case "LOW_TEMPERATURE": return "저온 주의";
    case "DRY": return "건조 주의";
    case "OVERHUMID": return "과습 주의";
  }
}

function riskStillApplies(
  snapshot: DocumentSnapshot,
  content: DocumentSnapshot,
  plantId: string,
  plantNameValue: unknown,
  type: WeatherRiskType,
): boolean {
  const observedAt = snapshot.get("observedAt");
  const temperature = snapshot.get("temperatureCelsius");
  const humidity = snapshot.get("humidityPercent");
  const precipitation = snapshot.get("precipitationMillimeters");
  if (
    !(observedAt instanceof Timestamp) ||
    typeof temperature !== "number" || !Number.isFinite(temperature) ||
    typeof humidity !== "number" || !Number.isInteger(humidity) ||
    typeof precipitation !== "number" || !Number.isFinite(precipitation)
  ) return false;
  const weather: WeatherSnapshot = {
    regionCode: typeof snapshot.get("regionCode") === "string" ? snapshot.get("regionCode") as string : "current",
    regionName: typeof snapshot.get("regionName") === "string" ? snapshot.get("regionName") as string : "관리 지역",
    latitude: 0,
    longitude: 0,
    temperatureCelsius: temperature,
    humidityPercent: humidity,
    precipitationMillimeters: precipitation,
    observedAt: observedAt.toDate(),
  };
  return evaluatePlantRisks(weather, {
    plantId,
    plantName: typeof plantNameValue === "string" ? plantNameValue : "등록 식물",
    minimumTemperatureCelsius: nullableNumber(content, "minimumTemperatureCelsius"),
    maximumTemperatureCelsius: nullableNumber(content, "maximumTemperatureCelsius"),
    minimumHumidityPercent: nullableNumber(content, "minimumHumidityPercent"),
    maximumHumidityPercent: nullableNumber(content, "maximumHumidityPercent"),
  }).some((risk) => risk.type === type);
}

function assertLocationConsentPrecondition(
  consent: DocumentSnapshot,
  ownerUid: string,
  expectedGeneration: number | null,
): void {
  if (expectedGeneration === null) return;
  if (
    !consent.exists ||
    consent.get("ownerUid") !== ownerUid ||
    consent.get("granted") !== true ||
    consent.get("commandGeneration") !== expectedGeneration
  ) {
    throw new WeatherError("aborted", "Weather location consent changed during refresh");
  }
}

function consentDocument(
  ownerUid: string,
  granted: boolean,
  commandGeneration: number,
  revision: number,
): DocumentData {
  return {
    ownerUid,
    type: "LOCATION",
    granted,
    commandGeneration,
    recordedAt: FieldValue.serverTimestamp(),
    revision: revision + 1,
    expectedRevision: revision,
    idempotencyKey: `weather-consent-${commandGeneration}`,
    updatedAt: FieldValue.serverTimestamp(),
  };
}

function settingsDocument(ownerUid: string, revision: number, patch: Readonly<Record<string, unknown>>): DocumentData {
  return {
    ownerUid,
    ...patch,
    revision: revision + 1,
    expectedRevision: revision,
    idempotencyKey: `weather-settings-${randomUUID()}`,
    updatedAt: FieldValue.serverTimestamp(),
  };
}

function regionDocument(region: WeatherRegion): DocumentData {
  return {
    regionCode: region.regionCode,
    regionName: region.regionName,
    latitude: region.latitude,
    longitude: region.longitude,
    source: region.source,
  };
}

function parseRegion(snapshot: DocumentSnapshot, field: string, source: "MANUAL" | "DEVICE"): WeatherRegion | null {
  if (!snapshot.exists) return null;
  const value = snapshot.get(field);
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const data = Object.fromEntries(Object.entries(value));
  if (
    typeof data.regionCode !== "string" || !opaqueId.test(data.regionCode) ||
    typeof data.regionName !== "string" || data.regionName.length === 0 ||
    typeof data.latitude !== "number" || !Number.isFinite(data.latitude) ||
    typeof data.longitude !== "number" || !Number.isFinite(data.longitude) ||
    data.source !== source
  ) return null;
  return {
    regionCode: data.regionCode,
    regionName: data.regionName,
    latitude: data.latitude,
    longitude: data.longitude,
    source,
  };
}

function environmentFromPlant(
  plant: DocumentSnapshot,
  contentById: ReadonlyMap<string, DocumentSnapshot>,
): PlantEnvironment {
  const contentId = plant.get("contentId");
  const content = typeof contentId === "string" ? contentById.get(contentId) : undefined;
  return {
    plantId: plant.id,
    plantName: typeof plant.get("displayName") === "string"
      ? plant.get("displayName") as string
      : "등록 식물",
    minimumTemperatureCelsius: nullableNumber(content, "minimumTemperatureCelsius"),
    maximumTemperatureCelsius: nullableNumber(content, "maximumTemperatureCelsius"),
    minimumHumidityPercent: nullableNumber(content, "minimumHumidityPercent"),
    maximumHumidityPercent: nullableNumber(content, "maximumHumidityPercent"),
  };
}

function completePlantEnvironment(plant: PlantEnvironment): boolean {
  return plant.minimumTemperatureCelsius !== null &&
    plant.maximumTemperatureCelsius !== null &&
    plant.minimumHumidityPercent !== null &&
    plant.maximumHumidityPercent !== null;
}

function samePlantEnvironments(
  left: readonly PlantEnvironment[],
  right: readonly PlantEnvironment[],
): boolean {
  const sortedLeft = [...left].sort((a, b) => a.plantId.localeCompare(b.plantId));
  const sortedRight = [...right].sort((a, b) => a.plantId.localeCompare(b.plantId));
  return sortedLeft.length === sortedRight.length && sortedLeft.every((plant, index) =>
    JSON.stringify(plant) === JSON.stringify(sortedRight[index])
  );
}

function sameStrings(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function nullableNumber(snapshot: DocumentSnapshot | undefined, field: string): number | null {
  if (snapshot === undefined) return null;
  const value = snapshot.get(field);
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function integer(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new WeatherError("failed-precondition", `${label} is invalid`);
  }
  return value;
}

function weatherAlertId(plantId: string, type: WeatherRiskType, transition: number): string {
  return createHash("sha256").update(`${plantId}:${type}:${transition}`).digest("hex").slice(0, 32);
}
