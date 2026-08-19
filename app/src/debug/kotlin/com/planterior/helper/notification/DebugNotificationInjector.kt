package com.planterior.helper.notification

import android.content.Context
import android.content.Intent

object DebugNotificationInjector {
    const val EXTRA_PLANT_ID = "planterior.debug.notificationPlantId"

    fun injectIfRequested(context: Context, intent: Intent): Boolean {
        val plantId = intent.getStringExtra(EXTRA_PLANT_ID) ?: return false
        if (!plantId.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) return false
        return WateringNotificationRenderer.post(
            context,
            "planterior://collection/plant/$plantId",
            "디버그 식물 물 주기",
            "QA용 예정일 알림입니다.",
            plantId.hashCode(),
        )
    }
}
