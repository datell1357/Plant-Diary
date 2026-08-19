package com.planterior.helper.feature.watering

import androidx.lifecycle.SavedStateHandle
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.planterior.helper.core.model.PersonalPlantId
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class GlobalWateringReminder(
    val enabled: Boolean,
    val defaultTime: LocalTime,
    val zoneId: ZoneId,
)

data class PlantWateringReminder(
    val plantId: PersonalPlantId,
    val enabled: Boolean,
    val timeOverride: LocalTime?,
    val displayName: String = plantId.value,
)

data class WateringNotificationSettings(
    val global: GlobalWateringReminder,
    val plants: List<PlantWateringReminder>,
    val revision: Long = 0,
)

data class EffectiveWateringReminder(
    val enabled: Boolean,
    val time: LocalTime,
    val zoneId: ZoneId,
    val scheduleRemainsVisible: Boolean = true,
    val completionRemainsAvailable: Boolean = true,
)

fun effectiveReminder(
    global: GlobalWateringReminder,
    plant: PlantWateringReminder,
): EffectiveWateringReminder =
    EffectiveWateringReminder(
        enabled = global.enabled && plant.enabled,
        time = plant.timeOverride ?: global.defaultTime,
        zoneId = global.zoneId,
    )

sealed interface WateringSettingsSaveResult {
    data class Saved(val settings: WateringNotificationSettings) : WateringSettingsSaveResult

    data object Conflict : WateringSettingsSaveResult

    data object Failed : WateringSettingsSaveResult
}

interface WateringNotificationSettingsRepository {
    suspend fun load(): WateringNotificationSettings

    suspend fun save(settings: WateringNotificationSettings): WateringSettingsSaveResult
}

sealed interface WateringNotificationSettingsState {
    data object Loading : WateringNotificationSettingsState

    data class Ready(
        val settings: WateringNotificationSettings,
        val errorMessage: String? = null,
    ) : WateringNotificationSettingsState

    data class Editing(
        val confirmed: WateringNotificationSettings,
        val draft: WateringNotificationSettings,
    ) : WateringNotificationSettingsState

    data class Saving(
        val confirmed: WateringNotificationSettings,
        val draft: WateringNotificationSettings,
    ) : WateringNotificationSettingsState

    data class SaveFailed(
        val confirmed: WateringNotificationSettings,
        val draft: WateringNotificationSettings,
    ) : WateringNotificationSettingsState

    data object Empty : WateringNotificationSettingsState

    data object Error : WateringNotificationSettingsState
}

