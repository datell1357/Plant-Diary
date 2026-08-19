package com.planterior.helper.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun `android twelve and below never requests runtime notification permission`() {
        assertEquals(
            NotificationPermissionAction.NOT_REQUIRED,
            NotificationPermissionPolicy.action(
                sdkInt = 32,
                granted = false,
                requestedBefore = false,
            ),
        )
    }

    @Test
    fun `android twelve disabled app notifications expose settings without runtime permission`() {
        assertEquals(
            NotificationPermissionAction.SHOW_SETTINGS_ALTERNATIVE,
            NotificationPermissionPolicy.action(
                sdkInt = 32,
                granted = true,
                requestedBefore = false,
                notificationsEnabled = false,
            ),
        )
        assertEquals(
            NotificationPermissionAction.NOT_REQUIRED,
            NotificationPermissionPolicy.action(
                sdkInt = 32,
                granted = true,
                requestedBefore = false,
                notificationsEnabled = true,
            ),
        )
    }

    @Test
    fun `android thirteen requests once then exposes a non blocking settings alternative`() {
        assertEquals(
            NotificationPermissionAction.REQUEST,
            NotificationPermissionPolicy.action(
                sdkInt = 33,
                granted = false,
                requestedBefore = false,
            ),
        )
        assertEquals(
            NotificationPermissionAction.SHOW_SETTINGS_ALTERNATIVE,
            NotificationPermissionPolicy.action(
                sdkInt = 33,
                granted = false,
                requestedBefore = true,
            ),
        )
        assertEquals(
            NotificationPermissionAction.GRANTED,
            NotificationPermissionPolicy.action(
                sdkInt = 33,
                granted = true,
                requestedBefore = true,
                notificationsEnabled = true,
            ),
        )
        assertEquals(
            NotificationPermissionAction.SHOW_SETTINGS_ALTERNATIVE,
            NotificationPermissionPolicy.action(
                sdkInt = 33,
                granted = true,
                requestedBefore = false,
                notificationsEnabled = false,
            ),
        )
    }

    @Test
    fun `permission denial changes delivery guidance but never schedule or completion capability`() {
        val denied = NotificationCapability.from(permissionGranted = false)

        assertFalse(denied.canPostSystemNotification)
        assertTrue(denied.canViewWateringSchedule)
        assertTrue(denied.canCompleteWatering)
        assertTrue(denied.showInAppDueCare)
    }
}
