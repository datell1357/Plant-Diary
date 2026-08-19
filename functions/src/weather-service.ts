import {
  WeatherError,
  evaluatePlantRisks,
  isWeatherStale,
  resolveWeatherRegion,
  type ApproximateLocation,
  type EvaluatedWeatherRisk,
  type PlantEnvironment,
  type WeatherRegion,
  type WeatherSnapshot,
} from "./weather.js";

export type WeatherAuth = Readonly<{ uid: string }>;

export type WeatherContext = Readonly<{
  ownerUid: string;
  zoneId: string;
  manualRegion: WeatherRegion | null;
  deviceRegion: WeatherRegion | null;
  locationConsent: boolean;
  locationConsentGeneration: number | null;
  globalAlertsEnabled: boolean;
  plantAlerts: ReadonlyMap<string, boolean>;
  revision: number;
  plants: readonly PlantEnvironment[];
  lastKnownRisks: readonly EvaluatedWeatherRisk[];
}>;

export type WeatherEvaluationCommand = Readonly<{
  ownerUid: string;
  expectedRevision: number;
  region: WeatherRegion;
  snapshot: WeatherSnapshot;
  risks: readonly EvaluatedWeatherRisk[];
  unavailablePlantIds: readonly string[];
  plants: readonly PlantEnvironment[];
  stale: boolean;
  evaluatedAt: Date;
  globalAlertsEnabled: boolean;
  plantAlerts: ReadonlyMap<string, boolean>;
  switchToDeviceRegion: boolean;
  expectedLocationConsentGeneration: number | null;
}>;

export type WeatherConsentMutationResult = Readonly<{
  commandGeneration: number;
  granted: boolean;
  recovered?: true;
}>;

export interface WeatherStore {
  loadContext(ownerUid: string): Promise<WeatherContext>;
  commitEvaluation(command: WeatherEvaluationCommand): Promise<Readonly<{ revision: number }>>;
  recomputeSnapshotStaleness(
    ownerUid: string,
    evaluatedAt: Date,
    expectedLocationConsentGeneration: number | null,
  ): Promise<Readonly<{ stale: boolean }>>;
  setLocationConsent(
    ownerUid: string,
    granted: boolean,
    commandGeneration: number,
  ): Promise<WeatherConsentMutationResult>;
  recoverLocationConsent(ownerUid: string): Promise<WeatherConsentMutationResult>;
  setManualRegion(ownerUid: string, region: WeatherRegion, expectedRevision: number): Promise<number>;
  updateAlerts(
    ownerUid: string,
    globalEnabled: boolean,
    plants: ReadonlyMap<string, boolean>,
    expectedRevision: number,
  ): Promise<number>;
}

export interface WeatherProvider {
  current(region: WeatherRegion, signal?: AbortSignal): Promise<WeatherSnapshot>;
  search(query: string): Promise<readonly WeatherRegion[]>;
}

export type WeatherRefreshResult = Readonly<{
  snapshot: WeatherSnapshot;
  risks: readonly EvaluatedWeatherRisk[];
  unavailablePlantIds: readonly string[];
  stale: boolean;
  globalAlertsEnabled: boolean;
  plantAlerts: Readonly<Record<string, boolean>>;
  plants: Readonly<Record<string, string>>;
  revision: number;
}>;

