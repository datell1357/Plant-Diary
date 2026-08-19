package com.planterior.helper.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.feature.auth.AccountSessionCache
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthCoordinator
import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.FirebaseIdentityGateway
import com.planterior.helper.feature.auth.ProviderProof
import com.planterior.helper.feature.auth.SyncSummary
import com.planterior.helper.notification.FirebaseNotificationEndpointGateway
import com.planterior.helper.notification.NotificationEndpointCallable
import com.planterior.helper.notification.NotificationEndpointGateway
import com.planterior.helper.notification.NotificationEndpointRegistration
import com.planterior.helper.notification.NotificationEndpointRevocationResult
import com.planterior.helper.notification.NotificationEndpointUnregistration
import com.planterior.helper.notification.NotificationRegistrationResult
import com.planterior.helper.notification.NotificationRegistrationTask
import com.planterior.helper.notification.NotificationTokenStore
import com.planterior.helper.notification.PermanentNotificationEndpointException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class AuthRuntimeNotificationLogoutTest {
    @Test
    fun `logout waits for blocked transfer replay then revokes with adopted proof`() = runTest {
        val (tokenStore, transfer) = pendingTransfer()
        val gateway = BlockingTransferGateway()
        val identity = RecordingIdentity("account-b")
        var authRemovalObserved = false
        val coordinator =
            logoutCoordinator(
                identity,
                notificationEndpointRevocationAction(tokenStore, gateway) {},
            ) {
                assertTrue(gateway.tombstoned)
                authRemovalObserved = true
            }
        coordinator.restore()

        val logout = async { coordinator.logout() }
        gateway.registrationStarted.await()

        assertEquals("account-b", identity.current()?.uid)
        assertFalse(logout.isCompleted)
        assertTrue(gateway.revocations.isEmpty())

        gateway.allowRegistration.complete(Unit)
        assertEquals(true, logout.await())

        assertEquals(listOf(transfer), gateway.registrations)
        val revocation = gateway.revocations.single()
        assertEquals(transfer.generation + 1, revocation.generation)
        assertEquals(transfer.nextInstallationSecret, revocation.installationSecret)
        assertTrue(gateway.tombstoned)
        assertTrue(authRemovalObserved)
        assertEquals(null, identity.current())
    }

    @Test
    fun `committed transfer response loss replays exact command before logout revocation`() =
        runTest {
            val (originalStore, transfer) = pendingTransfer()
            val restartedStore = NotificationTokenStore(context())
            val gateway = CommittedTransferGateway(transfer)
            val identity = RecordingIdentity("account-b")
            val coordinator =
                logoutCoordinator(
                    identity,
                    notificationEndpointRevocationAction(restartedStore, gateway) {},
                ) {
                    assertTrue(gateway.tombstoned)
                }
            coordinator.restore()

            assertEquals(true, coordinator.logout())

            assertEquals(listOf(transfer), gateway.registrations)
            assertEquals(
                transfer.nextInstallationSecret,
                gateway.revocations.single().installationSecret,
            )
            assertTrue(gateway.tombstoned)
            assertEquals(null, identity.current())
            assertEquals(null, originalStore.unresolvedRegistrationFor("account-b"))
        }

    @Test
    fun `logout tombstone wins race with the original late transfer request`() = runTest {
        val (tokenStore, transfer) = pendingTransfer()
        val gateway = RacingTransferGateway(transfer)
        val original = async {
            runCatching {
                NotificationRegistrationTask(tokenStore, gateway) { "account-b" }.run()
            }
        }
        gateway.originalStarted.await()
        val identity = RecordingIdentity("account-b")
        val coordinator =
            logoutCoordinator(
                identity,
                notificationEndpointRevocationAction(tokenStore, gateway) {},
            ) {
                assertTrue(gateway.tombstoned)
            }
        coordinator.restore()

        assertEquals(true, coordinator.logout())
        gateway.releaseOriginal.complete(Unit)
        val lateResult = original.await()

        assertTrue(lateResult.exceptionOrNull() is PermanentNotificationEndpointException)
        assertTrue(gateway.tombstoned)
        assertFalse(gateway.endpointEnabled)
        assertEquals(
            transfer.nextInstallationSecret,
            gateway.revocations.single().installationSecret,
        )
        assertEquals(null, identity.current())
    }

    @Test
    fun `queued transfer never started does not let prior owner tombstone deadlock logout`() =
        runTest {
            val (tokenStore, tombstone, _) = confirmedPriorOwnerTombstone()
            val gateway = AbsentEndpointGateway()
            val identity = RecordingIdentity("account-b")
            var queuedWorkCancelled = false
            val coordinator =
                logoutCoordinator(
                    identity,
                    notificationEndpointRevocationAction(tokenStore, gateway) {
                        queuedWorkCancelled = true
                    },
                ) {}
            coordinator.restore()

            assertEquals(true, coordinator.logout())

            assertTrue(queuedWorkCancelled)
            assertTrue(gateway.registrations.isEmpty())
            assertTrue(gateway.revocations.isEmpty())
            assertEquals(null, identity.current())
            assertEquals(tombstone, tokenStore.beginUnregistration("account-a"))
        }

    @Test
    fun `restart before queued transfer starts preserves prior tombstone and permits logout`() =
        runTest {
            val (_, tombstone, _) = confirmedPriorOwnerTombstone()
            val restartedStore = NotificationTokenStore(context())
            val gateway = AbsentEndpointGateway()
            val identity = RecordingIdentity("account-b")
            val coordinator =
                logoutCoordinator(
                    identity,
                    notificationEndpointRevocationAction(restartedStore, gateway) {},
                ) {}
            coordinator.restore()

            assertEquals(true, coordinator.logout())

            assertTrue(gateway.registrations.isEmpty())
            assertTrue(gateway.revocations.isEmpty())
            assertEquals(tombstone, restartedStore.beginUnregistration("account-a"))
            assertEquals(null, identity.current())
        }

    @Test
    fun `late stale registration completions after absent logout cannot replace prior tombstone`() =
        runTest {
            val (tokenStore, tombstone, oldAccountARegistration) = confirmedPriorOwnerTombstone()
            val gateway = AbsentEndpointGateway()
            val identity = RecordingIdentity("account-b")
            val coordinator =
                logoutCoordinator(
                    identity,
                    notificationEndpointRevocationAction(tokenStore, gateway) {},
                ) {}
            coordinator.restore()
            assertEquals(true, coordinator.logout())
            val staleAccountBTransfer =
                oldAccountARegistration.copy(
                    accountId = "account-b",
                    installationSecret = tombstone.installationSecret,
                    nextInstallationSecret = "stale-account-b-rotated-secret-1234567890",
                    generation = tombstone.generation + 1,
                )

            val lateWorkerResult =
                NotificationRegistrationTask(tokenStore, gateway) { identity.current()?.uid }.run()
            tokenStore.markRegistered(oldAccountARegistration)
            tokenStore.markRegistered(staleAccountBTransfer)

            assertEquals(NotificationRegistrationResult.NO_SESSION, lateWorkerResult)
            assertEquals(tombstone, tokenStore.beginUnregistration("account-a"))
            assertEquals(null, tokenStore.unresolvedRegistrationFor("account-b"))
            assertTrue(gateway.registrations.isEmpty())
            assertTrue(gateway.revocations.isEmpty())
        }

    @Test
    fun `truly unresolved foreign registration still blocks logout without clearing command`() =
        runTest {
            val tokenStore = clearedTokenStore()
            tokenStore.updateToken("fcm-token")
            tokenStore.updateCapability(true)
            val accountARegistration = requireNotNull(tokenStore.registrationFor("account-a"))
            tokenStore.markRegistered(accountARegistration)
            val unresolvedAccountA = tokenStore.beginUnregistration("account-a")
            val gateway = AbsentEndpointGateway()
            val identity = RecordingIdentity("account-b")
            var queuedWorkCancelled = false
            val coordinator =
                logoutCoordinator(
                    identity,
                    notificationEndpointRevocationAction(tokenStore, gateway) {
                        queuedWorkCancelled = true
                    },
                ) {}
            coordinator.restore()

            assertEquals(false, coordinator.logout())

            assertTrue(queuedWorkCancelled)
            assertEquals("account-b", identity.current()?.uid)
            assertEquals(unresolvedAccountA, tokenStore.beginUnregistration("account-a"))
            assertTrue(gateway.registrations.isEmpty())
            assertTrue(gateway.revocations.isEmpty())
        }

    @Test
    fun `malformed unregister response keeps AuthRuntime session and revocation pending`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context
                .getSharedPreferences(NotificationTokenStore.PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            val tokenStore = NotificationTokenStore(context)
            tokenStore.updateToken("fcm-token")
            tokenStore.updateCapability(true)
            val registration = requireNotNull(tokenStore.registrationFor("account-a"))
            tokenStore.markRegistered(registration)
            val identity = RecordingIdentity()
            var registrationCancelled = false
            var cacheCleared = false
            val malformedCallable = NotificationEndpointCallable { _, _ -> null }
            val coordinator =
                AuthCoordinator(
                    providers = emptyMap(),
                    identity = identity,
                    profiles = {},
                    cache =
                        object : AccountSessionCache {
                            override suspend fun clearVisible(accountUid: String?) {
                                cacheCleared = true
                            }

                            override fun activate(accountUid: String?) = Unit
                        },
                    synchronizer = { SyncSummary.EMPTY },
                    beforeSignOut =
                        notificationEndpointRevocationAction(
                            tokenStore,
                            FirebaseNotificationEndpointGateway(malformedCallable),
                        ) {
                            registrationCancelled = true
                        },
                )
            coordinator.restore()
            cacheCleared = false

            assertEquals(false, coordinator.logout())

            assertTrue(registrationCancelled)
            assertEquals("account-a", identity.current()?.uid)
            assertEquals(0, identity.signOutCalls)
            assertEquals(false, cacheCleared)
            assertEquals(null, tokenStore.registrationFor("account-a"))
            assertNotNull(tokenStore.beginUnregistration("account-a"))
        }

    private fun pendingTransfer(): Pair<NotificationTokenStore, NotificationEndpointRegistration> {
        val (store, _, _) = confirmedPriorOwnerTombstone()
        return store to requireNotNull(store.registrationFor("account-b"))
    }

    private fun confirmedPriorOwnerTombstone():
        Triple<
            NotificationTokenStore,
            NotificationEndpointUnregistration,
            NotificationEndpointRegistration,
        > {
        val store = clearedTokenStore()
        store.updateToken("fcm-token")
        store.updateCapability(true)
        val accountA = requireNotNull(store.registrationFor("account-a"))
        store.markRegistered(accountA)
        val tombstone = store.beginUnregistration("account-a")
        store.markUnregistered(tombstone, NotificationEndpointRevocationResult.REVOKED)
        return Triple(store, tombstone, accountA)
    }

    private fun clearedTokenStore(): NotificationTokenStore {
        val context = context()
        context
            .getSharedPreferences(NotificationTokenStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        return NotificationTokenStore(context)
    }

    private fun logoutCoordinator(
        identity: RecordingIdentity,
        beforeSignOut: suspend (String) -> Unit,
        beforeAuthRemoval: suspend (String) -> Unit,
    ) =
        AuthCoordinator(
            providers = emptyMap(),
            identity = identity,
            profiles = {},
            cache =
                object : AccountSessionCache {
                    override suspend fun clearVisible(accountUid: String?) = Unit

                    override fun activate(accountUid: String?) = Unit
                },
            synchronizer = { SyncSummary.EMPTY },
            beforeSignOut = beforeSignOut,
            beforeAuthRemoval = beforeAuthRemoval,
        )

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private open class CommittedTransferGateway(
        private val expectedTransfer: NotificationEndpointRegistration
    ) : NotificationEndpointGateway {
        val registrations = mutableListOf<NotificationEndpointRegistration>()
        val revocations = mutableListOf<NotificationEndpointUnregistration>()
        var endpointEnabled = true
        var tombstoned = false

        override suspend fun register(registration: NotificationEndpointRegistration) {
            assertEquals(expectedTransfer, registration)
            registrations += registration
            endpointEnabled = true
        }

        override suspend fun unregister(
            unregistration: NotificationEndpointUnregistration
        ): NotificationEndpointRevocationResult {
            revocations += unregistration
            endpointEnabled = false
            tombstoned = true
            return NotificationEndpointRevocationResult.REVOKED
        }
    }

    private class AbsentEndpointGateway : NotificationEndpointGateway {
        val registrations = mutableListOf<NotificationEndpointRegistration>()
        val revocations = mutableListOf<NotificationEndpointUnregistration>()

        override suspend fun register(registration: NotificationEndpointRegistration) {
            registrations += registration
        }

        override suspend fun unregister(
            unregistration: NotificationEndpointUnregistration
        ): NotificationEndpointRevocationResult {
            revocations += unregistration
            return NotificationEndpointRevocationResult.ALREADY_ABSENT
        }
    }

    private class BlockingTransferGateway : NotificationEndpointGateway {
        val registrationStarted = CompletableDeferred<Unit>()
        val allowRegistration = CompletableDeferred<Unit>()
        val registrations = mutableListOf<NotificationEndpointRegistration>()
        val revocations = mutableListOf<NotificationEndpointUnregistration>()
        var tombstoned = false

        override suspend fun register(registration: NotificationEndpointRegistration) {
            registrations += registration
            registrationStarted.complete(Unit)
            allowRegistration.await()
        }

        override suspend fun unregister(
            unregistration: NotificationEndpointUnregistration
        ): NotificationEndpointRevocationResult {
            revocations += unregistration
            tombstoned = true
            return NotificationEndpointRevocationResult.REVOKED
        }
    }

    private class RacingTransferGateway(
        private val expectedTransfer: NotificationEndpointRegistration
    ) : NotificationEndpointGateway {
        val originalStarted = CompletableDeferred<Unit>()
        val releaseOriginal = CompletableDeferred<Unit>()
        val revocations = mutableListOf<NotificationEndpointUnregistration>()
        var endpointEnabled = false
        var tombstoned = false
        private var registrations = 0

        override suspend fun register(registration: NotificationEndpointRegistration) {
            assertEquals(expectedTransfer, registration)
            registrations += 1
            if (registrations == 1) {
                originalStarted.complete(Unit)
                releaseOriginal.await()
                if (tombstoned) {
                    throw PermanentNotificationEndpointException("Transfer generation is stale")
                }
            }
            endpointEnabled = true
        }

        override suspend fun unregister(
            unregistration: NotificationEndpointUnregistration
        ): NotificationEndpointRevocationResult {
            revocations += unregistration
            endpointEnabled = false
            tombstoned = true
            return NotificationEndpointRevocationResult.REVOKED
        }
    }

    private class RecordingIdentity(uid: String = "account-a") : FirebaseIdentityGateway {
        private var account: AuthAccount? =
            AuthAccount(
                uid = uid,
                email = "$uid@example.invalid",
                displayName = uid,
                providers = setOf(AuthProvider.GOOGLE),
            )
        var signOutCalls = 0

        override fun current() = account

        override suspend fun signIn(proof: ProviderProof) = error("not used")

        override suspend fun reauthenticate(proof: ProviderProof) = error("not used")

        override suspend fun link(proof: ProviderProof) = error("not used")

        override suspend fun signOut() {
            signOutCalls += 1
            account = null
        }
    }
}
