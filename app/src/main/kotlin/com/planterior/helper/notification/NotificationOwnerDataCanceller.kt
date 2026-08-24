package com.planterior.helper.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat

fun interface NotificationOwnerDataCanceller {
    fun cancelFormerOwnerNotifications()
}

class SystemNotificationOwnerDataCanceller(context: Context) : NotificationOwnerDataCanceller {
    private val applicationContext = context.applicationContext

    override fun cancelFormerOwnerNotifications() {
        NotificationManagerCompat.from(applicationContext).cancelAll()
    }
}

class LocalNotificationOwnerStateCleaner(context: Context) {
    private val applicationContext = context.applicationContext

    fun clear() {
        SystemNotificationOwnerDataCanceller(applicationContext).cancelFormerOwnerNotifications()
        NotificationTokenStore(applicationContext).clearLocalState()
        NotificationOpenConfirmationStore(applicationContext).clearLocalState()
        WeatherNotificationIdentityRegistry(applicationContext).clearLocalState()
    }
}
