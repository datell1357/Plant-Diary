package com.planterior.helper.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.firebase.functions.FirebaseFunctionsException
import com.planterior.helper.auth.notificationEndpointRevocationAction
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class NotificationRegistrationWorkerTest {
    private lateinit var context: Context
    private lateinit var gateway: RecordingEndpointGateway
    private lateinit var tokenStore: NotificationTokenStore
    private var activeAccountId: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences(NotificationTokenStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        gateway = RecordingEndpointGateway()
        tokenStore = NotificationTokenStore(context)
        tokenStore.updateCapability(true)
        activeAccountId = "account-a"
        val configuration =
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(
                    NotificationWorkerFactory(tokenStore, gateway) { activeAccountId }
                )
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `work manager registers latest token and includes generation capability`() = runTest {
        tokenStore.updateToken("fcm-token-one")
        tokenStore.updateToken("fcm-token-two")
        val workManager = WorkManager.getInstance(context)

        NotificationWorkScheduler(workManager).enqueueTokenRegistration()
        val work =
            workManager
                .getWorkInfosForUniqueWork(NotificationWorkScheduler.TOKEN_REGISTRATION_WORK)
                .get()
                .single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)
        val completed = requireNotNull(workManager.getWorkInfoById(work.id).get())

        assertEquals(WorkInfo.State.SUCCEEDED, completed.state)
        assertEquals("fcm-token-two", gateway.registrations.single().token)
        assertEquals(true, gateway.registrations.single().notificationsEnabled)
        assertTrue(gateway.registrations.single().generation > 0)
        assertEquals(null, tokenStore.registrationFor("account-a"))
    }

    @Test
    fun `only transient endpoint failures retry and permanent failures fail with pending token retained`() =
        runTest {
            tokenStore.updateToken("fcm-token-one")
            gateway.failure = PermanentNotificationEndpointException("permission denied")
            val workManager = WorkManager.getInstance(context)

            NotificationWorkScheduler(workManager).enqueueTokenRegistration()
            val work =
                workManager
                    .getWorkInfosForUniqueWork(NotificationWorkScheduler.TOKEN_REGISTRATION_WORK)
                    .get()
                    .single()
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)

            assertEquals(
                WorkInfo.State.FAILED,
                requireNotNull(workManager.getWorkInfoById(work.id).get()).state,
            )
            assertEquals("fcm-token-one", tokenStore.pendingToken())
            assertFalse(NotificationRegistrationRetryPolicy.shouldRetry(gateway.failure!!, 0))
            assertTrue(
                FirebaseEndpointFailureClassifier.isTransient(
                    FirebaseFunctionsException.Code.UNAVAILABLE
                )
            )
            assertFalse(
                FirebaseEndpointFailureClassifier.isTransient(
                    FirebaseFunctionsException.Code.PERMISSION_DENIED
                )
            )
            assertTrue(
                NotificationRegistrationRetryPolicy.shouldRetry(
                    TransientNotificationEndpointException("unavailable"),
                    0,
                )
            )
            assertFalse(
                NotificationRegistrationRetryPolicy.shouldRetry(
                    TransientNotificationEndpointException("unavailable"),
                    5,
                )
            )
        }

    @Test
    fun `logout drain cancels queued registration before revocation proceeds`() = runTest {
        tokenStore.updateToken("fcm-token-one")
        val workManager = WorkManager.getInstance(context)
        val scheduler = NotificationWorkScheduler(workManager)
        scheduler.enqueueTokenRegistration()
        val work =
            workManager
                .getWorkInfosForUniqueWork(NotificationWorkScheduler.TOKEN_REGISTRATION_WORK)
                .get()
                .single()

        scheduler.cancelTokenRegistration()

        assertEquals(
            WorkInfo.State.CANCELLED,
            requireNotNull(workManager.getWorkInfoById(work.id).get()).state,
        )
        assertTrue(gateway.registrations.isEmpty())
    }

    @Test
    fun `network constrained transfer is cancelled before absent account logout completes`() =
        runTest {
            tokenStore.updateToken("fcm-token-one")
            val accountA = requireNotNull(tokenStore.registrationFor("account-a"))
            tokenStore.markRegistered(accountA)
            val tombstone = tokenStore.beginUnregistration("account-a")
            tokenStore.markUnregistered(tombstone, NotificationEndpointRevocationResult.REVOKED)
            activeAccountId = "account-b"
            val workManager = WorkManager.getInstance(context)
            val scheduler = NotificationWorkScheduler(workManager)
            scheduler.enqueueTokenRegistration()
            val work =
                workManager
                    .getWorkInfosForUniqueWork(NotificationWorkScheduler.TOKEN_REGISTRATION_WORK)
                    .get()
                    .single()
            assertEquals(WorkInfo.State.ENQUEUED, work.state)

            notificationEndpointRevocationAction(tokenStore, gateway) {
                scheduler.cancelTokenRegistration()
            }("account-b")

            assertEquals(
                WorkInfo.State.CANCELLED,
                requireNotNull(workManager.getWorkInfoById(work.id).get()).state,
            )
            assertTrue(gateway.registrations.isEmpty())
            assertTrue(gateway.revocations.isEmpty())
            assertEquals(tombstone, tokenStore.beginUnregistration("account-a"))
        }

    @Test
    fun `registration request uses bounded exponential backoff`() {
        val request = NotificationWorkScheduler.registrationRequest()

        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
    }

    private class RecordingEndpointGateway : NotificationEndpointGateway {
        val registrations = mutableListOf<NotificationEndpointRegistration>()
        val revocations = mutableListOf<NotificationEndpointUnregistration>()
        var failure: RuntimeException? = null

        override suspend fun register(registration: NotificationEndpointRegistration) {
            failure?.let { throw it }
            registrations += registration
        }

        override suspend fun unregister(
            unregistration: NotificationEndpointUnregistration
        ): NotificationEndpointRevocationResult {
            revocations += unregistration
            return NotificationEndpointRevocationResult.REVOKED
        }
    }
}
