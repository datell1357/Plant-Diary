import { createHash } from "node:crypto"
import { z } from "zod"

export const PLANT_IDENTIFICATION_CONTRACT_VERSION = 2 as const

const maximumImageBytes = 4 * 1024 * 1024
const maximumEncodedImageLength = Math.ceil(maximumImageBytes / 3) * 4
const opaqueID = /^[A-Za-z0-9_-]{8,128}$/
const publicContentID = /^[A-Za-z0-9_-]{1,128}$/
const canonicalBase64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/

const ImageBase64Schema = z
  .string()
  .min(4)
  .max(maximumEncodedImageLength)
  .regex(canonicalBase64)
  .refine((value) => Buffer.from(value, "base64").byteLength <= maximumImageBytes)

export const PlantIdentificationProxyRequestSchema = z
  .object({
    requestID: z.string().regex(opaqueID),
    idempotencyKey: z.string().regex(opaqueID),
    imagesBase64: z.array(ImageBase64Schema).min(1).max(5).readonly(),
  })
  .strict()
  .readonly()
export type PlantIdentificationProxyRequest = z.infer<typeof PlantIdentificationProxyRequestSchema>

const ProviderSuggestionSchema = z
  .object({
    id: z.string().regex(publicContentID),
    name: z.string().trim().min(1).max(200),
    probability: z.number().finite().min(0).max(1),
    details: z
      .object({
        common_names: z.array(z.string().trim().min(1).max(200)).max(100).optional(),
      })
      .passthrough()
      .optional(),
    similar_images: z
      .array(
        z
          .object({ url: z.string().url().max(2048) })
          .passthrough()
          .readonly(),
      )
      .min(1)
      .max(100),
  })
  .passthrough()
  .readonly()

const PlantIdResponseSchema = z
  .object({
    result: z
      .object({
        classification: z
          .object({
            suggestions: z.array(ProviderSuggestionSchema).max(100),
          })
          .passthrough(),
      })
      .passthrough(),
  })
  .passthrough()

const PlantIdentificationCandidateSchema = z
  .object({
    publicContentId: z.string().regex(publicContentID),
    koreanName: z.string().trim().min(1).max(200),
    commonName: z.string().trim().min(1).max(200),
    scientificName: z.string().trim().min(1).max(200),
    confidence: z.number().finite().min(0).max(1),
    thumbnailUrl: z.string().url().max(2048),
  })
  .strict()
  .readonly()

export type PlantIdentificationCandidate = Readonly<{
  publicContentId: string
  koreanName: string
  commonName: string
  scientificName: string
  confidence: number
  thumbnailUrl: string
}>

export type PlantIdentificationFailureReason =
  | "timeout"
  | "rate_limited"
  | "provider_unavailable"
  | "malformed_response"

export type PlantIdentificationResponse =
  | Readonly<{ kind: "candidates"; candidates: readonly PlantIdentificationCandidate[] }>
  | Readonly<{ kind: "no_candidates" }>
  | Readonly<{ kind: "failed"; reason: PlantIdentificationFailureReason }>

export const PlantIdentificationResponseSchema = z
  .discriminatedUnion("kind", [
    z
      .object({
        kind: z.literal("candidates"),
        candidates: z.array(PlantIdentificationCandidateSchema).min(1).max(3).readonly(),
      })
      .strict()
      .readonly(),
    z
      .object({ kind: z.literal("no_candidates") })
      .strict()
      .readonly(),
    z
      .object({
        kind: z.literal("failed"),
        reason: z.enum(["timeout", "rate_limited", "provider_unavailable", "malformed_response"]),
      })
      .strict()
      .readonly(),
  ])
  .readonly()

export type PlantIdentificationErrorCode =
  | "unauthenticated"
  | "invalid-argument"
  | "permission-denied"
  | "conflict"
  | "timeout"
  | "rate-limited"
  | "provider-unavailable"

export class PlantIdentificationProxyError extends Error {
  override readonly name = "PlantIdentificationProxyError"

  constructor(
    readonly code: PlantIdentificationErrorCode,
    message: string = code,
  ) {
    super(message)
  }
}

export interface PlantIdentificationProvider {
  identify(images: readonly Buffer[]): Promise<unknown>
}

export type PlantIdentificationOperation = Readonly<{
  ownerID: string
  requestID: string
  idempotencyKey: string
  requestHash: string
}>

export interface PlantIdentificationResultStore {
  runOnce(
    operation: PlantIdentificationOperation,
    execute: () => Promise<PlantIdentificationResponse>,
  ): Promise<PlantIdentificationResponse>
}

export type PlantIdentificationAuthenticator = (request: Request) => Promise<string | null>

export type PlantIdentificationHTTPDependencies = Readonly<{
  authenticate: PlantIdentificationAuthenticator
  store: PlantIdentificationResultStore
  provider: PlantIdentificationProvider
}>

function parseRequest(input: unknown): PlantIdentificationProxyRequest {
  try {
    return PlantIdentificationProxyRequestSchema.parse(input)
  } catch (error: unknown) {
    if (error instanceof z.ZodError) {
      throw new PlantIdentificationProxyError("invalid-argument")
    }
    throw error
  }
}