class WateringNotificationSettingsController(
    private val repository: WateringNotificationSettingsRepository,
    private val savedStateHandle: SavedStateHandle? = null,
) {
    private val mutableState =
        MutableStateFlow(
            savedStateHandle?.get<String>(SAVED_STATE_KEY)?.let(::decodeRetainedState)
                ?: WateringNotificationSettingsState.Loading
        )
    val state: StateFlow<WateringNotificationSettingsState> = mutableState.asStateFlow()

    suspend fun loadIfNeeded() {
        if (mutableState.value == WateringNotificationSettingsState.Loading) load()
    }

    suspend fun load() {
        mutableState.value = WateringNotificationSettingsState.Loading
        mutableState.value =
            try {
                WateringNotificationSettingsState.Ready(repository.load()).also { clearRetained() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                WateringNotificationSettingsState.Error
            }
    }

    fun setGlobalEnabled(enabled: Boolean) = edit { settings ->
        settings.copy(global = settings.global.copy(enabled = enabled))
    }

    fun setDefaultTime(time: LocalTime) = edit { settings ->
        settings.copy(global = settings.global.copy(defaultTime = time))
    }

    fun setPlantEnabled(plantId: PersonalPlantId, enabled: Boolean) = edit { settings ->
        settings.copy(
            plants =
                settings.plants.map { plant ->
                    if (plant.plantId == plantId) plant.copy(enabled = enabled) else plant
                }
        )
    }

    fun setPlantTime(plantId: PersonalPlantId, time: LocalTime?) = edit { settings ->
        settings.copy(
            plants =
                settings.plants.map { plant ->
                    if (plant.plantId == plantId) plant.copy(timeOverride = time) else plant
                }
        )
    }

    suspend fun save() {
        val (confirmed, draft) =
            when (val current = mutableState.value) {
                is WateringNotificationSettingsState.Editing -> current.confirmed to current.draft
                is WateringNotificationSettingsState.SaveFailed ->
                    current.confirmed to current.draft
                else -> return
            }
        mutableState.value =
            WateringNotificationSettingsState.Saving(confirmed, draft).also {
                retain(WateringNotificationSettingsState.Editing(confirmed, draft))
            }
        val saved =
            try {
                repository.save(draft)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                WateringSettingsSaveResult.Failed
            }
        mutableState.value =
            when (saved) {
                is WateringSettingsSaveResult.Saved ->
                    WateringNotificationSettingsState.Ready(saved.settings).also {
                        clearRetained()
                    }
                WateringSettingsSaveResult.Conflict ->
                    try {
                        WateringNotificationSettingsState.Ready(
                                repository.load(),
                                "다른 기기에서 설정이 변경되어 최신 설정을 불러왔어요.",
                            )
                            .also { clearRetained() }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        WateringNotificationSettingsState.Error
                    }
                WateringSettingsSaveResult.Failed ->
                    WateringNotificationSettingsState.SaveFailed(confirmed, draft).also(::retain)
            }
    }

    private fun edit(transform: (WateringNotificationSettings) -> WateringNotificationSettings) {
        val (confirmed, draft) =
            when (val current = mutableState.value) {
                is WateringNotificationSettingsState.Ready -> current.settings to current.settings
                is WateringNotificationSettingsState.Editing -> current.confirmed to current.draft
                is WateringNotificationSettingsState.SaveFailed ->
                    current.confirmed to current.draft
                else -> return
            }
        mutableState.value =
            WateringNotificationSettingsState.Editing(confirmed, transform(draft)).also(::retain)
    }

    private fun retain(state: WateringNotificationSettingsState) {
        savedStateHandle?.set(SAVED_STATE_KEY, encodeRetainedState(state))
    }

    private fun clearRetained() {
        savedStateHandle?.remove<String>(SAVED_STATE_KEY)
    }

    private companion object {
        const val SAVED_STATE_KEY = "watering-notification-settings-state"
    }
}

private fun encodeRetainedState(state: WateringNotificationSettingsState): String {
    val (kind, confirmed, draft) =
        when (state) {
            is WateringNotificationSettingsState.Editing ->
                Triple("editing", state.confirmed, state.draft)
            is WateringNotificationSettingsState.SaveFailed ->
                Triple("failed", state.confirmed, state.draft)
            else -> error("Only editable notification settings state can be retained")
        }
    return buildJsonObject {
        put("kind", kind)
        put("confirmed", encodeSettings(confirmed))
        put("draft", encodeSettings(draft))
    }
        .toString()
}

private fun decodeRetainedState(value: String): WateringNotificationSettingsState? = runCatching {
    val root = Json.parseToJsonElement(value).jsonObject
    val confirmed = decodeSettings(root.getValue("confirmed").jsonObject)
    val draft = decodeSettings(root.getValue("draft").jsonObject)
    when (root.getValue("kind").jsonPrimitive.content) {
        "editing" -> WateringNotificationSettingsState.Editing(confirmed, draft)
        "failed" -> WateringNotificationSettingsState.SaveFailed(confirmed, draft)
        else -> null
    }
}
    .getOrNull()

private fun encodeSettings(settings: WateringNotificationSettings) = buildJsonObject {
    put("enabled", settings.global.enabled)
    put("defaultTime", settings.global.defaultTime.toString())
    put("zoneId", settings.global.zoneId.id)
    put("revision", settings.revision)
    put(
        "plants",
        buildJsonArray {
            settings.plants.forEach { plant ->
                add(
                    buildJsonObject {
                        put("plantId", plant.plantId.value)
                        put("enabled", plant.enabled)
                        put("timeOverride", plant.timeOverride?.toString() ?: "")
                        put("displayName", plant.displayName)
                    }
                )
            }
        },
    )
}

private fun decodeSettings(value: kotlinx.serialization.json.JsonObject) =
    WateringNotificationSettings(
        global =
            GlobalWateringReminder(
                enabled = value.getValue("enabled").jsonPrimitive.boolean,
                defaultTime = LocalTime.parse(value.getValue("defaultTime").jsonPrimitive.content),
                zoneId = ZoneId.of(value.getValue("zoneId").jsonPrimitive.content),
            ),
        plants =
            value.getValue("plants").jsonArray.map { item ->
                val plant = item.jsonObject
                PlantWateringReminder(
                    plantId = PersonalPlantId(plant.getValue("plantId").jsonPrimitive.content),
                    enabled = plant.getValue("enabled").jsonPrimitive.boolean,
                    timeOverride =
                        plant
                            .getValue("timeOverride")
                            .jsonPrimitive
                            .content
                            .takeIf(String::isNotEmpty)
                            ?.let(LocalTime::parse),
                    displayName = plant.getValue("displayName").jsonPrimitive.content,
                )
            },
        revision = value.getValue("revision").jsonPrimitive.long,
    )

internal fun committedSettingsRevision(response: Any?, currentRevision: Long): Long? =
    ((response as? Map<*, *>)?.get("revision") as? Number)?.toLong()?.takeIf {
        it > currentRevision
    }

internal object FirebaseWateringSettingsFailureClassifier {
    fun isConflict(codeName: String): Boolean =
        codeName == "ABORTED" || codeName == "FAILED_PRECONDITION"
}

class FirebaseWateringNotificationSettingsRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : WateringNotificationSettingsRepository {
    override suspend fun load(): WateringNotificationSettings {
        val accountId = auth.currentUser?.uid ?: error("Authentication is required")
        functions
            .getHttpsCallable("ensureWateringNotificationSettings")
            .call(mapOf("expectedOwnerUid" to accountId))
            .await()
        ensureAccount(accountId)
        val account = firestore.document("users/$accountId").get().await()
        val settings =
            firestore.document("users/$accountId/notificationSettings/watering").get().await()
        ensureAccount(accountId)
        check(settings.exists()) { "Authoritative notification settings are unavailable" }
        val zone = ZoneId.of(requireNotNull(account.getString("zoneId")))
        val plants = firestore.collection("users/$accountId/personalPlants").get().await()
        val preferences =
            firestore.collection("users/$accountId/notificationPlantSettings").get().await()
        ensureAccount(accountId)
        val preferencesByPlant = preferences.documents.associateBy { it.id }
        return WateringNotificationSettings(
            global =
                GlobalWateringReminder(
                    enabled = requireNotNull(settings.getBoolean("wateringEnabled")),
                    defaultTime =
                        LocalTime.parse(requireNotNull(settings.getString("defaultTime"))),
                    zoneId = zone,
                ),
            plants =
                plants.documents.mapNotNull { plant ->
                    val name = plant.getString("displayName") ?: return@mapNotNull null
                    val preference = preferencesByPlant[plant.id]
                    PlantWateringReminder(
                        PersonalPlantId(plant.id),
                        enabled = preference?.getBoolean("enabled") ?: true,
                        timeOverride = preference?.getString("timeOverride")?.let(LocalTime::parse),
                        displayName = name,
                    )
                },
            revision = requireNotNull(settings.getLong("revision")),
        )
    }

    override suspend fun save(settings: WateringNotificationSettings): WateringSettingsSaveResult {
        val accountId = auth.currentUser?.uid ?: return WateringSettingsSaveResult.Failed
        return try {
            val response =
                functions
                    .getHttpsCallable("updateWateringNotificationSettings")
                    .call(
                        mapOf(
                            "expectedOwnerUid" to accountId,
                            "expectedRevision" to settings.revision,
                            "defaultTime" to
                                settings.global.defaultTime.format(
                                    DateTimeFormatter.ofPattern("HH:mm")
                                ),
                            "zoneId" to settings.global.zoneId.id,
                            "globalEnabled" to settings.global.enabled,
                            "plants" to
                                settings.plants.map { plant ->
                                    mapOf(
                                        "plantId" to plant.plantId.value,
                                        "enabled" to plant.enabled,
                                        "timeOverride" to
                                            plant.timeOverride?.format(
                                                DateTimeFormatter.ofPattern("HH:mm")
                                            ),
                                    )
                                },
                        )
                    )
                    .await()
            ensureAccount(accountId)
            val revision =
                committedSettingsRevision(response.data, settings.revision)
                    ?: return WateringSettingsSaveResult.Failed
            WateringSettingsSaveResult.Saved(settings.copy(revision = revision))
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFunctionsException) {
            if (FirebaseWateringSettingsFailureClassifier.isConflict(error.code.name)) {
                WateringSettingsSaveResult.Conflict
            } else {
                WateringSettingsSaveResult.Failed
            }
        } catch (error: FirebaseFirestoreException) {
            WateringSettingsSaveResult.Failed
        } catch (_: Exception) {
            WateringSettingsSaveResult.Failed
        }
    }

    private fun ensureAccount(accountId: String) {
        if (auth.currentUser?.uid != accountId) throw SecurityException("Active account changed")
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel(CancellationException("Firebase task cancelled")) }
}
