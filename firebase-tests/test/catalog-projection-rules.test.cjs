const fs = require("node:fs");
const path = require("node:path");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const {
  Timestamp,
  deleteDoc,
  doc,
  getDoc,
  setDoc,
  updateDoc,
} = require("firebase/firestore");
const { getBytes, ref, uploadBytes } = require("firebase/storage");
const { after, before, beforeEach, describe, it } = require("mocha");

const projectId = "demo-planterior";
const digestA = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";
const digestB = "b".repeat(64);
const ts = (value) => Timestamp.fromDate(new Date(value));
const assetPath = (itemId, digest = digestA) => `catalog-assets/${itemId}/${digest}.webp`;
const item = (
  itemId,
  {
    digest = digestA,
    publicationState = "PUBLIC",
    acquisitionCondition = null,
    revision = 1,
    mediaRevision = 1,
  } = {},
) => ({
  name: "햇살 벽지",
  description: "방을 환하게 꾸며요.",
  category: "BACKGROUND",
  assetPath: assetPath(itemId, digest),
  assetSha256: digest,
  assetContentType: "image/webp",
  assetByteSize: 3,
  assetWidth: 96,
  assetHeight: 64,
  assetMediaRevision: mediaRevision,
  acquisitionCondition,
  publicationState,
  revision,
  updatedAt: ts(`2026-08-12T00:00:0${Math.min(revision - 1, 9)}Z`),
});

let env;

async function seedFirestore(documentPath, data) {
  await env.withSecurityRulesDisabled(async (admin) => {
    await setDoc(doc(admin.firestore(), documentPath), data);
  });
}

async function seedCatalogObject(itemId, digest = digestA, metadata = {}) {
  await env.withSecurityRulesDisabled(async (admin) => {
    await uploadBytes(
      ref(admin.storage(), assetPath(itemId, digest)),
      new Uint8Array([1, 2, 3]),
      {
        contentType: "image/webp",
        customMetadata: {
          width: "96",
          height: "64",
          sha256: digest,
          mediaRevision: "1",
          ...metadata,
        },
      },
    );
  });
}

