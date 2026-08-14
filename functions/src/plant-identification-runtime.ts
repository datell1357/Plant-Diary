import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { getStorage, type Storage } from "firebase-admin/storage";
import ky, { TimeoutError } from "ky";
import type {
  IdentificationCandidate,
  IdentificationRequestStore,
  IdentificationResponse,
  PlantIdProvider,
  StoredIdentificationRequest,
} from "./plant-identification.js";
import { PlantIdentificationError } from "./plant-identification.js";

export type IdentificationRuntimeErrorReason = "not_found" | "permission_denied" | "malformed_state";

export class IdentificationRuntimeError extends Error {
  readonly name = "IdentificationRuntimeError";

  constructor(readonly reason: IdentificationRuntimeErrorReason) {
    super(reason);
  }
}

function record(value: unknown): Readonly<Record<string, unknown>> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new IdentificationRuntimeError("malformed_state");
  }
  return Object.fromEntries(Object.entries(value));
}

function storedCandidate(value: unknown): IdentificationCandidate {
  const parsed = record(value);
  if (
    typeof parsed.publicContentId !== "string"
    || typeof parsed.scientificName !== "string"
    || typeof parsed.confidence !== "number"
    || (parsed.koreanName !== null && typeof parsed.koreanName !== "string")
    || (parsed.commonName !== null && typeof parsed.commonName !== "string")
    || (parsed.thumbnailUrl !== null && typeof parsed.thumbnailUrl !== "string")
  ) {
    throw new IdentificationRuntimeError("malformed_state");
  }
  return {
    publicContentId: parsed.publicContentId,
    koreanName: parsed.koreanName,
    commonName: parsed.commonName,
    scientificName: parsed.scientificName,
    confidence: parsed.confidence,
    thumbnailUrl: parsed.thumbnailUrl,
  };
}

function storedResponse(value: unknown): IdentificationResponse {
  const parsed = record(value);
  switch (parsed.kind) {
    case "pending":
      return { kind: "pending" };
    case "no_candidates":
      return { kind: "no_candidates" };
    case "candidates": {
      if (!Array.isArray(parsed.candidates) || parsed.candidates.length < 1 || parsed.candidates.length > 3) {
        throw new IdentificationRuntimeError("malformed_state");
      }
      return { kind: "candidates", candidates: parsed.candidates.map(storedCandidate) };
    }
    case "failed":
      switch (parsed.reason) {
        case "timeout":
        case "rate_limited":
        case "provider_unavailable":
        case "malformed_response":
          return { kind: "failed", reason: parsed.reason };
        default:
          throw new IdentificationRuntimeError("malformed_state");
      }
    default:
      throw new IdentificationRuntimeError("malformed_state");
  }
}

type Claim =
  | Readonly<{ kind: "start"; request: StoredIdentificationRequest }>
  | Readonly<{ kind: "replay"; response: IdentificationResponse }>;

export class FirestoreIdentificationRequestStore implements IdentificationRequestStore {
  private static readonly STUCK_PENDING_MILLISECONDS = 2 * 60 * 1000;

  constructor(
    private readonly firestore: Firestore = getFirestore(),
    private readonly now: () => Date = () => new Date(),
  ) {}

