import { z } from "zod";

export const ANALYTICS_EVENT_TTL_MILLIS = 35 * 24 * 60 * 60 * 1_000;
export const ANALYTICS_EVENT_BATCH_LIMIT = 50;

export const ANALYTICS_EVENT_NAMES = [
  "APP_SESSION_STARTED",
  "IDENTIFICATION_REQUEST_SUBMITTED",
  "IDENTIFICATION_RESULT_AVAILABLE",
  "IDENTIFICATION_FAILED",
  "IDENTIFICATION_RESULT_CONFIRMED",
  "IDENTIFICATION_RESULT_EDITED",
  "PLANT_REGISTRATION_COMPLETED",
  "CARE_INFORMATION_VIEWED",
  "WATERING_NOTIFICATION_SENT",
  "WATERING_NOTIFICATION_OPENED",
  "WATERING_COMPLETED",
  "WEATHER_RISK_ALERT_CREATED",
  "WEATHER_RISK_NOTIFICATION_SENT",
  "WEATHER_RISK_ALERT_VIEWED",
  "MINI_HOME_LAYOUT_SAVED",
  "MINI_HOME_SHARE_LINK_CREATED",
  "MINI_HOME_SHARE_SHEET_OPENED",
  "MINI_HOME_ACQUISITION_SOURCE_VIEWED",
  "SYNC_COMPLETED",
  "SYNC_FAILED",
  "ACCOUNT_DELETION_REQUESTED",
  "ACCOUNT_DELETION_COMPLETED",
  "ACCOUNT_DELETION_FAILED",
] as const;

export type AnalyticsEventName = (typeof ANALYTICS_EVENT_NAMES)[number];

export const CLIENT_ANALYTICS_EVENT_NAMES = [
  "APP_SESSION_STARTED",
  "IDENTIFICATION_REQUEST_SUBMITTED",
  "IDENTIFICATION_RESULT_AVAILABLE",
  "IDENTIFICATION_FAILED",
  "IDENTIFICATION_RESULT_CONFIRMED",
  "IDENTIFICATION_RESULT_EDITED",
  "PLANT_REGISTRATION_COMPLETED",
  "CARE_INFORMATION_VIEWED",
  "WATERING_COMPLETED",
  "WEATHER_RISK_ALERT_VIEWED",
  "MINI_HOME_SHARE_SHEET_OPENED",
  "MINI_HOME_ACQUISITION_SOURCE_VIEWED",
  "SYNC_COMPLETED",
  "SYNC_FAILED",
] as const satisfies readonly AnalyticsEventName[];

export type ClientAnalyticsEventName =
  (typeof CLIENT_ANALYTICS_EVENT_NAMES)[number];

export const SERVER_ANALYTICS_EVENT_NAMES = ANALYTICS_EVENT_NAMES.filter(
  (eventName) =>
    !(CLIENT_ANALYTICS_EVENT_NAMES as readonly AnalyticsEventName[]).includes(
      eventName,
    ),
);

const ownerUidSchema = z.string().min(1).max(128);
const positiveGenerationSchema = z.number().int().safe().positive();
const operationIdSchema = z.string().regex(/^[A-Za-z0-9_-]{8,128}$/);
const analyticsEventNameSchema = z.enum(ANALYTICS_EVENT_NAMES);

export const analyticsEventInputSchema = z
  .object({
    schemaVersion: z.literal(1),
    eventId: z.string().uuid(),
    eventName: analyticsEventNameSchema,
    consentRevision: positiveGenerationSchema,
  })
  .strict();

export const analyticsEventBatchInputSchema = z
  .object({
    ownerUid: ownerUidSchema,
    events: z
      .array(analyticsEventInputSchema)
      .min(1)
      .max(ANALYTICS_EVENT_BATCH_LIMIT),
  })
  .strict();

export const getAnalyticsConsentInputSchema = z
  .object({ ownerUid: ownerUidSchema })
  .strict();

export const setAnalyticsConsentInputSchema = z
  .object({
    ownerUid: ownerUidSchema,
    granted: z.boolean(),
    commandGeneration: positiveGenerationSchema,
    operationId: operationIdSchema,
  })
  .strict();

export type AnalyticsEventInput = z.infer<typeof analyticsEventInputSchema>;
export type AnalyticsAuth = Readonly<{ uid: string }>;

export type AnalyticsConsentView = Readonly<{
  schemaVersion: 1;
  granted: boolean;
  commandGeneration: number;
  grantedAtEpochMillis: number | null;
  revokedAtEpochMillis: number | null;
}>;