describe("Todo 14 catalog and immutable projection Rules regressions", () => {
  before(async () => {
    env = await initializeTestEnvironment({
      projectId,
      firestore: {
        rules: fs.readFileSync(path.resolve(__dirname, "../../firestore.rules"), "utf8"),
      },
      storage: {
        rules: fs.readFileSync(path.resolve(__dirname, "../../storage.rules"), "utf8"),
      },
    });
  });
  beforeEach(async () => {
    await env.clearFirestore();
    await env.clearStorage();
  });
  after(async () => {
    if (env) await env.cleanup();
  });

  it("denies normal-user shopItems create update and delete", async () => {
    const user = env.authenticatedContext("user-a").firestore();
    await seedFirestore("shopItems/existing", item("existing"));
    await assertFails(setDoc(doc(user, "shopItems/new-item"), item("new-item")));
    await assertFails(updateDoc(doc(user, "shopItems/existing"), {
      description: "forged",
      revision: 2,
    }));
    await assertFails(deleteDoc(doc(user, "shopItems/existing")));
  });

  it("denies unauthenticated shopItems create update and delete", async () => {
    const anonymous = env.unauthenticatedContext().firestore();
    await seedFirestore("shopItems/existing", item("existing"));
    await assertFails(setDoc(doc(anonymous, "shopItems/new-item"), item("new-item")));
    await assertFails(updateDoc(doc(anonymous, "shopItems/existing"), {
      description: "forged",
      revision: 2,
    }));
    await assertFails(deleteDoc(doc(anonymous, "shopItems/existing")));
  });

  it("allows the exact contentAdmin custom claim to create update and delete a valid item", async () => {
    const contentAdmin = env.authenticatedContext("content-admin", {
      contentAdmin: true,
    }).firestore();
    const reference = doc(contentAdmin, "shopItems/managed");
    await assertSucceeds(setDoc(reference, item("managed")));
    await assertSucceeds(setDoc(reference, {
      ...item("managed", { revision: 2 }),
      description: "검수된 설명",
    }));
    await assertSucceeds(deleteDoc(reference));
  });

  it("rejects false string and lookalike content-admin claims", async () => {
    const contexts = [
      env.authenticatedContext("false-admin", { contentAdmin: false }).firestore(),
      env.authenticatedContext("string-admin", { contentAdmin: "true" }).firestore(),
      env.authenticatedContext("lookalike-admin", { content_admin: true }).firestore(),
    ];
    for (const firestore of contexts) {
      await assertFails(setDoc(doc(firestore, "shopItems/forged"), item("forged")));
    }
  });

  it("rejects malformed digest-bound catalog media identity", async () => {
    const contentAdmin = env.authenticatedContext("content-admin", {
      contentAdmin: true,
    }).firestore();
    await assertFails(setDoc(doc(contentAdmin, "shopItems/path-mismatch"), {
      ...item("path-mismatch"),
      assetPath: assetPath("path-mismatch", digestB),
    }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/uppercase-digest"), {
      ...item("uppercase-digest"),
      assetPath: assetPath("uppercase-digest", digestA.toUpperCase()),
      assetSha256: digestA.toUpperCase(),
    }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/type-mismatch"), {
      ...item("type-mismatch"),
      assetContentType: "image/png",
    }));
  });

  it("rejects invalid and missing catalog visibility", async () => {
    const contentAdmin = env.authenticatedContext("content-admin", {
      contentAdmin: true,
    }).firestore();
    await assertFails(setDoc(doc(contentAdmin, "shopItems/unreviewed"), {
      ...item("unreviewed"),
      publicationState: "UNREVIEWED",
    }));
    const { publicationState: _omitted, ...missingVisibility } = item("missing-visibility");
    await assertFails(setDoc(doc(contentAdmin, "shopItems/missing-visibility"), missingVisibility));
  });

  it("rejects catalog create revision drift and update revision regression or jumps", async () => {
    const contentAdmin = env.authenticatedContext("content-admin", {
      contentAdmin: true,
    }).firestore();
    await assertFails(setDoc(doc(contentAdmin, "shopItems/create-revision-two"),
      item("create-revision-two", { revision: 2 })));
    await seedFirestore("shopItems/revisioned", item("revisioned", { revision: 2 }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/revisioned"),
      item("revisioned", { revision: 2 })));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/revisioned"),
      item("revisioned", { revision: 4 })));
  });

  it("rejects owner and monetization fields outside the closed shopItems schema", async () => {
    const contentAdmin = env.authenticatedContext("content-admin", {
      contentAdmin: true,
    }).firestore();
    await assertFails(setDoc(doc(contentAdmin, "shopItems/owner-field"), {
      ...item("owner-field"),
      ownerUid: "content-admin",
    }));
    await assertFails(setDoc(doc(contentAdmin, "shopItems/monetized"), {
      ...item("monetized"),
      price: 100,
      currency: "POINTS",
    }));
  });

  it("keeps catalog projection pointers unreadable and unwritable by every client role", async () => {
    const projectionPath = "catalogProjectionPointers/current";
    await seedFirestore(projectionPath, { projectionId: `1-${digestA}` });
    const clients = [
      env.unauthenticatedContext().firestore(),
      env.authenticatedContext("user-a").firestore(),
      env.authenticatedContext("content-admin", { contentAdmin: true }).firestore(),
      env.authenticatedContext("ops-admin", { opsAdmin: true }).firestore(),
    ];
    for (const firestore of clients) {
      await assertFails(getDoc(doc(firestore, projectionPath)));
      await assertFails(setDoc(doc(firestore, projectionPath), { projectionId: `2-${digestB}` }));
    }
  });

  it("keeps immutable catalog projection generations unreadable and unwritable by every client role", async () => {
    const projectionPath = `catalogProjections/1-${digestA}`;
    await seedFirestore(projectionPath, { projectionId: `1-${digestA}`, catalogToken: digestA });
    const clients = [
      env.unauthenticatedContext().firestore(),
      env.authenticatedContext("user-a").firestore(),
      env.authenticatedContext("content-admin", { contentAdmin: true }).firestore(),
      env.authenticatedContext("ops-admin", { opsAdmin: true }).firestore(),
    ];
    for (const firestore of clients) {
      await assertFails(getDoc(doc(firestore, projectionPath)));
      await assertFails(setDoc(doc(firestore, projectionPath), { catalogToken: digestB }));
    }
  });

  it("prevents owner forgery and cross-owner access to Mini-home projection pointers", async () => {
    const pointerPath = "users/user-a/miniHomeProjectionPointers/current";
    await seedFirestore(pointerPath, { ownerUid: "user-a", projectionId: `1-${digestA}` });
    const owner = env.authenticatedContext("user-a").firestore();
    const foreign = env.authenticatedContext("user-b").firestore();
    const contentAdmin = env.authenticatedContext("content-admin", {
      contentAdmin: true,
    }).firestore();
    await assertFails(getDoc(doc(owner, pointerPath)));
    await assertFails(setDoc(doc(owner, pointerPath), {
      ownerUid: "user-a",
      projectionId: `2-${digestB}`,
    }));
    await assertFails(getDoc(doc(foreign, pointerPath)));
    await assertFails(setDoc(doc(foreign, pointerPath), {
      ownerUid: "user-b",
      projectionId: `2-${digestB}`,
    }));
    await assertFails(getDoc(doc(contentAdmin, pointerPath)));
  });

  it("prevents owner forgery and cross-owner access to immutable Mini-home projections", async () => {
    const projectionPath = `users/user-a/miniHomeProjections/1-${digestA}`;
    await seedFirestore(projectionPath, { ownerUid: "user-a", snapshotToken: digestA });
    const owner = env.authenticatedContext("user-a").firestore();
    const foreign = env.authenticatedContext("user-b").firestore();
    const contentAdmin = env.authenticatedContext("content-admin", {
      contentAdmin: true,
    }).firestore();
    await assertFails(getDoc(doc(owner, projectionPath)));
    await assertFails(setDoc(doc(owner, projectionPath), {
      ownerUid: "user-a",
      snapshotToken: digestB,
    }));
    await assertFails(getDoc(doc(foreign, projectionPath)));
    await assertFails(setDoc(doc(foreign, projectionPath), {
      ownerUid: "user-b",
      snapshotToken: digestB,
    }));
    await assertFails(getDoc(doc(contentAdmin, projectionPath)));
  });

  it("allows authenticated and anonymous reads of an exact public digest-bound catalog asset", async () => {
    await seedFirestore("shopItems/public", item("public"));
    await seedCatalogObject("public");
    const authenticated = env.authenticatedContext("user-a").storage();
    const anonymous = env.unauthenticatedContext().storage();
    await assertSucceeds(getBytes(ref(authenticated, assetPath("public"))));
    await assertSucceeds(getBytes(ref(anonymous, assetPath("public"))));
    await assertFails(uploadBytes(
      ref(authenticated, assetPath("public")),
      new Uint8Array([1, 2, 3]),
      { contentType: "image/webp" },
    ));
  });

  it("denies old path-only and non-content-addressed catalog assets", async () => {
    const oldPath = "catalog-assets/legacy/preview.webp";
    await seedFirestore("shopItems/legacy", {
      ...item("legacy"),
      assetPath: oldPath,
      assetSha256: null,
    });
    await env.withSecurityRulesDisabled(async (admin) => {
      await uploadBytes(
        ref(admin.storage(), oldPath),
        new Uint8Array([1, 2, 3]),
        {
          contentType: "image/webp",
          customMetadata: {
            width: "96",
            height: "64",
            sha256: digestA,
            mediaRevision: "1",
          },
        },
      );
    });
    await assertFails(getBytes(ref(env.unauthenticatedContext().storage(), oldPath)));
  });

  it("denies wrong-digest and private catalog asset reads without exact ownership", async () => {
    await seedFirestore("shopItems/wrong-digest", {
      ...item("wrong-digest"),
      assetSha256: digestB,
    });
    await seedCatalogObject("wrong-digest");
    await seedFirestore("shopItems/private", item("private", { publicationState: "PRIVATE" }));
    await seedCatalogObject("private");
    const anonymous = env.unauthenticatedContext().storage();
    const user = env.authenticatedContext("user-a").storage();
    await assertFails(getBytes(ref(anonymous, assetPath("wrong-digest"))));
    await assertFails(getBytes(ref(anonymous, assetPath("private"))));
    await assertFails(getBytes(ref(user, assetPath("private"))));
  });

  it("allows registered-plant catalog media but denies unsupported action conditions", async () => {
    await seedFirestore("shopItems/registered", item("registered", {
      acquisitionCondition: "registered-plant",
    }));
    await seedCatalogObject("registered");
    await seedFirestore("shopItems/action-ineligible", item("action-ineligible", {
      acquisitionCondition: "points-100",
    }));
    await seedCatalogObject("action-ineligible");
    const anonymous = env.unauthenticatedContext().storage();
    await assertSucceeds(getBytes(ref(anonymous, assetPath("registered"))));
    await assertFails(getBytes(ref(anonymous, assetPath("action-ineligible"))));
  });
});
