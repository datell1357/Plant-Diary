package com.planterior.helper.notification

import android.content.Context
import android.content.Intent

object DebugNotificationInjector {
    fun injectIfRequested(context: Context, intent: Intent): Boolean = false
}
