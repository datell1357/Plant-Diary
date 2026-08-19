package com.planterior.helper.feature.weather

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.suspendCancellableCoroutine

class FirebaseWeatherRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : WeatherRepository {
    private val maxSafeConsentGeneration = 9_007_199_254_740_991L

    override fun accounts(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }
        .distinctUntilChanged()

    override suspend fun load(accountId: String): WeatherLoad {
        requireCurrentAccount(accountId)
        val snapshot =
            firestore.document("users/$accountId/weatherSnapshots/current").get().awaitTask()
        if (!snapshot.exists()) return WeatherLoad.NotConfigured
        val settings =
            firestore.document("users/$accountId/weatherSettings/current").get().awaitTask()
        val plants =
            firestore
                .collection("users/$accountId/personalPlants")
                .get()
                .awaitTask()
                .documents
                .filter { it.getString("ownerUid") == accountId }
                .associate { it.id to it.getString("displayName").orEmpty().ifBlank { "등록 식물" } }
        val risks =
            firestore
                .collection("users/$accountId/weatherRisks")
                .whereEqualTo("active", true)
                .get()
                .awaitTask()
                .documents
                .filter { it.getString("plantId") in plants }
        val preferences =
            firestore
                .collection("users/$accountId/weatherPlantSettings")
                .get()
                .awaitTask()
                .documents
                .associate { it.id to (it.getBoolean("enabled") != false) }
        val dashboard = dashboard(snapshot, settings, risks, preferences, plants)
        return if (dashboard.stale) WeatherLoad.Stale(dashboard) else WeatherLoad.Fresh(dashboard)
    }

    override suspend fun recordLocationConsent(
        accountId: String,
        granted: Boolean,
        commandGeneration: Long,
    ): WeatherConsentMutationResult {
        val result =
            if (commandGeneration > maxSafeConsentGeneration) {
                recoverLocationConsent(accountId)
            } else {
                try {
                    call(
                        "setWeatherLocationConsent",
                        mapOf(
                            "expectedOwnerUid" to accountId,
                            "granted" to granted,
                            "commandGeneration" to commandGeneration,
                        ),
                    )
                } catch (conflict: FirebaseFunctionsException) {
                    if (conflict.code != FirebaseFunctionsException.Code.ABORTED) throw conflict
                    try {
                        recoverLocationConsent(accountId)
                    } catch (_: FirebaseFunctionsException) {
                        throw conflict.toWeatherConsentConflict() ?: conflict
                    }
                }
            }
        return WeatherConsentMutationResult(
            authoritativeGeneration = result.requiredLong("commandGeneration"),
            authoritativeGranted = result.requiredBoolean("granted"),
            recovered = result["recovered"] == true,
        )
    }

    override suspend fun refresh(
        accountId: String,
        location: ApproximateLocation?,
    ): WeatherLoad {
        val data =
            call(
                "refreshWeather",
                buildMap {
                    put("expectedOwnerUid", accountId)
                    location?.let {
                        put(
                            "location",
                            mapOf("latitude" to it.latitude, "longitude" to it.longitude),
                        )
                    }
                },
            )
        return data.toWeatherLoad()
    }

    override suspend fun switchToCurrentLocation(
        accountId: String,
        location: ApproximateLocation,
    ): WeatherLoad {
        val data =
            call(
                "refreshWeather",
                mapOf(
                    "expectedOwnerUid" to accountId,
                    "location" to
                        mapOf(
                            "latitude" to location.latitude,
                            "longitude" to location.longitude,
                        ),
                    "switchToDeviceRegion" to true,
                ),
            )
        return data.toWeatherLoad()
    }

    override suspend fun searchRegions(
        accountId: String,
        query: String,
    ): List<WeatherRegion> {
        val result =
            functions
                .getHttpsCallable("searchWeatherRegions")
                .call(mapOf("expectedOwnerUid" to accountId, "query" to query))
                .awaitTask()
                .data
        return (result as? List<*>)?.map { it.requiredMap().toRegion() }
            ?: error("Region response is malformed")
    }

    override suspend fun selectManualRegion(
        accountId: String,
        region: WeatherRegion,
        expectedRevision: Long,
    ): WeatherLoad {
        call(
            "setManualWeatherRegion",
            mapOf(
                "expectedOwnerUid" to accountId,
                "expectedRevision" to expectedRevision,
                "region" to
                    mapOf(
                        "regionCode" to region.regionCode,
                        "regionName" to region.regionName,
                        "latitude" to region.latitude,
                        "longitude" to region.longitude,
                        "source" to "MANUAL",
                    ),
            ),
        )
        return refresh(accountId, null)
    }

