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
