package com.planterior.helper.core.model

/**
 * Complete server analytics vocabulary. Server-only events are intentionally absent from
 * [ClientProductEvent].
 */
enum class ProductEvent {
    APP_SESSION_STARTED,
    IDENTIFICATION_REQUEST_SUBMITTED,
    IDENTIFICATION_RESULT_AVAILABLE,
    IDENTIFICATION_FAILED,
    IDENTIFICATION_RESULT_CONFIRMED,
    IDENTIFICATION_RESULT_EDITED,
    PLANT_REGISTRATION_COMPLETED,
    CARE_INFORMATION_VIEWED,
    WATERING_NOTIFICATION_SENT,
    WATERING_NOTIFICATION_OPENED,
    WATERING_COMPLETED,
    WEATHER_RISK_ALERT_CREATED,
    WEATHER_RISK_NOTIFICATION_SENT,
    WEATHER_RISK_ALERT_VIEWED,
    MINI_HOME_LAYOUT_SAVED,
    MINI_HOME_SHARE_LINK_CREATED,
    MINI_HOME_SHARE_SHEET_OPENED,
    MINI_HOME_ACQUISITION_SOURCE_VIEWED,
    SYNC_COMPLETED,
    SYNC_FAILED,
    ACCOUNT_DELETION_REQUESTED,
    ACCOUNT_DELETION_COMPLETED,
    ACCOUNT_DELETION_FAILED,
}

/** Events whose observation boundary exists on the Android client. */
enum class ClientProductEvent(val event: ProductEvent) {
    APP_SESSION_STARTED(ProductEvent.APP_SESSION_STARTED),
    IDENTIFICATION_REQUEST_SUBMITTED(ProductEvent.IDENTIFICATION_REQUEST_SUBMITTED),
    IDENTIFICATION_RESULT_AVAILABLE(ProductEvent.IDENTIFICATION_RESULT_AVAILABLE),
    IDENTIFICATION_FAILED(ProductEvent.IDENTIFICATION_FAILED),
    IDENTIFICATION_RESULT_CONFIRMED(ProductEvent.IDENTIFICATION_RESULT_CONFIRMED),
    IDENTIFICATION_RESULT_EDITED(ProductEvent.IDENTIFICATION_RESULT_EDITED),
    PLANT_REGISTRATION_COMPLETED(ProductEvent.PLANT_REGISTRATION_COMPLETED),
    CARE_INFORMATION_VIEWED(ProductEvent.CARE_INFORMATION_VIEWED),
    WATERING_COMPLETED(ProductEvent.WATERING_COMPLETED),
    WEATHER_RISK_ALERT_VIEWED(ProductEvent.WEATHER_RISK_ALERT_VIEWED),
    MINI_HOME_SHARE_SHEET_OPENED(ProductEvent.MINI_HOME_SHARE_SHEET_OPENED),
    MINI_HOME_ACQUISITION_SOURCE_VIEWED(ProductEvent.MINI_HOME_ACQUISITION_SOURCE_VIEWED),
    SYNC_COMPLETED(ProductEvent.SYNC_COMPLETED),
    SYNC_FAILED(ProductEvent.SYNC_FAILED),
}

sealed interface AnalyticsConsentState {
    data object Loading : AnalyticsConsentState

    data object Disabled : AnalyticsConsentState

    data object Enabling : AnalyticsConsentState

    data class Enabled(val revision: Int) : AnalyticsConsentState {
        init {
            require(revision > 0)
        }
    }

    data object Disabling : AnalyticsConsentState

    data object FailedOff : AnalyticsConsentState
}

fun interface ProductEventRecorder {
    /** Best effort and non-blocking. Telemetry can never change the product operation's result. */
    fun record(event: ClientProductEvent)
}
