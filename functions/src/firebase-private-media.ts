import type { Storage } from "firebase-admin/storage";
import {
  PRIVATE_MEDIA_SEAL_CONTENT_TYPE,
  PrivateMediaError,
  validObjectGeneration,
  type CreateSealResult,
  type DeleteGenerationResult,
  type PrivateMediaObject,
  type PrivateMediaObjectStore,
  type PrivateMediaSignCommand,
  type PrivateMediaSigner,
} from "./private-media-contract.js";

export class FirebasePrivateMediaSigner implements PrivateMediaSigner {
  constructor(private readonly storage: Storage) {}

  async signPut(command: PrivateMediaSignCommand): Promise<Readonly<{ url: string }>> {
    try {
      const extensionHeaders = Object.fromEntries(
        Object.entries(command.requiredHeaders).filter(([name]) => name !== "content-type"),
      );
      const [url] = await this.storage.bucket().file(command.objectPath).getSignedUrl({
        version: "v4",
        action: "write",
        expires: command.expiresAtMillis,
        contentType: command.contentType,
        extensionHeaders,
      });
      return { url };
    } catch (error: unknown) {
      if (error instanceof Error) {
        throw new PrivateMediaError("unavailable", "Private media upload signing failed", {
          cause: error,
        });
      }
      throw error;
    }
  }
}

export class FirebasePrivateMediaObjectStore implements PrivateMediaObjectStore {
  constructor(private readonly storage: Storage) {}

  async inspect(path: string): Promise<PrivateMediaObject | null> {
    try {
      const [metadata] = await this.storage.bucket().file(path).getMetadata();
      const generation = validObjectGeneration(metadata.generation);
      const byteSize = numericSize(metadata.size);
      const contentType = metadata.contentType;
      if (generation === null || byteSize === null || typeof contentType !== "string") {
        throw new PrivateMediaError("failed-precondition", "Private media object metadata is malformed");
      }
      return {
        path,
        generation,
        byteSize,
        contentType,
        customMetadata: stringMetadata(metadata.metadata),
      };
    } catch (error: unknown) {
      if (apiCode(error) === 404) return null;
      throw error;
    }
  }

  async deleteGeneration(path: string, generation: string): Promise<DeleteGenerationResult> {
    try {
      await this.storage.bucket().file(path, {
        generation,
        preconditionOpts: { ifGenerationMatch: generation },
      }).delete();
      return "deleted";
    } catch (error: unknown) {
      const code = apiCode(error);
      if (code === 404) return "absent";
      if (code === 412) return "generation_changed";
      throw error;
    }
  }

  async createSeal(path: string): Promise<CreateSealResult> {
    try {
      const file = this.storage.bucket().file(path, {
        generation: 0,
        preconditionOpts: { ifGenerationMatch: 0 },
      });
      await file.save(Buffer.alloc(0), {
        resumable: false,
        validation: false,
        preconditionOpts: { ifGenerationMatch: 0 },
        metadata: {
          cacheControl: "private, no-store",
          contentType: PRIVATE_MEDIA_SEAL_CONTENT_TYPE,
          metadata: { privateMediaSeal: "true" },
        },
      });
      const seal = await this.inspect(path);
      if (seal === null) {
        throw new PrivateMediaError("unavailable", "Private media seal disappeared after creation");
      }
      return { kind: "created", generation: seal.generation };
    } catch (error: unknown) {
      if (apiCode(error) === 412) return { kind: "occupied" };
      throw error;
    }
  }
}

function numericSize(value: unknown): number | null {
  const size = typeof value === "string" ? Number(value) : value;
  return typeof size === "number" && Number.isSafeInteger(size) && size >= 0 ? size : null;
}

function stringMetadata(value: unknown): Readonly<Record<string, string>> {
  if (value === undefined || value === null) return {};
  if (typeof value !== "object" || Array.isArray(value)) {
    throw new PrivateMediaError("failed-precondition", "Private media custom metadata is malformed");
  }
  const entries = Object.entries(value);
  if (!entries.every((entry): entry is [string, string] => typeof entry[1] === "string")) {
    throw new PrivateMediaError("failed-precondition", "Private media custom metadata is malformed");
  }
  return Object.fromEntries(entries);
}

function apiCode(error: unknown): number | null {
  if (!(error instanceof Error) || !("code" in error)) return null;
  const code = error.code;
  if (typeof code === "number") return code;
  if (typeof code === "string" && /^\d+$/.test(code)) return Number(code);
  return null;
}