export type AnalyticsConsentMutation = Readonly<{
  ownerUid: string;
  granted: boolean;
  commandGeneration: number;
  operationId: string;
}>;

export type AnalyticsConsentMutationResult = AnalyticsConsentView &
  Readonly<{
    replayed: boolean;
    purgedEventCount: number;
  }>;

export type AnalyticsEventBatchWrite = Readonly<{
  ownerUid: string;
  origin: "CLIENT";
  events: readonly AnalyticsEventInput[];
}>;

export type AnalyticsEventBatchEntryResult = Readonly<{
  eventId: string;
  accepted: true;
  duplicate: boolean;
}>;

export type AnalyticsEventBatchResult = Readonly<{
  results: readonly AnalyticsEventBatchEntryResult[];
}>;

export type AnalyticsEventWriteResult = Readonly<{
  eventId: string;
  accepted: true;
  replayed: boolean;
  occurredAtEpochMillis: number;
  expiresAtEpochMillis: number;
}>;

export interface AnalyticsStore {
  getConsent(ownerUid: string): Promise<AnalyticsConsentView>;
  setConsent(
    command: AnalyticsConsentMutation,
  ): Promise<AnalyticsConsentMutationResult>;
  recordEvents(
    command: AnalyticsEventBatchWrite,
  ): Promise<AnalyticsEventBatchResult>;
}

export type AnalyticsErrorCode =
  | "unauthenticated"
  | "permission-denied"
  | "invalid-argument"
  | "failed-precondition"
  | "already-exists"
  | "aborted"
  | "deadline-exceeded";

export class AnalyticsError extends Error {
  override readonly name = "AnalyticsError";

  constructor(
    readonly code: AnalyticsErrorCode,
    message: string,
  ) {
    super(message);
  }
}

export async function executeGetAnalyticsConsent(
  auth: AnalyticsAuth | null,
  input: unknown,
  store: AnalyticsStore,
): Promise<AnalyticsConsentView> {
  const authenticatedOwnerUid = authenticated(auth);
  const parsed = parseBoundary(getAnalyticsConsentInputSchema, input);
  assertOwner(parsed.ownerUid, authenticatedOwnerUid);
  return store.getConsent(authenticatedOwnerUid);
}

export async function executeSetAnalyticsConsent(
  auth: AnalyticsAuth | null,
  input: unknown,
  store: AnalyticsStore,
): Promise<AnalyticsConsentMutationResult> {
  const authenticatedOwnerUid = authenticated(auth);
  const parsed = parseBoundary(setAnalyticsConsentInputSchema, input);
  assertOwner(parsed.ownerUid, authenticatedOwnerUid);
  return store.setConsent({
    ownerUid: authenticatedOwnerUid,
    granted: parsed.granted,
    commandGeneration: parsed.commandGeneration,
    operationId: parsed.operationId,
  });
}

export async function executeRecordClientAnalyticsEvents(
  auth: AnalyticsAuth | null,
  input: unknown,
  store: AnalyticsStore,
): Promise<AnalyticsEventBatchResult> {
  const authenticatedOwnerUid = authenticated(auth);
  const parsed = parseBoundary(analyticsEventBatchInputSchema, input);
  assertOwner(parsed.ownerUid, authenticatedOwnerUid);
  for (const event of parsed.events) {
    if (
      !(CLIENT_ANALYTICS_EVENT_NAMES as readonly AnalyticsEventName[]).includes(
        event.eventName,
      )
    ) {
      throw new AnalyticsError(
        "permission-denied",
        "This analytics event can only be recorded by an authoritative server flow",
      );
    }
  }
  return store.recordEvents({
    ownerUid: authenticatedOwnerUid,
    origin: "CLIENT",
    events: parsed.events,
  });
}

export function parseServerAnalyticsEvent(input: unknown): AnalyticsEventInput {
  return parseBoundary(analyticsEventInputSchema, input);
}

function authenticated(auth: AnalyticsAuth | null): string {
  if (auth === null) {
    throw new AnalyticsError("unauthenticated", "Sign-in is required");
  }
  return auth.uid;
}

function assertOwner(ownerUid: string, authenticatedOwnerUid: string): void {
  if (ownerUid !== authenticatedOwnerUid) {
    throw new AnalyticsError(
      "permission-denied",
      "Request owner does not match Auth uid",
    );
  }
}

function parseBoundary<T>(schema: z.ZodType<T>, input: unknown): T {
  const result = schema.safeParse(input);
  if (!result.success) {
    throw new AnalyticsError(
      "invalid-argument",
      "Analytics request is invalid",
    );
  }
  return result.data;
}
