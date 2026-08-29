package com.planterior.helper.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.planterior.helper.feature.collection.CollectionRepository
import com.planterior.helper.feature.collection.PlantThumbnailLoader
import com.planterior.helper.feature.minihome.MiniHomeDiagnosticEvent
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.feature.registration.RegistrationDiagnosticEvent
import com.planterior.helper.feature.registration.RegistrationRepository
import com.planterior.helper.feature.registration.RegistrationUiState
import com.planterior.helper.feature.settings.AccountDeletionDependencies
import com.planterior.helper.feature.share.MiniHomeShareRepository
import com.planterior.helper.feature.shop.CatalogMediaLoader
import com.planterior.helper.feature.shop.InventoryRepository
import com.planterior.helper.feature.watering.WateringNotificationSettingsRepository
import com.planterior.helper.feature.watering.WateringRepository
import com.planterior.helper.feature.weather.WeatherPermissionCapabilityStore
import com.planterior.helper.feature.weather.WeatherRepository

/** Optional rendered-state transport owned by an injected runtime harness. */
interface RenderedStateSink {
    fun onMiniHomeRawState(state: MiniHomeUiState)

    fun onMiniHomeDisplayedState(state: MiniHomeUiState)

    fun onRegistrationState(state: RegistrationUiState)

    fun onMiniHomeDiagnosticEvent(event: MiniHomeDiagnosticEvent) = Unit

    fun onRegistrationDiagnosticEvent(event: RegistrationDiagnosticEvent) = Unit
}

internal fun runtimeDiagnosticIdentity(instance: Any?): String? = instance?.let {
    "${it.javaClass.name}@${Integer.toHexString(System.identityHashCode(it))}"
}

internal tailrec fun runtimeDiagnosticActivityIdentity(context: Context): String? =
    when (context) {
        is Activity -> runtimeDiagnosticIdentity(context)
        is ContextWrapper -> runtimeDiagnosticActivityIdentity(context.baseContext)
        else -> null
    }

/** Debug source sets may replace only the product dependencies needed by an integrated harness. */
internal data class AuthRuntimeDependencyOverrides(
    val registrationRepository: RegistrationRepository? = null,
    val collectionRepository: CollectionRepository? = null,
    val miniHomeRepository: MiniHomeRepository? = null,
    val miniHomeShareRepository: MiniHomeShareRepository? = null,
    val inventoryRepository: InventoryRepository? = null,
    val wateringRepository: WateringRepository? = null,
    val wateringNotificationSettingsRepository: WateringNotificationSettingsRepository? = null,
    val weatherRepository: WeatherRepository? = null,
    val weatherPermissionCapabilities: WeatherPermissionCapabilityStore? = null,
    val collectionThumbnailLoader: PlantThumbnailLoader? = null,
    val catalogMediaLoader: CatalogMediaLoader? = null,
    val accountDeletionDependencies: AccountDeletionDependencies? = null,
    val renderedStateSink: RenderedStateSink? = null,
)