export function canonicalPlantIdentificationRequest(
  operation: Pick<PlantIdentificationOperation, "ownerID" | "requestID">,
  imagesBase64: readonly string[],
): string {
  return JSON.stringify({
    contractVersion: PLANT_IDENTIFICATION_CONTRACT_VERSION,
    owner: operation.ownerID,
    requestID: operation.requestID,
    imagesBase64,
  })
}

export function plantIdentificationRequestHash(
  operation: Pick<PlantIdentificationOperation, "ownerID" | "requestID">,
  imagesBase64: readonly string[],
): string {
  return createHash("sha256")
    .update(canonicalPlantIdentificationRequest(operation, imagesBase64), "utf8")
    .digest("hex")
}

function httpsURL(value: string): string {
  const url = new URL(value)
  if (url.protocol !== "https:" || url.username !== "" || url.password !== "" || url.hash !== "") {
    throw new PlantIdentificationProxyError("provider-unavailable")
  }
  return url.toString()
}

function normalizeProviderResponse(input: unknown): PlantIdentificationResponse {
  try {
    const response = PlantIdResponseSchema.parse(input)
    const candidates = response.result.classification.suggestions
      .map((suggestion): PlantIdentificationCandidate => {
        const commonName = suggestion.details?.common_names?.[0] ?? suggestion.name
        const thumbnail = suggestion.similar_images[0]
        if (thumbnail === undefined) {
          throw new PlantIdentificationProxyError("provider-unavailable")
        }
        return {
          publicContentId: suggestion.id,
          koreanName: commonName,
          commonName,
          scientificName: suggestion.name,
          confidence: suggestion.probability,
          thumbnailUrl: httpsURL(thumbnail.url),
        }
      })
      .sort((left, right) => right.confidence - left.confidence)
      .slice(0, 3)
    return PlantIdentificationResponseSchema.parse(
      candidates.length === 0 ? { kind: "no_candidates" } : { kind: "candidates", candidates },
    )
  } catch (error: unknown) {
    if (error instanceof z.ZodError || error instanceof PlantIdentificationProxyError) {
      return { kind: "failed", reason: "malformed_response" }
    }
    throw error
  }
}

function providerFailure(error: PlantIdentificationProxyError): PlantIdentificationResponse {
  switch (error.code) {
    case "timeout":
      return { kind: "failed", reason: "timeout" }
    case "rate-limited":
      return { kind: "failed", reason: "rate_limited" }
    case "provider-unavailable":
      return { kind: "failed", reason: "provider_unavailable" }
    case "unauthenticated":
    case "invalid-argument":
    case "permission-denied":
    case "conflict":
      throw error
  }
}

export async function executePlantIdentification(
  subjectID: string | null,
  input: unknown,
  store: PlantIdentificationResultStore,
  provider: PlantIdentificationProvider,
): Promise<PlantIdentificationResponse> {
  if (subjectID === null || subjectID.trim() === "") {
    throw new PlantIdentificationProxyError("unauthenticated")
  }
  const request = parseRequest(input)
  const images = request.imagesBase64.map((image) => Buffer.from(image, "base64"))
  const operation = {
    ownerID: subjectID,
    requestID: request.requestID,
    idempotencyKey: request.idempotencyKey,
    requestHash: plantIdentificationRequestHash(
      { ownerID: subjectID, requestID: request.requestID },
      request.imagesBase64,
    ),
  } satisfies PlantIdentificationOperation
  return store.runOnce(operation, async () => {
    try {
      return normalizeProviderResponse(await provider.identify(images))
    } catch (error: unknown) {
      if (error instanceof PlantIdentificationProxyError) return providerFailure(error)
      throw error
    }
  })
}

function errorStatus(error: PlantIdentificationProxyError): number {
  switch (error.code) {
    case "unauthenticated":
      return 401
    case "permission-denied":
      return 403
    case "invalid-argument":
      return 400
    case "conflict":
      return 409
    case "rate-limited":
      return 429
    case "timeout":
      return 504
    case "provider-unavailable":
      return 503
  }
}

function jsonResponse(value: PlantIdentificationResponse): Response {
  return Response.json(value, {
    status: 200,
    headers: { "cache-control": "no-store" },
  })
}

export function createPlantIdentificationHTTPHandler(
  dependencies: PlantIdentificationHTTPDependencies,
): (request: Request) => Promise<Response> {
  return async (request) => {
    if (request.method !== "POST") {
      return new Response(null, { status: 405, headers: { allow: "POST" } })
    }
    if (!request.headers.get("content-type")?.toLowerCase().startsWith("application/json")) {
      return new Response(null, { status: 415 })
    }
    try {
      const subjectID = await dependencies.authenticate(request)
      if (subjectID === null) throw new PlantIdentificationProxyError("unauthenticated")
      const input: unknown = await request.json()
      return jsonResponse(
        await executePlantIdentification(
          subjectID,
          input,
          dependencies.store,
          dependencies.provider,
        ),
      )
    } catch (error: unknown) {
      if (error instanceof PlantIdentificationProxyError) {
        return new Response(null, { status: errorStatus(error) })
      }
      if (error instanceof SyntaxError) return new Response(null, { status: 400 })
      return new Response(null, { status: 500 })
    }
  }
}