export async function executeRefreshWeather(
  auth: WeatherAuth | null,
  input: unknown,
  store: WeatherStore,
  provider: WeatherProvider,
  time: Date | (() => Date) = () => new Date(),
  signal?: AbortSignal,
): Promise<WeatherRefreshResult> {
  const uid = authenticated(auth);
  const clock = typeof time === "function" ? time : () => time;
  const value = exactRecord(
    input,
    ["expectedOwnerUid"],
    ["location", "switchToDeviceRegion"],
  );
  assertOwner(value, uid);
  const switchToDeviceRegion = value.switchToDeviceRegion ?? false;
  if (typeof switchToDeviceRegion !== "boolean") {
    throw new WeatherError("invalid-argument", "Weather region source switch is invalid");
  }
  const context = await store.loadContext(uid);
  if (context.ownerUid !== uid) throw new WeatherError("permission-denied", "Weather scope is invalid");
  const location = optionalLocation(value.location);
  if (switchToDeviceRegion && location === null) {
    throw new WeatherError("invalid-argument", "Current location is required for a device switch");
  }
  const currentLocation = location ?? context.deviceRegion;
  const activeManualRegion = switchToDeviceRegion ? null : context.manualRegion;
  const region = resolveWeatherRegion({
    manual: activeManualRegion,
    currentLocation:
      currentLocation === null
        ? null
        : { latitude: currentLocation.latitude, longitude: currentLocation.longitude },
    locationConsent: context.locationConsent,
  });
  const expectedLocationConsentGeneration =
    region.source === "DEVICE" ? context.locationConsentGeneration : null;
  if (region.source === "DEVICE" && expectedLocationConsentGeneration === null) {
    throw new WeatherError(
      "failed-precondition",
      "Location consent generation is unavailable",
    );
  }
  let snapshot: WeatherSnapshot;
  try {
    snapshot = await provider.current(
      activeManualRegion === null && currentLocation !== null
        ? {
            ...region,
            regionName:
              location === null
                ? context.deviceRegion?.regionName ?? region.regionName
                : region.regionName,
          }
        : region,
      signal,
    );
  } catch (error: unknown) {
    await store.recomputeSnapshotStaleness(
      uid,
      clock(),
      expectedLocationConsentGeneration,
    );
    if (error instanceof WeatherError) throw error;
    throw new WeatherError("unavailable", "Weather provider is unavailable");
  }
  const evaluatedAt = clock();
  const stale = isWeatherStale(snapshot.observedAt, evaluatedAt);
  const completePlants = context.plants.filter(completeEnvironment);
  const unavailablePlantIds = context.plants
    .filter((plant) => !completeEnvironment(plant))
    .map((plant) => plant.plantId);
  const evaluatedRisks = completePlants.flatMap((plant) => evaluatePlantRisks(snapshot, plant));
  const authoritativePlantIds = new Set(context.plants.map((plant) => plant.plantId));
  const lastKnownRisks = context.lastKnownRisks.filter((risk) =>
    authoritativePlantIds.has(risk.plantId)
  );
  const risks = stale ? lastKnownRisks : evaluatedRisks;
  const committed = await store.commitEvaluation({
    ownerUid: uid,
    expectedRevision: context.revision,
    region,
    snapshot,
    risks: stale ? [] : evaluatedRisks,
    unavailablePlantIds,
    plants: context.plants,
    stale,
    evaluatedAt,
    globalAlertsEnabled: context.globalAlertsEnabled,
    plantAlerts: context.plantAlerts,
    switchToDeviceRegion,
    expectedLocationConsentGeneration,
  });
  return {
    snapshot,
    risks,
    unavailablePlantIds,
    stale,
    globalAlertsEnabled: context.globalAlertsEnabled,
    plantAlerts: Object.fromEntries(context.plants.map((plant) => [
      plant.plantId,
      context.plantAlerts.get(plant.plantId) !== false,
    ])),
    plants: Object.fromEntries(context.plants.map((plant) => [plant.plantId, plant.plantName])),
    revision: committed.revision,
  };
}

export async function executeSetLocationConsent(
  auth: WeatherAuth | null,
  input: unknown,
  store: WeatherStore,
): Promise<WeatherConsentMutationResult> {
  const uid = authenticated(auth);
  const value = exactRecord(
    input,
    ["expectedOwnerUid", "granted"],
    ["commandGeneration", "recoverLegacy"],
  );
  assertOwner(value, uid);
  if (typeof value.granted !== "boolean") {
    throw new WeatherError("invalid-argument", "Location consent must be boolean");
  }
  if (value.recoverLegacy === true) {
    if (value.granted || value.commandGeneration !== undefined) {
      throw new WeatherError("invalid-argument", "Consent recovery must be a revoke-only command");
    }
    return store.recoverLocationConsent(uid);
  }
  if (value.recoverLegacy !== undefined) {
    throw new WeatherError("invalid-argument", "Consent recovery command is invalid");
  }
  const commandGeneration = revisionField(value.commandGeneration);
  if (commandGeneration < 1) {
    throw new WeatherError("invalid-argument", "Consent command generation must be positive");
  }
  return store.setLocationConsent(uid, value.granted, commandGeneration);
}

export async function executeSearchWeatherRegions(
  auth: WeatherAuth | null,
  input: unknown,
  provider: WeatherProvider,
): Promise<readonly WeatherRegion[]> {
  const uid = authenticated(auth);
  const value = exactRecord(input, ["expectedOwnerUid", "query"]);
  assertOwner(value, uid);
  const query = stringField(value, "query").trim();
  if ([...query].length < 2 || [...query].length > 80) {
    throw new WeatherError("invalid-argument", "Region query must contain 2 to 80 characters");
  }
  try {
    return (await provider.search(query)).slice(0, 10).map(validateManualRegion);
  } catch (error: unknown) {
    if (error instanceof WeatherError) throw error;
    throw new WeatherError("unavailable", "Region search is unavailable");
  }
}

