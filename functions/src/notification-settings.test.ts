import assert from "node:assert/strict";
import test from "node:test";
import {
  NotificationSettingsError,
  executeEnsureWateringNotificationSettings,
  executeRegisterNotificationEndpoint,
  executeUnregisterNotificationEndpoint,
  executeUpdateWateringNotificationSettings,
  type AccountProfileCommand,
  type NotificationEndpointCommand,
  type NotificationEndpointUnregistration,
  type NotificationSettingsStore,
  type WateringNotificationSettingsCommand,
} from "./notification-settings.js";

class FakeStore implements NotificationSettingsStore {
  endpoints: NotificationEndpointCommand[] = [];
  unregistrations: NotificationEndpointUnregistration[] = [];
  settings: WateringNotificationSettingsCommand[] = [];
  profiles: AccountProfileCommand[] = [];
  ensured: string[] = [];

  async registerEndpoint(command: NotificationEndpointCommand): Promise<void> {
    this.endpoints.push(command);
  }

  async unregisterEndpoint(command: NotificationEndpointUnregistration) {
    this.unregistrations.push(command);
    return "REVOKED" as const;
  }

  async ensureWateringSettings(ownerUid: string): Promise<void> {
    this.ensured.push(ownerUid);
  }

  async updateWateringSettings(command: WateringNotificationSettingsCommand): Promise<number> {
    this.settings.push(command);
    return command.expectedRevision + 1;
  }

  async updateAccountProfile(command: AccountProfileCommand): Promise<void> {
    this.profiles.push(command);
  }

  async reconcileWateringTimezone(): Promise<void> {}
}

test("endpoint commands require expectedOwnerUid and preserve monotonic generation and capability", async () => {
  const store = new FakeStore();
  await executeRegisterNotificationEndpoint(
    { uid: "user-a" },
    {
      expectedOwnerUid: "user-a",
      installationId: "install-12345678",
      installationSecret: "secret-1234567890abcdef",
      nextInstallationSecret: "secret-1234567890abcdef",
      generation: 7,
      token: "fcm-token-one",
      platform: "ANDROID",
      notificationsEnabled: false,
    },
    store,
  );
  await executeUnregisterNotificationEndpoint(
    { uid: "user-a" },
    { expectedOwnerUid: "user-a", installationId: "install-12345678", installationSecret: "secret-1234567890abcdef", generation: 8 },
    store,
  );

  assert.deepEqual(store.endpoints[0], {
    ownerUid: "user-a",
    installationId: "install-12345678",
    installationSecret: "secret-1234567890abcdef",
    nextInstallationSecret: "secret-1234567890abcdef",
    generation: 7,
    token: "fcm-token-one",
    platform: "ANDROID",
    notificationsEnabled: false,
  });
  assert.deepEqual(store.unregistrations[0], {
    ownerUid: "user-a",
    installationId: "install-12345678",
    installationSecret: "secret-1234567890abcdef",
    generation: 8,
  });
});

test("register unregister and settings reject spoofed expected owners", async () => {
  const store = new FakeStore();
  const permissionDenied = (error: unknown) =>
    error instanceof NotificationSettingsError && error.code === "permission-denied";

  await assert.rejects(
    () => executeRegisterNotificationEndpoint(
      { uid: "user-a" },
      { expectedOwnerUid: "user-b", installationId: "install-12345678", installationSecret: "secret-1234567890abcdef", nextInstallationSecret: "secret-1234567890abcdef", generation: 1, token: "token", platform: "ANDROID", notificationsEnabled: true },
      store,
    ),
    permissionDenied,
  );
  await assert.rejects(
    () => executeUnregisterNotificationEndpoint(
      { uid: "user-a" },
      { expectedOwnerUid: "user-b", installationId: "install-12345678", installationSecret: "secret-1234567890abcdef", generation: 2 },
      store,
    ),
    permissionDenied,
  );
  await assert.rejects(
    () => executeUpdateWateringNotificationSettings(
      { uid: "user-a" },
      { expectedOwnerUid: "user-b", expectedRevision: 1, defaultTime: "09:00", zoneId: "Asia/Seoul", globalEnabled: true, plants: [] },
      store,
    ),
    permissionDenied,
  );
});

test("canonical global defaults are explicitly ensured before Android reads settings", async () => {
  const store = new FakeStore();

  await executeEnsureWateringNotificationSettings(
    { uid: "user-a" },
    { expectedOwnerUid: "user-a" },
    store,
  );

  assert.deepEqual(store.ensured, ["user-a"]);
});

test("settings command preserves independent preferences for scheduled and unscheduled plants", async () => {
  const store = new FakeStore();
  const result = await executeUpdateWateringNotificationSettings(
    { uid: "user-a" },
    {
      expectedOwnerUid: "user-a",
      expectedRevision: 3,
      defaultTime: "09:00",
      zoneId: "Asia/Seoul",
      globalEnabled: true,
      plants: [
        { plantId: "scheduled", enabled: false, timeOverride: null },
        { plantId: "unscheduled", enabled: true, timeOverride: "08:15" },
      ],
    },
    store,
  );

  assert.deepEqual(result, { updated: true, revision: 4 });
  assert.equal(store.settings[0]?.expectedRevision, 3);
  assert.deepEqual(store.settings[0]?.plants, [
    { plantId: "scheduled", enabled: false, timeOverride: null },
    { plantId: "unscheduled", enabled: true, timeOverride: "08:15" },
  ]);
});
