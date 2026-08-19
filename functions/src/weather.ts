import { createHash } from "node:crypto";

export type WeatherErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition"
  | "not-found"
  | "aborted"
  | "unavailable";

export class WeatherError extends Error {
  constructor(readonly code: WeatherErrorCode, message: string) {
    super(message);
    this.name = "WeatherError";
  }
}

export class WeatherConsentConflictError extends WeatherError {
  constructor(
    readonly commandGeneration: number,
    readonly granted: boolean,
    message = "Consent generation changed; reload required",
  ) {
    super("aborted", message);
    this.name = "WeatherConsentConflictError";
  }
}

export type WeatherRegion = Readonly<{
  regionCode: string;
  regionName: string;
  latitude: number;
  longitude: number;
  source: "MANUAL" | "DEVICE";
}>;

export type ApproximateLocation = Readonly<{ latitude: number; longitude: number }>;

export type WeatherSnapshot = Readonly<{
  regionCode: string;
  regionName: string;
  latitude: number;
  longitude: number;
  temperatureCelsius: number;
  humidityPercent: number;
  precipitationMillimeters: number;
  observedAt: Date;
  zoneId?: string;
}>;

export type PlantEnvironment = Readonly<{
  plantId: string;
  plantName: string;
  minimumTemperatureCelsius: number | null;
  maximumTemperatureCelsius: number | null;
  minimumHumidityPercent: number | null;
  maximumHumidityPercent: number | null;
}>;

export type WeatherRiskType =
  | "HIGH_TEMPERATURE"
  | "LOW_TEMPERATURE"
  | "DRY"
  | "OVERHUMID";

export type EvaluatedWeatherRisk = Readonly<{
  plantId: string;
  plantName: string;
  type: WeatherRiskType;
  reason: string;
  action: string;
  detectedAt?: Date;
}>;

const actions: Readonly<Record<WeatherRiskType, string>> = {
  HIGH_TEMPERATURE: "직사광선을 피하고 서늘한 곳으로 옮겨 주세요.",
  LOW_TEMPERATURE: "찬바람을 피해 따뜻한 실내로 옮겨 주세요.",
  DRY: "흙과 잎 상태를 확인하고 필요한 경우 주변에 분무해 주세요.",
  OVERHUMID: "물을 잠시 미루고 공기가 잘 통하도록 환기해 주세요.",
};

export function evaluatePlantRisks(
  snapshot: WeatherSnapshot,
  plant: PlantEnvironment,
): readonly EvaluatedWeatherRisk[] {
  const {
    minimumTemperatureCelsius: minimumTemperature,
    maximumTemperatureCelsius: maximumTemperature,
    minimumHumidityPercent: minimumHumidity,
    maximumHumidityPercent: maximumHumidity,
  } = plant;
  if (
    minimumTemperature === null ||
    maximumTemperature === null ||
    minimumHumidity === null ||
    maximumHumidity === null ||
    minimumTemperature > maximumTemperature ||
    minimumHumidity > maximumHumidity
  ) return [];

  const risks: EvaluatedWeatherRisk[] = [];
  if (snapshot.temperatureCelsius > maximumTemperature) {
    risks.push({
      plantId: plant.plantId,
      plantName: plant.plantName,
      type: "HIGH_TEMPERATURE",
      reason: `현재 ${snapshot.temperatureCelsius}°C로 적정 최고 온도 ${maximumTemperature}°C보다 높아요.`,
      action: actions.HIGH_TEMPERATURE,
    });
  }
  if (snapshot.temperatureCelsius < minimumTemperature) {
    risks.push({
      plantId: plant.plantId,
      plantName: plant.plantName,
      type: "LOW_TEMPERATURE",
      reason: `현재 ${snapshot.temperatureCelsius}°C로 적정 최저 온도 ${minimumTemperature}°C보다 낮아요.`,
      action: actions.LOW_TEMPERATURE,
    });
  }
  if (snapshot.humidityPercent < minimumHumidity) {
    risks.push({
      plantId: plant.plantId,
      plantName: plant.plantName,
      type: "DRY",
      reason: `현재 습도 ${snapshot.humidityPercent}%로 적정 최저 습도 ${minimumHumidity}%보다 낮아요.`,
      action: actions.DRY,
    });
  }
  if (snapshot.humidityPercent > maximumHumidity) {
    risks.push({
      plantId: plant.plantId,
      plantName: plant.plantName,
      type: "OVERHUMID",
      reason: `현재 습도 ${snapshot.humidityPercent}%로 적정 최고 습도 ${maximumHumidity}%보다 높아요.`,
      action: actions.OVERHUMID,
    });
  }
  return risks;
}