export async function executeSetManualWeatherRegion(
  auth: WeatherAuth | null,
  input: unknown,
  store: WeatherStore,
): Promise<Readonly<{ revision: number }>> {
  const uid = authenticated(auth);
  const value = exactRecord(input, ["expectedOwnerUid", "region", "expectedRevision"]);
  assertOwner(value, uid);
  const revision = revisionField(value.expectedRevision);
  const region = validateManualRegion(value.region);
  return { revision: await store.setManualRegion(uid, region, revision) };
}

export async function executeUpdateWeatherAlerts(
  auth: WeatherAuth | null,
  input: unknown,
  store: WeatherStore,
): Promise<Readonly<{ revision: number }>> {
  const uid = authenticated(auth);
  const value = exactRecord(input, ["expectedOwnerUid", "globalEnabled", "plants", "expectedRevision"]);
  assertOwner(value, uid);
  if (typeof value.globalEnabled !== "boolean" || !Array.isArray(value.plants) || value.plants.length > 200) {
    throw new WeatherError("invalid-argument", "Weather alert settings are invalid");
  }
  const plants = new Map<string, boolean>();
  for (const item of value.plants) {
    const plant = exactRecord(item, ["plantId", "enabled"]);
    const plantId = opaque(stringField(plant, "plantId"));
    if (typeof plant.enabled !== "boolean" || plants.has(plantId)) {
      throw new WeatherError("invalid-argument", "Plant alert setting is invalid");
    }
    plants.set(plantId, plant.enabled);
  }
  return {
    revision: await store.updateAlerts(
      uid,
      value.globalEnabled,
      plants,
      revisionField(value.expectedRevision),
    ),
  };
}

function completeEnvironment(plant: PlantEnvironment): boolean {
  return plant.minimumTemperatureCelsius !== null &&
    plant.maximumTemperatureCelsius !== null &&
    plant.minimumHumidityPercent !== null &&
    plant.maximumHumidityPercent !== null;
}

function authenticated(auth: WeatherAuth | null): string {
  if (auth === null) throw new WeatherError("unauthenticated", "Authentication is required");
  return opaque(auth.uid);
}

function assertOwner(value: Readonly<Record<string, unknown>>, uid: string): void {
  if (value.expectedOwnerUid !== uid) {
    throw new WeatherError("permission-denied", "Authenticated owner does not match the request");
  }
}

function exactRecord(
  input: unknown,
  required: readonly string[],
  optional: readonly string[] = [],
): Readonly<Record<string, unknown>> {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new WeatherError("invalid-argument", "Payload must be an object");
  }
  const value = Object.fromEntries(Object.entries(input));
  const allowed = new Set([...required, ...optional]);
  if (!required.every((field) => field in value) || !Object.keys(value).every((field) => allowed.has(field))) {
    throw new WeatherError("invalid-argument", "Payload fields do not match the contract");
  }
  return value;
}

function stringField(value: Readonly<Record<string, unknown>>, field: string): string {
  const candidate = value[field];
  if (typeof candidate !== "string" || candidate.length === 0) {
    throw new WeatherError("invalid-argument", `${field} must be a non-empty string`);
  }
  return candidate;
}

function opaque(value: string): string {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(value)) {
    throw new WeatherError("invalid-argument", "Identifier is invalid");
  }
  return value;
}

function revisionField(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new WeatherError("invalid-argument", "Revision is invalid");
  }
  return value;
}

function optionalLocation(value: unknown): ApproximateLocation | null {
  if (value === undefined || value === null) return null;
  const location = exactRecord(value, ["latitude", "longitude"]);
  if (typeof location.latitude !== "number" || typeof location.longitude !== "number") {
    throw new WeatherError("invalid-argument", "Location is invalid");
  }
  return { latitude: location.latitude, longitude: location.longitude };
}

function validateManualRegion(input: unknown): WeatherRegion {
  const value = exactRecord(input, ["regionCode", "regionName", "latitude", "longitude", "source"]);
  const regionCode = opaque(stringField(value, "regionCode"));
  const regionName = stringField(value, "regionName").trim();
  if (
    value.source !== "MANUAL" ||
    regionName.length === 0 ||
    [...regionName].length > 100 ||
    typeof value.latitude !== "number" ||
    !Number.isFinite(value.latitude) ||
    value.latitude < -90 ||
    value.latitude > 90 ||
    typeof value.longitude !== "number" ||
    !Number.isFinite(value.longitude) ||
    value.longitude < -180 ||
    value.longitude > 180
  ) throw new WeatherError("invalid-argument", "Manual region is invalid");
  return {
    regionCode,
    regionName,
    latitude: value.latitude,
    longitude: value.longitude,
    source: "MANUAL",
  };
}
