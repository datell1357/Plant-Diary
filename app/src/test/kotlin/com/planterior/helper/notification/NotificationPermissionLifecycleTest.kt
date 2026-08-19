package com.planterior.helper.notification

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationPermissionLifecycleTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        NotificationPermissionPreferences(application).clear()
        Shadows.shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `request marker survives recreation and changes the denied CTA to settings`() {
        val preferences = NotificationPermissionPreferences(application)
        assertEquals(false, preferences.requestedBefore())

        preferences.markRequested()

        assertEquals(true, NotificationPermissionPreferences(application).requestedBefore())
        assertEquals(
            NotificationPermissionAction.SHOW_SETTINGS_ALTERNATIVE,
            NotificationPermissionPolicy.action(36, granted = false, requestedBefore = true),
        )
    }

    @Test
    fun `activity refreshes permission policy when returning from system settings`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(NotificationPermissionAction.REQUEST, activity.notificationPermissionAction)

        NotificationPermissionPreferences(activity).markRequested()
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller.pause().resume()

        assertEquals(NotificationPermissionAction.GRANTED, activity.notificationPermissionAction)
        assertEquals(true, NotificationTokenStore(activity).notificationsEnabled())
        controller.destroy()
    }
}