export function isWeatherStale(observedAt: Date, now: Date): boolean {
  if (Number.isNaN(observedAt.valueOf()) || Number.isNaN(now.valueOf())) return true;
  const age = now.valueOf() - observedAt.valueOf();
  return age < 0 || age > 3 * 60 * 60 * 1000;
}

export type WeatherAlertDecision = Readonly<{
  globalEnabled: boolean;
  plantEnabled: boolean;
  stale: boolean;
  wasActive: boolean;
  active: boolean;
  transition: number;
  deliveredTransition: number | null;
}>;

export function shouldDeliverWeatherAlert(decision: WeatherAlertDecision): boolean {
  return decision.globalEnabled &&
    decision.plantEnabled &&
    !decision.stale &&
    !decision.wasActive &&
    decision.active &&
    decision.deliveredTransition !== decision.transition;
}

export function resolveWeatherRegion(input: Readonly<{
  manual: WeatherRegion | null;
  currentLocation: ApproximateLocation | null;
  locationConsent: boolean;
}>): WeatherRegion {
  if (input.manual !== null) return input.manual;
  if (!input.locationConsent) {
    throw new WeatherError("failed-precondition", "Location consent is required");
  }
  const location = input.currentLocation;
  if (location === null) throw new WeatherError("failed-precondition", "Location is unavailable");
  validateCoordinates(location.latitude, location.longitude);
  const latitude = roundedCoordinate(location.latitude);
  const longitude = roundedCoordinate(location.longitude);
  return {
    regionCode: coordinateRegionCode(latitude, longitude),
    regionName: "현재 위치 주변",
    latitude,
    longitude,
    source: "DEVICE",
  };
}

export function coordinateRegionCode(latitude: number, longitude: number): string {
  return `device-${createHash("sha256").update(`${latitude.toFixed(2)},${longitude.toFixed(2)}`).digest("hex").slice(0, 16)}`;
}

function roundedCoordinate(value: number): number {
  return Math.round(value * 100) / 100;
}

function validateCoordinates(latitude: number, longitude: number): void {
  if (
    !Number.isFinite(latitude) ||
    !Number.isFinite(longitude) ||
    latitude < -90 ||
    latitude > 90 ||
    longitude < -180 ||
    longitude > 180
  ) throw new WeatherError("invalid-argument", "Coordinates are invalid");
}

function record(value: unknown): Readonly<Record<string, unknown>> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new WeatherError("unavailable", "Weather provider response is malformed");
  }
  return Object.fromEntries(Object.entries(value));
}

function finite(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new WeatherError("unavailable", "Weather provider response is malformed");
  }
  return value;
}

export function canonicalWeather(payload: unknown, region: WeatherRegion): WeatherSnapshot {
  const root = record(payload);
  const current = record(root.current);
  const temperatureCelsius = finite(current.temp);
  const humidityPercent = finite(current.humidity);
  const epochSeconds = finite(current.dt);
  const zoneId = root.timezone;
  const rain = current.rain === undefined ? null : record(current.rain);
  const precipitationMillimeters = rain === null ? 0 : finite(rain["1h"] ?? 0);
  if (
    !Number.isInteger(humidityPercent) ||
    humidityPercent < 0 ||
    humidityPercent > 100 ||
    precipitationMillimeters < 0 ||
    typeof zoneId !== "string" ||
    zoneId.length === 0
  ) throw new WeatherError("unavailable", "Weather provider response is outside supported ranges");
  const observedAt = new Date(epochSeconds * 1000);
  if (Number.isNaN(observedAt.valueOf())) {
    throw new WeatherError("unavailable", "Weather observation time is invalid");
  }
  return {
    regionCode: region.regionCode,
    regionName: region.regionName,
    latitude: region.latitude,
    longitude: region.longitude,
    temperatureCelsius,
    humidityPercent,
    precipitationMillimeters,
    observedAt,
    zoneId,
  };
}
