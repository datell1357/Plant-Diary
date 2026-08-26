import type { Storage } from "firebase-admin/storage";
import {
  IDENTIFICATION_LEGACY_ORIGINAL_PREFIX,
  type LegacyIdentificationOriginal,
  type LegacyIdentificationOriginalStore,
} from "./identification-cleanup.js";
import { FirebasePrivateMediaObjectStore } from "./firebase-private-media.js";
import {
  PrivateMediaError,
  validObjectGeneration,
} from "./private-media-contract.js";

/** Bounded legacy-prefix adapter. Deletion is pinned to the listed generation. */
export class FirebaseLegacyIdentificationOriginalStore
  implements LegacyIdentificationOriginalStore
{
  private readonly objects: FirebasePrivateMediaObjectStore;

  constructor(private readonly storage: Storage) {
    this.objects = new FirebasePrivateMediaObjectStore(storage);
  }

  async listLegacyIdentificationOriginals(limit: number) {
    const [files] = await this.storage.bucket().getFiles({
      autoPaginate: false,
      maxResults: limit,
      prefix: IDENTIFICATION_LEGACY_ORIGINAL_PREFIX,
    });
    const items: LegacyIdentificationOriginal[] = [];
    const failures: { itemId: string; error: unknown }[] = [];
    for (const file of files) {
      if (!file.name.startsWith(IDENTIFICATION_LEGACY_ORIGINAL_PREFIX))
        continue;
      try {
        const [metadata] = await file.getMetadata();
        const generation = validObjectGeneration(metadata.generation);
        if (generation === null) {
          throw new PrivateMediaError(
            "failed-precondition",
            `Legacy identification original generation is malformed: ${file.name}`,
          );
        }
        items.push({ path: file.name, generation });
      } catch (error: unknown) {
        failures.push({ itemId: file.name, error });
      }
    }
    return { items, failures };
  }

  deleteLegacyIdentificationOriginal(
    original: LegacyIdentificationOriginal,
  ): Promise<"deleted" | "absent" | "generation_changed"> {
    return this.objects.deleteGeneration(original.path, original.generation);
  }
}
