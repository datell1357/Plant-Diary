package com.planterior.helper.notification

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class WateringNotificationResourceTest {
    @Test
    fun `watering notification uses the dedicated transparent monochrome status icon`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(context.getDrawable(R.drawable.ic_stat_watering))
        WateringNotificationRenderer.createChannel(context)

        WateringNotificationRenderer.post(
            context,
            "planterior://collection/plant/plant-a/watering",
            "물 줄 시간이에요",
            "몬스테라 물 주기를 확인해 주세요.",
            42,
        )

        val notification =
            Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
                .allNotifications
                .single()
        assertEquals(R.drawable.ic_stat_watering, notification.smallIcon.resId)
    }
}
