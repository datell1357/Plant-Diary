package com.planterior.helper.notification

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationCapabilityPublisherTest {
    @Test
    fun `endpoint capability requires both runtime permission and app notification enablement`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val shadowManager = Shadows.shadowOf(manager)

        Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowManager.setNotificationsEnabled(true)
        assertFalse(NotificationCapabilityPublisher.notificationsEnabled(context))

        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowManager.setNotificationsEnabled(false)
        assertFalse(NotificationCapabilityPublisher.notificationsEnabled(context))

        shadowManager.setNotificationsEnabled(true)
        assertTrue(NotificationCapabilityPublisher.notificationsEnabled(context))
    }
}
