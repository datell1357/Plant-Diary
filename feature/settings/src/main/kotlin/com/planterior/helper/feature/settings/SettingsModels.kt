package com.planterior.helper.feature.settings

import com.planterior.helper.core.model.AnalyticsConsentState
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
    val analyticsConsentState: AnalyticsConsentState = AnalyticsConsentState.Disabled,
    val locationConsentState: SettingsLocationConsentState =
        if (appLocationConsentGranted) SettingsLocationConsentState.Enabled(0)
        else SettingsLocationConsentState.Disabled,
)

data class SettingsActions(
    val onLinkProvider: (AuthProvider, Boolean) -> Unit = { _, _ -> },
    val onWateringNotificationsChanged: (Boolean) -> Unit = {},
    val onWeatherNotificationsChanged: (Boolean) -> Unit = {},
    val onOpenWateringSettings: () -> Unit = {},
    val onOpenQuietHours: () -> Unit = {},
    val onOpenRegion: () -> Unit = {},
    val onLocationConsentChanged: (Boolean) -> Unit = {},
    val onRetryLocationConsent: () -> Unit = {},
    val onOpenPrivacy: () -> Unit = {},
    val onLogout: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    val onOpenAccountDeletion: () -> Unit = {},
    val onAnalyticsConsentChanged: (Boolean) -> Unit = {},
    val onRetryAnalyticsConsent: () -> Unit = {},
)

sealed interface SettingsRegion {
    data class Manual(val name: String) : SettingsRegion

    data class Current(val name: String) : SettingsRegion
}

@JvmInline value class LocationRequestGeneration(val value: Long)

sealed interface SettingsLocationConsentState {
    data object Loading : SettingsLocationConsentState

    data class Enabled(val commandGeneration: Long) : SettingsLocationConsentState

    data object Enabling : SettingsLocationConsentState

    data object Disabled : SettingsLocationConsentState

    data object Disabling : SettingsLocationConsentState

    data object FailedOff : SettingsLocationConsentState
}

data class SettingsLocationState(
    val region: SettingsRegion,
    val consent: SettingsLocationConsentState = SettingsLocationConsentState.Loading,
) {
    val consentRevoked: Boolean
        get() = consent !is SettingsLocationConsentState.Enabled

    val revocationFailed: Boolean
        get() = consent is SettingsLocationConsentState.FailedOff
}
