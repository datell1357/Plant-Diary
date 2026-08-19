package com.planterior.helper

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.navigation.toRoute
import com.planterior.helper.navigation.PlanteriorRoute
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MainActivityNotificationIntentTest {
    private val wateringUri =
        "planterior://collection/plant/deleted-plant?deliveryId=123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `cold logged-out notification preserves the exact deleted target for post-login resume`() {
        val controller =
            Robolectric.buildActivity(MainActivity::class.java, wateringIntent()).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val route =
            controller
                .get()
                .navigationController
                .currentBackStackEntry!!
                .toRoute<PlanteriorRoute.Login>()

        assertEquals(wateringUri, route.returnRoute)
        controller.destroy()
    }

    @Test
    fun `warm notification replaces the stack with logged-out exact return`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        controller.newIntent(wateringIntent())
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val route =
            controller
                .get()
                .navigationController
                .currentBackStackEntry!!
                .toRoute<PlanteriorRoute.Login>()

        assertEquals(wateringUri, route.returnRoute)
        controller.destroy()
    }

    @Test
    fun `notification arriving before navigation is ready is queued and consumed once`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()

        controller.newIntent(wateringIntent())
        assertEquals(1, controller.get().pendingNotificationIntentCount)
        controller.start().resume().visible()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(0, controller.get().pendingNotificationIntentCount)
        val route =
            controller
                .get()
                .navigationController
                .currentBackStackEntry!!
                .toRoute<PlanteriorRoute.Login>()
        assertEquals(wateringUri, route.returnRoute)
        controller.destroy()
    }

    private fun wateringIntent() =
        Intent(Intent.ACTION_VIEW, wateringUri.toUri())
            .setClassName(
                "com.planterior.helper",
                MainActivity::class.java.name,
            )
}
