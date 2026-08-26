import {
  FieldPath,
  FieldValue,
  Timestamp,
  type DocumentSnapshot,
  type Firestore,
  type Query,
} from "firebase-admin/firestore";

export const WEATHER_RETENTION_MILLIS = 35 * 24 * 60 * 60 * 1_000;
export const WEATHER_RETENTION_CLEANUP_LIMIT = 100;

export type WeatherRetentionDataKind = "weatherSnapshots" | "weatherRisks" | "weatherAlerts";
export type WeatherRetentionCleanupStage = "query" | "mutation";

export type WeatherRetentionCleanupFailure = Readonly<{
  kind: WeatherRetentionDataKind;
  path: string;
  stage: WeatherRetentionCleanupStage;
  error: unknown;
}>;

export type WeatherRetentionCleanupResult = Readonly<{
  scanned: number;
  deleted: number;
  terminalized: number;
  preserved: number;
  hasMore: boolean;
  failures: readonly WeatherRetentionCleanupFailure[];
}>;

type RetentionCandidate = Readonly<{
  kind: WeatherRetentionDataKind;
  document: DocumentSnapshot;
  dueAtMillis: number;
}>;

type RetentionMutationOutcome = "deleted" | "terminalized" | "preserved";

const TERMINAL_WEATHER_ALERT_STATUSES = new Set([
  "CANCELLED",
  "FAILED",
  "SENT",
  "SENT_AMBIGUOUS",
]);

export function weatherRetentionTimestamp(anchor: Date): Timestamp {
  const anchorMillis = anchor.valueOf();
  if (!Number.isFinite(anchorMillis)) throw new TypeError("Weather retention anchor is invalid");
  return Timestamp.fromMillis(anchorMillis + WEATHER_RETENTION_MILLIS);
}

export function terminalWeatherAlertFields(at: Date): Readonly<{
  terminalAt: Timestamp;
  expiresAt: Timestamp;
}> {
  const terminalAt = Timestamp.fromDate(at);
  return {
    terminalAt,
    expiresAt: Timestamp.fromMillis(terminalAt.toMillis() + WEATHER_RETENTION_MILLIS),
  };
}

export async function cleanupExpiredWeatherData(
  firestore: Firestore,
  now: Timestamp = Timestamp.now(),
  limit = WEATHER_RETENTION_CLEANUP_LIMIT,
): Promise<WeatherRetentionCleanupResult> {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 500) {
    throw new TypeError("Weather retention cleanup limit is invalid");
  }
  const cutoff = Timestamp.fromMillis(now.toMillis() - WEATHER_RETENTION_MILLIS);
  const queryLimit = limit + 1;
  const queries: readonly Readonly<{
    kind: WeatherRetentionDataKind;
    query: Query;
  }>[] = [
    {
      kind: "weatherSnapshots",
      query: firestore.collectionGroup("weatherSnapshots")
        .where("observedAt", "<=", cutoff)
        .orderBy("observedAt", "asc")
        .orderBy(FieldPath.documentId(), "asc")
        .limit(queryLimit),
    },
    {
      kind: "weatherRisks",
      query: firestore.collectionGroup("weatherRisks")
        .where("observedAt", "<=", cutoff)
        .orderBy("observedAt", "asc")
        .orderBy(FieldPath.documentId(), "asc")
        .limit(queryLimit),
    },
    {
      kind: "weatherAlerts",
      query: firestore.collectionGroup("weatherAlerts")
        .where("expiresAt", "<=", now)
        .orderBy("expiresAt", "asc")
        .orderBy(FieldPath.documentId(), "asc")
        .limit(queryLimit),
    },
  ];
  const queryOutcomes = await Promise.allSettled(queries.map(({ query }) => query.get()));
  const failures: WeatherRetentionCleanupFailure[] = [];
  const candidates: RetentionCandidate[] = [];
  let queryHasMore = false;
  queryOutcomes.forEach((outcome, index) => {
    const kind = queries[index]!.kind;
    if (outcome.status === "rejected") {
      failures.push({
        kind,
        path: `collectionGroup/${kind}`,
        stage: "query",
        error: outcome.reason,
      });
      return;
    }
    if (outcome.value.size > limit) queryHasMore = true;
    for (const document of outcome.value.docs) {
      const anchor = kind === "weatherAlerts"
        ? document.get("expiresAt")
        : document.get("observedAt");
      if (!(anchor instanceof Timestamp)) continue;
      candidates.push({
        kind,
        document,
        dueAtMillis: kind === "weatherAlerts"
          ? anchor.toMillis()
          : anchor.toMillis() + WEATHER_RETENTION_MILLIS,
      });
    }
  });
  candidates.sort((left, right) =>
    left.dueAtMillis - right.dueAtMillis ||
    left.kind.localeCompare(right.kind) ||
    left.document.ref.path.localeCompare(right.document.ref.path)
  );
  const selected = candidates.slice(0, limit);
  const mutationOutcomes = await Promise.allSettled(selected.map((candidate) =>
    mutateExpiredWeatherDocument(firestore, candidate, now, cutoff)
  ));
  let deleted = 0;
  let terminalized = 0;
  let preserved = 0;
  mutationOutcomes.forEach((outcome, index) => {
    if (outcome.status === "rejected") {
      const candidate = selected[index]!;
      failures.push({
        kind: candidate.kind,
        path: candidate.document.ref.path,
        stage: "mutation",
        error: outcome.reason,
      });
      return;
    }
    switch (outcome.value) {
      case "deleted":
        deleted += 1;
        break;
      case "terminalized":
        terminalized += 1;
        break;
      case "preserved":
        preserved += 1;
        break;
    }
  });
  return {
    scanned: selected.length,
    deleted,
    terminalized,
    preserved,
    hasMore: queryHasMore || candidates.length > limit,
    failures,
  };
}