    override suspend fun saveAlerts(
        accountId: String,
        globalEnabled: Boolean,
        plants: Map<String, Boolean>,
        expectedRevision: Long,
    ): WeatherLoad {
        try {
            call(
                "updateWeatherAlerts",
                mapOf(
                    "expectedOwnerUid" to accountId,
                    "globalEnabled" to globalEnabled,
                    "plants" to
                        plants.entries.map { mapOf("plantId" to it.key, "enabled" to it.value) },
                    "expectedRevision" to expectedRevision,
                ),
            )
        } catch (error: FirebaseFunctionsException) {
            if (error.code == FirebaseFunctionsException.Code.ABORTED) {
                throw WeatherRevisionConflictException()
            }
            throw error
        }
        return load(accountId)
    }

    private suspend fun recoverLocationConsent(accountId: String): Map<String, Any?> =
        call(
            "setWeatherLocationConsent",
            mapOf(
                "expectedOwnerUid" to accountId,
                "granted" to false,
                "recoverLegacy" to true,
            ),
        )

    private suspend fun call(name: String, payload: Map<String, Any?>): Map<String, Any?> {
        requireCurrentAccount(payload["expectedOwnerUid"] as String)
        return functions.getHttpsCallable(name).call(payload).awaitTask().data.requiredMap()
    }

    private fun requireCurrentAccount(accountId: String) {
        check(auth.currentUser?.uid == accountId) { "Weather account changed" }
    }
}

internal fun Map<String, Any?>.toWeatherLoad(): WeatherLoad {
    val snapshot = requiredMap("snapshot").toSnapshot()
    val risks =
        requiredList("risks").mapIndexed { index, value ->
            value.requiredMap().toRisk("${requiredLong("revision")}-$index", snapshot.observedAt)
        }
    val unavailable = requiredList("unavailablePlantIds").map { requireNotNull(it as? String) }
    val stale = requiredBoolean("stale")
    val plantAlerts =
        requiredMap("plantAlerts").mapValues { (_, value) ->
            requireNotNull(value as? Boolean)
        }
    val dashboard =
        WeatherDashboard(
            snapshot,
            risks,
            unavailable,
            stale,
            requiredBoolean("globalAlertsEnabled"),
            plantAlerts,
            requiredLong("revision"),
            requiredMap("plants").mapValues { (_, value) ->
                requireNotNull(value as? String)
            },
        )
    return if (stale) WeatherLoad.Stale(dashboard) else WeatherLoad.Fresh(dashboard)
}

private fun dashboard(
    snapshot: DocumentSnapshot,
    settings: DocumentSnapshot,
    risks: List<DocumentSnapshot>,
    preferences: Map<String, Boolean>,
    plants: Map<String, String>,
): WeatherDashboard {
    val weatherSnapshot =
        WeatherSnapshot(
            requireNotNull(snapshot.getString("regionCode")),
            requireNotNull(snapshot.getString("regionName")),
            requireNotNull(snapshot.getDouble("temperatureCelsius")),
            requireNotNull(snapshot.getLong("humidityPercent")).toInt(),
            requireNotNull(snapshot.getDouble("precipitationMillimeters")),
            requireNotNull(snapshot.getTimestamp("observedAt")).toDate().toInstant(),
            requireNotNull(snapshot.getString("zoneId")),
        )
    val stale = snapshot.getBoolean("stale") == true || weatherSnapshot.isStaleAt(Instant.now())
    return WeatherDashboard(
        snapshot = weatherSnapshot,
        risks = risks.map { it.toRisk() },
        unavailablePlants =
            persistedUnavailablePlantIds(snapshot.get("unavailablePlantIds"), plants.keys),
        stale = stale,
        globalAlertsEnabled = settings.getBoolean("globalAlertsEnabled") != false,
        plantAlerts = plants.keys.associateWith { preferences[it] != false },
        revision = settings.getLong("revision") ?: 0,
        plantNames = plants,
    )
}

