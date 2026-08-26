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
import {
  FirestoreIdentificationAuthorizationRepository,
} from "./firestore-identification-authorization.js";
import { FirestorePrivateMediaReservationRepository } from "./firestore-private-media.js";
import {
  IdentificationAuthorizationError,
  type IdentificationAuthorizationRepository,
} from "./identification-authorization-contract.js";
import type { PrivateMediaReservationRepository, ResolvedPrivateMedia } from "./private-media-contract.js";

export type IdentificationRuntimeErrorReason = "not_found" | "permission_denied" | "malformed_state" | "failed_precondition";

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
    case "pending": return { kind: "pending" };
    case "no_candidates": return { kind: "no_candidates" };
    case "candidates": {
      if (!Array.isArray(parsed.candidates) || parsed.candidates.length < 1 || parsed.candidates.length > 3) {
        throw new IdentificationRuntimeError("malformed_state");
      }
      return { kind: "candidates", candidates: parsed.candidates.map(storedCandidate) };
    }
    case "failed":
      if (["timeout", "rate_limited", "provider_unavailable", "malformed_response"].includes(String(parsed.reason))) {
        return { kind: "failed", reason: parsed.reason as "timeout" | "rate_limited" | "provider_unavailable" | "malformed_response" };
      }
      throw new IdentificationRuntimeError("malformed_state");
    default: throw new IdentificationRuntimeError("malformed_state");
  }
}

function responseStatus(response: IdentificationResponse) {
  switch (response.kind) {
    case "candidates": return "CANDIDATES" as const;
    case "no_candidates": return "NO_CANDIDATES" as const;
    case "failed": return "FAILED" as const;
    case "pending": return "PENDING" as const;
  }
}

/**
 * Executes only a current provider claim.  The durable SENDING transition is deliberately made
 * before the operation receives media: after that boundary an interrupted/ambiguous provider call
 * remains non-retriable, rather than risking a second transmission of the original.
 */
export class ClaimFencedIdentificationRequestStore implements IdentificationRequestStore {
  constructor(
    private readonly requests: IdentificationAuthorizationRepository,
    private readonly media: PrivateMediaReservationRepository,
    private readonly nowMillis: () => number,
  ) {}

  async runOnce(
    ownerUid: string,
    requestId: string,
    idempotencyKey: string,
    operation: (request: StoredIdentificationRequest) => Promise<IdentificationResponse>,
  ): Promise<IdentificationResponse> {
    let claim;
    try {
      claim = await this.requests.claim({ ownerUid, requestId, operationKey: idempotencyKey, nowMillis: this.nowMillis() });
    } catch (error: unknown) {
      throw runtimeError(error);
    }
    if (claim.kind === "replay") return storedResponse(claim.result);
    if (claim.kind === "in_flight") return { kind: "pending" };

    const resolved = await this.media.resolve({
      ownerUid,
      reference: claim.request.mediaReference,
      mediaKind: "IDENTIFICATION_ORIGINAL",
    });
    if (resolved === null) throw new IdentificationRuntimeError("malformed_state");

    try {
      // A cancellation or lease takeover between resolve and this transition is rejected here,
      // before the operation (and therefore before bytes are loaded) can run.
      await this.requests.markSending({
        ownerUid,
        requestId,
        operationKey: idempotencyKey,
        claimGeneration: claim.request.claimGeneration,
        nowMillis: this.nowMillis(),
      });
    } catch (error: unknown) {
      throw runtimeError(error);
    }

    const response = await operation({ ownerUid, media: resolved });
    try {
      await this.requests.finalize({
        ownerUid,
        requestId,
        operationKey: idempotencyKey,
        claimGeneration: claim.request.claimGeneration,
        status: responseStatus(response),
        result: response,
        nowMillis: this.nowMillis(),
      });
    } catch (error: unknown) {
      throw runtimeError(error);
    }
    return response;
  }
}

