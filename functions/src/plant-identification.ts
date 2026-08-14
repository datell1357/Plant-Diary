export type IdentificationCandidate = Readonly<{
  publicContentId: string;
  koreanName: string | null;
  commonName: string | null;
  scientificName: string;
  confidence: number;
  thumbnailUrl: string | null;
}>;

export type IdentificationFailureReason =
  | "timeout"
  | "rate_limited"
  | "provider_unavailable"
  | "malformed_response";

export type IdentificationResponse =
  | Readonly<{ kind: "pending" }>
  | Readonly<{ kind: "candidates"; candidates: readonly IdentificationCandidate[] }>
  | Readonly<{ kind: "no_candidates" }>
  | Readonly<{ kind: "failed"; reason: IdentificationFailureReason }>;

export type StoredIdentificationRequest = Readonly<{
  ownerUid: string;
  temporaryOriginalPath: string;
}>;

export interface IdentificationRequestStore {
  runOnce(
    ownerUid: string,
    requestId: string,
    idempotencyKey: string,
    operation: (request: StoredIdentificationRequest) => Promise<IdentificationResponse>,
  ): Promise<IdentificationResponse>;
}

export interface PlantIdProvider {
  identify(photoPath: string): Promise<unknown>;
}

export type PlantIdentificationErrorReason =
  | "unauthenticated"
  | "invalid_argument"
  | "permission_denied"
  | "timeout"
  | "rate_limited"
  | "provider_unavailable";

export class PlantIdentificationError extends Error {
  readonly name = "PlantIdentificationError";

  constructor(readonly reason: PlantIdentificationErrorReason) {
    super(reason);
  }
}

const opaqueId = /^[A-Za-z0-9_-]{8,128}$/;
const publicContentId = /^[A-Za-z0-9_-]{1,128}$/;

function record(value: unknown): Readonly<Record<string, unknown>> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new PlantIdentificationError("provider_unavailable");
  }
  return Object.fromEntries(Object.entries(value));
}

function requestInput(value: unknown): Readonly<{ requestId: string; idempotencyKey: string }> {
  let parsed: Readonly<Record<string, unknown>>;
  try {
    parsed = record(value);
  } catch {
    throw new PlantIdentificationError("invalid_argument");
  }
  if (
    Object.keys(parsed).length !== 2
    || !opaqueId.test(typeof parsed.requestId === "string" ? parsed.requestId : "")
    || !opaqueId.test(typeof parsed.idempotencyKey === "string" ? parsed.idempotencyKey : "")
  ) {
    throw new PlantIdentificationError("invalid_argument");
  }
  return { requestId: String(parsed.requestId), idempotencyKey: String(parsed.idempotencyKey) };
}

function optionalThumbnail(value: unknown): string | null {
  if (value === undefined) return null;
  if (!Array.isArray(value) || value.length === 0) return null;
  const image = record(value[0]);
  if (typeof image.url !== "string") throw new PlantIdentificationError("provider_unavailable");
  const url = new URL(image.url);
  if (url.protocol !== "https:") throw new PlantIdentificationError("provider_unavailable");
  return url.toString();
}

function optionalCommonName(value: unknown): string | null {
  if (value === undefined) return null;
  const details = record(value);
  if (details.common_names === undefined) return null;
  if (!Array.isArray(details.common_names)) throw new PlantIdentificationError("provider_unavailable");
  const first = details.common_names[0];
  if (first === undefined) return null;
  if (typeof first !== "string" || first.trim().length === 0) {
    throw new PlantIdentificationError("provider_unavailable");
  }
  return first.trim();
}

function candidate(value: unknown): IdentificationCandidate {
  const parsed = record(value);
  if (
    typeof parsed.id !== "string"
    || !publicContentId.test(parsed.id)
    || typeof parsed.name !== "string"
    || parsed.name.trim().length === 0
    || typeof parsed.probability !== "number"
    || !Number.isFinite(parsed.probability)
    || parsed.probability < 0
    || parsed.probability > 1
  ) {
    throw new PlantIdentificationError("provider_unavailable");
  }
  const commonName = optionalCommonName(parsed.details);
  return {
    publicContentId: parsed.id,
    koreanName: commonName,
    commonName,
    scientificName: parsed.name.trim(),
    confidence: parsed.probability,
    thumbnailUrl: optionalThumbnail(parsed.similar_images),
  };
}

function normalizedResponse(value: unknown): IdentificationResponse {
  try {
    const root = record(value);
    const result = record(root.result);
    const classification = record(result.classification);
    if (!Array.isArray(classification.suggestions)) {
      throw new PlantIdentificationError("provider_unavailable");
    }
    const candidates = classification.suggestions
      .map(candidate)
      .sort((left, right) => right.confidence - left.confidence)
      .slice(0, 3);
    return candidates.length === 0
      ? { kind: "no_candidates" }
      : { kind: "candidates", candidates };
  } catch (error: unknown) {
    if (error instanceof PlantIdentificationError) {
      return { kind: "failed", reason: "malformed_response" };
    }
    throw error;
  }
}

export async function executePlantIdentification(
  auth: Readonly<{ uid: string }> | null,
  input: unknown,
  store: IdentificationRequestStore,
  provider: PlantIdProvider,
): Promise<IdentificationResponse> {
  if (auth === null) throw new PlantIdentificationError("unauthenticated");
  const parsed = requestInput(input);
  return store.runOnce(auth.uid, parsed.requestId, parsed.idempotencyKey, async (request) => {
    if (request.ownerUid !== auth.uid) throw new PlantIdentificationError("permission_denied");
    try {
      return normalizedResponse(await provider.identify(request.temporaryOriginalPath));
    } catch (error: unknown) {
      if (!(error instanceof PlantIdentificationError)) throw error;
      switch (error.reason) {
        case "timeout":
        case "rate_limited":
        case "provider_unavailable":
          return { kind: "failed", reason: error.reason };
        case "unauthenticated":
        case "invalid_argument":
        case "permission_denied":
          throw error;
      }
    }
  });
}
