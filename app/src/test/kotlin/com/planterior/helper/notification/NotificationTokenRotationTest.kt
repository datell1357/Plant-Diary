package com.planterior.helper.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class NotificationTokenRotationTest {
    private lateinit var context: Context
    private lateinit var store: NotificationTokenStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences(NotificationTokenStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = NotificationTokenStore(context)
    }

    @Test
    fun `installation identity and monotonic generation survive recreation`() {
        val installationId = store.installationId()
        store.updateToken("fcm-token-one")
        store.updateCapability(notificationsEnabled = true)
        val first = requireNotNull(store.registrationFor("account-a"))
        store.markRegistered(first)

        store.updateToken("fcm-token-two")
        val rotated = requireNotNull(NotificationTokenStore(context).registrationFor("account-a"))

        assertEquals(installationId, rotated.installationId)
        assertNotEquals(first.generation, rotated.generation)
        assertEquals(first.generation + 1, rotated.generation)
        assertEquals("fcm-token-two", rotated.token)
        assertEquals(true, rotated.notificationsEnabled)
    }

    @Test
    fun `pre-commit and post-commit logout retries reuse one revocation generation`() {
        store.updateToken("fcm-token-one")
        store.updateCapability(notificationsEnabled = true)
        val registered = requireNotNull(store.registrationFor("account-a"))
        store.markRegistered(registered)

        val firstAttempt = store.beginUnregistration("account-a")
        val preCommitRetry = store.beginUnregistration("account-a")
        store.markUnregistered(
            firstAttempt,
            NotificationEndpointRevocationResult.REVOKED,
        )
        val postCommitResponseLossRetry = store.beginUnregistration("account-a")

        assertEquals(registered.generation + 1, firstAttempt.generation)
        assertEquals(firstAttempt, preCommitRetry)
        assertEquals(firstAttempt, postCommitResponseLossRetry)
        assertEquals("fcm-token-one", store.pendingToken())
    }

    @Test
    fun `missing server endpoint resets installation identity for safe account switch`() {
        store.updateToken("fcm-token-one")
        store.updateCapability(notificationsEnabled = true)
        val missing = store.beginUnregistration("account-a")
        val oldInstallationId = missing.installationId
        store.markUnregistered(
            missing,
            NotificationEndpointRevocationResult.ALREADY_ABSENT,
        )

        val accountB = requireNotNull(store.registrationFor("account-b"))

        assertNotEquals(oldInstallationId, accountB.installationId)
        assertEquals(1, accountB.generation)
        assertEquals(accountB.installationSecret, accountB.nextInstallationSecret)
    }

    @Test
    fun `token and capability changes cannot overwrite an unconfirmed revocation generation`() {
        store.updateToken("fcm-token-one")
        store.updateCapability(notificationsEnabled = true)
        val registered = requireNotNull(store.registrationFor("account-a"))
        store.markRegistered(registered)
        val revocation = store.beginUnregistration("account-a")

        store.updateToken("fcm-token-two")
        store.updateCapability(notificationsEnabled = false)

        assertEquals(null, store.registrationFor("account-a"))
        assertEquals(null, store.registrationFor("account-b"))
        assertEquals(revocation, store.beginUnregistration("account-a"))
    }

    @Test
    fun `unresolved registration cannot be overwritten by another account or token change`() {
        store.updateToken("fcm-token-one")
        store.updateCapability(notificationsEnabled = true)
        val accountA = requireNotNull(store.registrationFor("account-a"))

        store.updateToken("fcm-token-two")
        store.updateCapability(notificationsEnabled = false)

        assertEquals(accountA, NotificationTokenStore(context).registrationFor("account-a"))
        assertEquals(null, NotificationTokenStore(context).registrationFor("account-b"))
        store.markRegistered(accountA)
        val refreshed = requireNotNull(store.registrationFor("account-a"))
        assertEquals(accountA.generation + 1, refreshed.generation)
        assertEquals("fcm-token-two", refreshed.token)
        assertEquals(false, refreshed.notificationsEnabled)
    }

    @Test
    fun `pending transfer survives restart and adopts rotated proof before revocation`() {
        store.updateToken("fcm-token-one")
        store.updateCapability(notificationsEnabled = true)
        val accountA = requireNotNull(store.registrationFor("account-a"))
        store.markRegistered(accountA)
        val accountARevocation = store.beginUnregistration("account-a")
        store.markUnregistered(accountARevocation, NotificationEndpointRevocationResult.REVOKED)
        val transfer = requireNotNull(store.registrationFor("account-b"))
        assertNotEquals(transfer.installationSecret, transfer.nextInstallationSecret)

        store.updateToken("fcm-token-two")
        store.updateCapability(notificationsEnabled = false)
        val restarted = NotificationTokenStore(context)

        assertEquals(transfer, restarted.unresolvedRegistrationFor("account-b"))
        assertEquals(transfer, restarted.registrationFor("account-b"))
        assertThrows(IllegalStateException::class.java) {
            restarted.beginUnregistration("account-b")
        }
        restarted.markRegistered(transfer)
        val revocation = restarted.beginUnregistration("account-b")

        assertEquals(transfer.generation + 1, revocation.generation)
        assertEquals(transfer.nextInstallationSecret, revocation.installationSecret)
        assertEquals(transfer.installationId, revocation.installationId)
    }

    @Test
    fun `successful cross-account transfer rotates the installation secret`() {
        store.updateToken("fcm-token-one")
        store.updateCapability(notificationsEnabled = true)
        val accountA = requireNotNull(store.registrationFor("account-a"))
        store.markRegistered(accountA)
        val tombstone = store.beginUnregistration("account-a")
        store.markUnregistered(tombstone, NotificationEndpointRevocationResult.REVOKED)

        val accountB = requireNotNull(store.registrationFor("account-b"))
        assertNotEquals(accountB.installationSecret, accountB.nextInstallationSecret)
        store.markRegistered(accountB)
        store.updateCapability(notificationsEnabled = false)
        val nextPublication = requireNotNull(store.registrationFor("account-b"))

        assertEquals(accountB.nextInstallationSecret, nextPublication.installationSecret)
    }

    @Test
    fun `capability change creates a new generation for resume publication`() {
        store.updateToken("fcm-token-one")
        store.updateCapability(notificationsEnabled = false)
        val denied = requireNotNull(store.registrationFor("account-a"))
        store.markRegistered(denied)

        store.updateCapability(notificationsEnabled = true)
        val granted = requireNotNull(store.registrationFor("account-a"))

        assertEquals(denied.generation + 1, granted.generation)
        assertEquals(true, granted.notificationsEnabled)
    }
}