function runtimeError(error: unknown): Error {
  if (!(error instanceof IdentificationAuthorizationError)) return error instanceof Error ? error : new Error(String(error));
  switch (error.code) {
    case "not-found": return new IdentificationRuntimeError("not_found");
    case "permission-denied": return new IdentificationRuntimeError("permission_denied");
    case "unauthenticated": return new PlantIdentificationError("unauthenticated");
    case "invalid-argument": return new PlantIdentificationError("invalid_argument");
    case "failed-precondition": return new IdentificationRuntimeError("failed_precondition");
  }
}

export class FirestoreIdentificationRequestStore implements IdentificationRequestStore {
  private readonly delegate: ClaimFencedIdentificationRequestStore;

  constructor(firestore: Firestore = getFirestore(), now: () => Date = () => new Date()) {
    this.delegate = new ClaimFencedIdentificationRequestStore(
      new FirestoreIdentificationAuthorizationRepository(firestore),
      new FirestorePrivateMediaReservationRepository(firestore),
      () => now().getTime(),
    );
  }

  runOnce(
    ownerUid: string,
    requestId: string,
    idempotencyKey: string,
    operation: (request: StoredIdentificationRequest) => Promise<IdentificationResponse>,
  ): Promise<IdentificationResponse> {
    return this.delegate.runOnce(ownerUid, requestId, idempotencyKey, operation);
  }
}

export class PlantIdStorageProvider implements PlantIdProvider {
  constructor(private readonly client: PlantIdHttpClient, private readonly storage: Storage = getStorage()) {}

  async identify(media: ResolvedPrivateMedia): Promise<unknown> {
    const file = this.storage.bucket().file(media.objectPath, { generation: media.reference.generation });
    const [[bytes], [metadata]] = await Promise.all([file.download(), file.getMetadata()]);
    const size = typeof metadata.size === "string" ? Number(metadata.size) : metadata.size;
    if (
      metadata.contentType !== media.contentType || size !== media.byteSize
      || metadata.metadata?.ownerUid !== media.ownerUid
      || metadata.metadata?.reservationId !== media.reference.reservationId
      || Object.keys(metadata.metadata ?? {}).length !== 2
    ) throw new PlantIdentificationError("provider_unavailable");
    return this.client.identify(bytes, media.contentType);
  }
}

export class PlantIdHttpClient {
  constructor(private readonly apiKey: string, private readonly transport: PlantIdTransport) {}

  async identify(bytes: Buffer, contentType: string): Promise<unknown> {
    try {
      const response = await this.transport.post({ apiKey: this.apiKey, image: `data:${contentType};base64,${bytes.toString("base64")}` });
      if (response.status === 429) throw new PlantIdentificationError("rate_limited");
      if (response.status < 200 || response.status >= 300) throw new PlantIdentificationError("provider_unavailable");
      return response.body;
    } catch (error: unknown) {
      if (error instanceof TimeoutError) throw new PlantIdentificationError("timeout");
      throw error;
    }
  }
}

export type PlantIdTransportRequest = Readonly<{ apiKey: string; image: string }>;
export interface PlantIdTransport { post(request: PlantIdTransportRequest): Promise<Readonly<{ status: number; body: unknown }>>; }

export class KyPlantIdTransport implements PlantIdTransport {
  constructor(private readonly endpoint = "https://plant.id/api/v3/identification", private readonly timeoutMilliseconds = 10_000) {}

  async post(request: PlantIdTransportRequest): Promise<Readonly<{ status: number; body: unknown }>> {
    try {
      const response = await ky.post(this.endpoint, {
        headers: { "Api-Key": request.apiKey },
        json: { images: [request.image], language: "ko", details: ["common_names", "taxonomy"] },
        retry: 0, timeout: this.timeoutMilliseconds, throwHttpErrors: false,
      });
      return { status: response.status, body: await response.json() };
    } catch (error: unknown) {
      if (error instanceof TimeoutError) throw error;
      throw new PlantIdentificationError("provider_unavailable");
    }
  }
}

export function productionPlantIdHttpClient(apiKey: string): PlantIdHttpClient {
  return new PlantIdHttpClient(apiKey, new KyPlantIdTransport());
}
