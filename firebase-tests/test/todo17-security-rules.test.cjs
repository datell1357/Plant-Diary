const fs = require("node:fs");
const path = require("node:path");
const {
  assertFails,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const { doc, getDoc, setDoc } = require("firebase/firestore");
const { after, before, beforeEach, describe, it } = require("mocha");

const projectId = "demo-planterior";
let env;

const endpoint = {
  endpointId: "endpoint-security-gate-0001",
  token: "not-a-real-registration-token",
};

describe("Todo17 notification token Firestore boundary", () => {
  before(async () => {
    env = await initializeTestEnvironment({
      projectId,
      firestore: {
        rules: fs.readFileSync(
          path.resolve(__dirname, "../../firestore.rules"),
          "utf8",
        ),
      },
    });
  });

  beforeEach(async () => {
    await env.clearFirestore();
  });

  after(async () => {
    if (env) await env.cleanup();
  });

  it("denies endpoint and token-owner records to anonymous, owner, foreign, and server-claim clients", async () => {
    const contexts = [
      env.unauthenticatedContext().firestore(),
      env.authenticatedContext("user-a").firestore(),
      env.authenticatedContext("user-b").firestore(),
      env.authenticatedContext("service", { server: true }).firestore(),
    ];
    const endpointPath = "users/user-a/notificationEndpoints/endpoint-security-gate-0001";
    const ownerPath = "notificationEndpointOwners/endpoint-security-gate-0001";

    await env.withSecurityRulesDisabled(async (admin) => {
      await setDoc(doc(admin.firestore(), endpointPath), {
        ownerUid: "user-a",
        ...endpoint,
      });
      await setDoc(doc(admin.firestore(), ownerPath), {
        ownerUid: "user-a",
        endpointId: endpoint.endpointId,
      });
    });

    for (const firestore of contexts) {
      await assertFails(getDoc(doc(firestore, endpointPath)));
      await assertFails(setDoc(doc(firestore, endpointPath), {
        ownerUid: "user-a",
        ...endpoint,
      }));
      await assertFails(getDoc(doc(firestore, ownerPath)));
      await assertFails(setDoc(doc(firestore, ownerPath), {
        ownerUid: "user-a",
        endpointId: endpoint.endpointId,
      }));
    }
  });
});