internal fun persistedUnavailablePlantIds(
    value: Any?,
    ownedPlantIds: Set<String>,
): List<String> =
    (value as? List<*>)
        .orEmpty()
        .take(200)
        .mapNotNull { it as? String }
        .filter { it.matches(Regex("^[A-Za-z0-9_-]{1,128}$")) && it in ownedPlantIds }
        .distinct()
        .sorted()

private fun DocumentSnapshot.toRisk() =
    WeatherRisk(
        riskId = id,
        plantId = requireNotNull(getString("plantId")),
        plantName = requireNotNull(getString("plantName")),
        type = WeatherRiskType.valueOf(requireNotNull(getString("type"))),
        action = getString("action").orEmpty(),
        detectedAt = requireNotNull(getTimestamp("detectedAt")).toDate().toInstant(),
        active = getBoolean("active") == true,
        reason = getString("reason").orEmpty(),
    )

private fun Map<String, Any?>.toRegion() =
    WeatherRegion(
        requiredString("regionCode"),
        requiredString("regionName"),
        requiredDouble("latitude"),
        requiredDouble("longitude"),
    )

private fun Map<String, Any?>.toSnapshot() =
    WeatherSnapshot(
        requiredString("regionCode"),
        requiredString("regionName"),
        requiredDouble("temperatureCelsius"),
        requiredLong("humidityPercent").toInt(),
        requiredDouble("precipitationMillimeters"),
        instant("observedAt"),
        requiredString("zoneId"),
    )

private fun Map<String, Any?>.toRisk(id: String, fallbackDetectedAt: Instant) =
    WeatherRisk(
        id,
        requiredString("plantId"),
        requiredString("plantName"),
        WeatherRiskType.valueOf(requiredString("type")),
        requiredString("action"),
        (this["detectedAt"]?.let(::parseInstant) ?: fallbackDetectedAt),
        true,
        requiredString("reason"),
    )

private fun FirebaseFunctionsException.toWeatherConsentConflict():
    WeatherConsentConflictException? {
    val details = details as? Map<*, *> ?: return null
    if (details["conflict"] != true) return null
    val generation = details["commandGeneration"] as? Number ?: return null
    val authoritativeGeneration = generation.toLong()
    if (generation.toDouble() != authoritativeGeneration.toDouble()) return null
    val granted = details["granted"] as? Boolean ?: return null
    return WeatherConsentConflictException(authoritativeGeneration, granted)
}

private fun Any?.requiredMap(): Map<String, Any?> {
    val source = this as? Map<*, *> ?: error("Weather response is malformed")
    return source.entries.associate { entry ->
        val key = entry.key as? String ?: error("Weather response key is malformed")
        key to entry.value
    }
}

private fun Map<String, Any?>.requiredMap(field: String) = this[field].requiredMap()

private fun Map<String, Any?>.requiredList(field: String): List<*> =
    (this[field] as? List<*>) ?: error("$field is malformed")

private fun Map<String, Any?>.requiredString(field: String): String =
    requireNotNull(this[field] as? String) { "$field is malformed" }

private fun Map<String, Any?>.requiredBoolean(field: String): Boolean =
    requireNotNull(this[field] as? Boolean) { "$field is malformed" }

private fun Map<String, Any?>.requiredLong(field: String): Long {
    val number = requireNotNull(this[field] as? Number) { "$field is malformed" }
    val long = number.toLong()
    require(number.toDouble() == long.toDouble()) { "$field is malformed" }
    return long
}

private fun Map<String, Any?>.requiredDouble(field: String): Double =
    requireNotNull((this[field] as? Number)?.toDouble()) { "$field is malformed" }

private fun Map<String, Any?>.instant(field: String): Instant = parseInstant(this[field])

private fun parseInstant(value: Any?): Instant =
    when (value) {
        is String -> Instant.parse(value)
        is Timestamp -> value.toDate().toInstant()
        is Map<*, *> -> {
            val seconds =
                (value["_seconds"] as? Number)?.toLong() ?: (value["seconds"] as? Number)?.toLong()
            val nanos =
                (value["_nanoseconds"] as? Number)?.toLong()
                    ?: (value["nanoseconds"] as? Number)?.toLong()
                    ?: 0
            Instant.ofEpochSecond(requireNotNull(seconds), nanos)
        }
        else -> error("Weather timestamp is malformed")
    }

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener {
        continuation.cancel(CancellationException("Firebase weather task cancelled"))
    }
}