async function mutateExpiredWeatherDocument(
  firestore: Firestore,
  candidate: RetentionCandidate,
  now: Timestamp,
  cutoff: Timestamp,
): Promise<RetentionMutationOutcome> {
  return firestore.runTransaction(async (transaction) => {
    const current = await transaction.get(candidate.document.ref);
    if (!current.exists) return "preserved";
    if (candidate.kind !== "weatherAlerts") {
      const observedAt = current.get("observedAt");
      if (!(observedAt instanceof Timestamp) || observedAt.toMillis() > cutoff.toMillis()) {
        return "preserved";
      }
      transaction.delete(current.ref);
      return "deleted";
    }

    const expiresAt = current.get("expiresAt");
    if (!(expiresAt instanceof Timestamp) || expiresAt.toMillis() > now.toMillis()) {
      return "preserved";
    }
    const leaseExpiresAt = current.get("leaseExpiresAt");
    if (leaseExpiresAt instanceof Timestamp && leaseExpiresAt.toMillis() > now.toMillis()) {
      return "preserved";
    }
    const status = current.get("status");
    if (typeof status !== "string") return "preserved";
    if (TERMINAL_WEATHER_ALERT_STATUSES.has(status)) {
      const terminalAt = current.get("terminalAt");
      if (terminalAt instanceof Timestamp) {
        const expectedExpiresAt = terminalAt.toMillis() + WEATHER_RETENTION_MILLIS;
        if (expectedExpiresAt <= now.toMillis()) {
          transaction.delete(current.ref);
          return "deleted";
        }
        transaction.update(current.ref, {
          expiresAt: Timestamp.fromMillis(expectedExpiresAt),
          leaseExpiresAt: FieldValue.delete(),
          updatedAt: FieldValue.serverTimestamp(),
        });
        return "terminalized";
      }
      const legacyTerminalAt = firstTimestamp(
        current.get("sentAt"),
        current.get("updatedAt"),
        current.get("createdAt"),
      );
      if (legacyTerminalAt === null) return "preserved";
      const legacyExpiresAt = legacyTerminalAt.toMillis() + WEATHER_RETENTION_MILLIS;
      if (legacyExpiresAt <= now.toMillis()) {
        transaction.delete(current.ref);
        return "deleted";
      }
      transaction.update(current.ref, {
        terminalAt: legacyTerminalAt,
        expiresAt: Timestamp.fromMillis(legacyExpiresAt),
        leaseExpiresAt: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return "terminalized";
    }

    const terminalFields = terminalWeatherAlertFields(now.toDate());
    if (status === "PENDING" || status === "CLAIMED") {
      transaction.update(current.ref, {
        status: "CANCELLED",
        failureKind: "RETENTION_EXPIRED",
        ...terminalFields,
        leaseExpiresAt: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return "terminalized";
    }
    if (status === "SEND_MAY_HAVE_OCCURRED") {
      transaction.update(current.ref, {
        status: "SENT_AMBIGUOUS",
        failureKind: "RETENTION_EXPIRED_AFTER_SEND_BOUNDARY",
        ...terminalFields,
        leaseExpiresAt: FieldValue.delete(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      return "terminalized";
    }
    return "preserved";
  });
}

function firstTimestamp(...values: readonly unknown[]): Timestamp | null {
  return values.find((value): value is Timestamp => value instanceof Timestamp) ?? null;
}
