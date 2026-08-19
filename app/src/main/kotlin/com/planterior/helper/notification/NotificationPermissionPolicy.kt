package com.planterior.helper.notification

import android.content.Context
import androidx.core.content.edit

enum class NotificationPermissionAction {
    NOT_REQUIRED,
    GRANTED,
    REQUEST,
    SHOW_SETTINGS_ALTERNATIVE,
}

class NotificationPermissionPreferences(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun requestedBefore(): Boolean = preferences.getBoolean(REQUESTED, false)

    fun markRequested() {
        preferences.edit { putBoolean(REQUESTED, true) }
    }

    internal fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val PREFERENCES = "notification-permission"
        const val REQUESTED = "requested"
    }
}

object NotificationPermissionPolicy {
    fun action(
        sdkInt: Int,
        granted: Boolean,
        requestedBefore: Boolean,
        notificationsEnabled: Boolean = true,
    ): NotificationPermissionAction =
        when {
            sdkInt < 33 && !notificationsEnabled ->
                NotificationPermissionAction.SHOW_SETTINGS_ALTERNATIVE
            sdkInt < 33 -> NotificationPermissionAction.NOT_REQUIRED
            !granted && !requestedBefore -> NotificationPermissionAction.REQUEST
            !granted || !notificationsEnabled ->
                NotificationPermissionAction.SHOW_SETTINGS_ALTERNATIVE
            else -> NotificationPermissionAction.GRANTED
        }
}

data class NotificationCapability(
    val canPostSystemNotification: Boolean,
    val canViewWateringSchedule: Boolean = true,
    val canCompleteWatering: Boolean = true,
    val showInAppDueCare: Boolean = true,
) {
    companion object {
        fun from(permissionGranted: Boolean) =
            NotificationCapability(canPostSystemNotification = permissionGranted)
    }
}