  async runOnce(
    ownerUid: string,
    requestId: string,
    idempotencyKey: string,
    operation: (request: StoredIdentificationRequest) => Promise<IdentificationResponse>,
  ): Promise<IdentificationResponse> {
    const reference = this.firestore.doc(`users/${ownerUid}/identificationRequests/${requestId}`);
    const claim = await this.firestore.runTransaction<Claim>(async (transaction) => {
      const snapshot = await transaction.get(reference);
      if (!snapshot.exists) throw new IdentificationRuntimeError("not_found");
      const data = record(snapshot.data());
      if (data.ownerUid !== ownerUid) throw new IdentificationRuntimeError("permission_denied");
      if (data.identificationResult !== undefined) {
        return { kind: "replay", response: storedResponse(data.identificationResult) };
      }
      if (data.identificationOperationKey !== undefined) {
        const startedAt = data.identificationOperationStartedAt;
        const startedAtMilliseconds =
          startedAt instanceof Date
            ? startedAt.getTime()
            : typeof startedAt === "object"
              && startedAt !== null
              && "toMillis" in startedAt
              && typeof startedAt.toMillis === "function"
              ? startedAt.toMillis()
              : Number.NaN;
        if (
          Number.isFinite(startedAtMilliseconds)
          && this.now().getTime() - startedAtMilliseconds
            < FirestoreIdentificationRequestStore.STUCK_PENDING_MILLISECONDS
        ) {
          if (data.identificationOperationKey !== idempotencyKey) {
            throw new IdentificationRuntimeError("permission_denied");
          }
          return { kind: "replay", response: { kind: "pending" } };
        }
      }
      if (typeof data.temporaryOriginalPath !== "string") {
        throw new IdentificationRuntimeError("malformed_state");
      }
      const expectedPrefix = `identification-originals/${ownerUid}/${requestId}/`;
      const fileName = data.temporaryOriginalPath.slice(expectedPrefix.length);
      if (
        !data.temporaryOriginalPath.startsWith(expectedPrefix)
        || fileName.length === 0
        || fileName.includes("/")
        || fileName === "."
        || fileName === ".."
      ) {
        throw new IdentificationRuntimeError("malformed_state");
      }
      const startedAt = this.now();
      transaction.update(reference, {
        identificationOperationKey: idempotencyKey,
        identificationOperationStartedAt: startedAt,
        identificationStatus: "PENDING",
      });
      return {
        kind: "start",
        request: { ownerUid, temporaryOriginalPath: data.temporaryOriginalPath },
      };
    });
    if (claim.kind === "replay") return claim.response;
    const response = await operation(claim.request);
    await reference.update({
      identificationStatus: response.kind.toUpperCase(),
      identificationResult: response,
    });
    return response;
  }
}

export class PlantIdStorageProvider implements PlantIdProvider {
  constructor(
    private readonly client: PlantIdHttpClient,
    private readonly storage: Storage = getStorage(),
  ) {}

  async identify(photoPath: string): Promise<unknown> {
    const file = this.storage.bucket().file(photoPath);
    const [[bytes], [metadata]] = await Promise.all([file.download(), file.getMetadata()]);
    const contentType = metadata.contentType;
    if (typeof contentType !== "string" || !contentType.startsWith("image/")) {
      throw new PlantIdentificationError("provider_unavailable");
    }
    return this.client.identify(bytes, contentType);
  }
}

export class PlantIdHttpClient {
  constructor(
    private readonly apiKey: string,
    private readonly transport: PlantIdTransport,
  ) {}

  async identify(bytes: Buffer, contentType: string): Promise<unknown> {
    try {
      const response = await this.transport.post({
        apiKey: this.apiKey,
        image: `data:${contentType};base64,${bytes.toString("base64")}`,
      });
      if (response.status === 429) throw new PlantIdentificationError("rate_limited");
      if (response.status < 200 || response.status >= 300) {
        throw new PlantIdentificationError("provider_unavailable");
      }
      return response.body;
    } catch (error: unknown) {
      if (error instanceof TimeoutError) throw new PlantIdentificationError("timeout");
      throw error;
    }
  }
}

export type PlantIdTransportRequest = Readonly<{
  apiKey: string;
  image: string;
}>;

export interface PlantIdTransport {
  post(request: PlantIdTransportRequest): Promise<Readonly<{ status: number; body: unknown }>>;
}

export class KyPlantIdTransport implements PlantIdTransport {
  constructor(
    private readonly endpoint = "https://plant.id/api/v3/identification",
    private readonly timeoutMilliseconds = 10_000,
  ) {}

  async post(request: PlantIdTransportRequest): Promise<Readonly<{ status: number; body: unknown }>> {
    try {
      const response = await ky.post(this.endpoint, {
        headers: { "Api-Key": request.apiKey },
        json: {
          images: [request.image],
          language: "ko",
          details: ["common_names", "taxonomy"],
        },
        retry: 0,
        timeout: this.timeoutMilliseconds,
        throwHttpErrors: false,
      });
      const body: unknown = await response.json();
      return { status: response.status, body };
    } catch (error: unknown) {
      if (error instanceof TimeoutError) throw error;
      throw new PlantIdentificationError("provider_unavailable");
    }
  }
}

export function productionPlantIdHttpClient(apiKey: string): PlantIdHttpClient {
  return new PlantIdHttpClient(apiKey, new KyPlantIdTransport());
}
