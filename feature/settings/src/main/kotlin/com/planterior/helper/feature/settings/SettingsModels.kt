package com.planterior.helper.feature.settings

import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.AuthUiState
import java.time.Instant

enum class DevicePermissionState {
    ALLOWED,
    DENIED,
    NOT_REQUESTED,
}

data class SettingsUiState(
    val authState: AuthUiState,
    val wateringNotificationsEnabled: Boolean,
    val weatherNotificationsEnabled: Boolean,
    val quietHoursSummary: String,
    val regionName: String,
    val osLocationPermission: DevicePermissionState,
    val appLocationConsentGranted: Boolean,
    val lastSyncAt: Instant?,
    val osNotificationPermission: DevicePermissionState,
    val appVersion: String,
)

data class SettingsActions(
    val onLinkProvider: (AuthProvider, Boolean) -> Unit = { _, _ -> },
    val onWateringNotificationsChanged: (Boolean) -> Unit = {},
    val onWeatherNotificationsChanged: (Boolean) -> Unit = {},
    val onOpenWateringSettings: () -> Unit = {},
    val onOpenQuietHours: () -> Unit = {},
    val onOpenRegion: () -> Unit = {},
    val onRevokeLocationConsent: () -> Unit = {},
    val onOpenPrivacy: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    val onOpenAccountDeletion: () -> Unit = {},
)

sealed interface SettingsRegion {
    data class Manual(val name: String) : SettingsRegion

    data class Current(val name: String) : SettingsRegion
}

@JvmInline value class LocationRequestGeneration(val value: Long)

data class SettingsLocationState(
    val region: SettingsRegion,
    val consentRevoked: Boolean = false,
    val revocationFailed: Boolean = false,
)

interface CurrentLocationConsentBoundary {
    fun cancelInFlightLocation()

    suspend fun revokeConsent()
}
