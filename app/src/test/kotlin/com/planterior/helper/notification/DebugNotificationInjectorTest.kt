package com.planterior.helper.notification

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DebugNotificationInjectorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WateringNotificationRenderer.createChannel(context)
    }

    @Test
    fun `debug driver injects a tappable exact watering route`() {
        val injected =
            DebugNotificationInjector.injectIfRequested(
                context,
                Intent().putExtra(DebugNotificationInjector.EXTRA_PLANT_ID, "plant-a"),
            )

        assertTrue(injected)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = Shadows.shadowOf(manager).allNotifications.single()
        val tapIntent = Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, tapIntent.action)
        assertEquals("planterior://collection/plant/plant-a", tapIntent.dataString)
    }

    @Test
    fun `debug driver rejects path shaped plant identifiers`() {
        assertFalse(
            DebugNotificationInjector.injectIfRequested(
                context,
                Intent().putExtra(DebugNotificationInjector.EXTRA_PLANT_ID, "../other-user"),
            )
        )
    }
}
