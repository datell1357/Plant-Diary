import ky, { TimeoutError } from "ky"
import {
  type PlantIdentificationProvider,
  PlantIdentificationProxyError,
} from "./plant-identification-proxy.js"

export type PlantIdTransportRequest = Readonly<{
  apiKey: string
  image: string
  language: "ko"
  details: readonly ["common_names"]
  similarImages: true
  classificationLevel: "species"
}>

export interface PlantIdTransport {
  post(request: PlantIdTransportRequest): Promise<Readonly<{ status: number; body: unknown }>>
}

export class PlantIdTransportTimeoutError extends Error {
  override readonly name = "PlantIdTransportTimeoutError"
}

function mediaType(image: Buffer): string {
  if (image.length >= 3 && image[0] === 0xff && image[1] === 0xd8 && image[2] === 0xff) {
    return "image/jpeg"
  }
  if (
    image.length >= 8 &&
    image.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))
  ) {
    return "image/png"
  }
  if (
    image.length >= 12 &&
    image.subarray(0, 4).toString("ascii") === "RIFF" &&
    image.subarray(8, 12).toString("ascii") === "WEBP"
  ) {
    return "image/webp"
  }
  if (image.length >= 12 && image.subarray(4, 8).toString("ascii") === "ftyp") {
    return "image/heic"
  }
  throw new PlantIdentificationProxyError("invalid-argument")
}

export class PlantIdHttpClient implements PlantIdentificationProvider {
  constructor(
    private readonly apiKey: string,
    private readonly transport: PlantIdTransport,
  ) {}

  async identify(image: Buffer): Promise<unknown> {
    if (this.apiKey.trim() === "") {
      throw new PlantIdentificationProxyError("provider-unavailable")
    }
    const request: PlantIdTransportRequest = {
      apiKey: this.apiKey,
      image: `data:${mediaType(image)};base64,${image.toString("base64")}`,
      language: "ko",
      details: ["common_names"],
      similarImages: true,
      classificationLevel: "species",
    }
    try {
      const response = await this.transport.post(request)
      if (response.status === 408 || response.status === 504) {
        throw new PlantIdentificationProxyError("timeout")
      }
      if (response.status === 429) {
        throw new PlantIdentificationProxyError("rate-limited")
      }
      if (response.status < 200 || response.status >= 300) {
        throw new PlantIdentificationProxyError("provider-unavailable")
      }
      return response.body
    } catch (error: unknown) {
      if (error instanceof PlantIdTransportTimeoutError) {
        throw new PlantIdentificationProxyError("timeout")
      }
      throw error
    }
  }
}

export class KyPlantIdTransport implements PlantIdTransport {
  constructor(
    private readonly endpoint = "https://plant.id/api/v3/identification",
    private readonly timeoutMilliseconds = 10_000,
  ) {}

  async post(
    request: PlantIdTransportRequest,
  ): Promise<Readonly<{ status: number; body: unknown }>> {
    try {
      const response = await ky.post(this.endpoint, {
        headers: { "Api-Key": request.apiKey },
        searchParams: {
          details: request.details.join(","),
          language: request.language,
        },
        json: {
          images: [request.image],
          similar_images: request.similarImages,
          classification_level: request.classificationLevel,
        },
        retry: 0,
        timeout: this.timeoutMilliseconds,
        throwHttpErrors: false,
      })
      return { status: response.status, body: await response.json() }
    } catch (error: unknown) {
      if (error instanceof TimeoutError) throw new PlantIdTransportTimeoutError()
      if (error instanceof PlantIdentificationProxyError) throw error
      throw new PlantIdentificationProxyError("provider-unavailable")
    }
  }
}

export function productionPlantIdClient(apiKey: string): PlantIdHttpClient {
  return new PlantIdHttpClient(apiKey, new KyPlantIdTransport())
}
