package com.planterior.helper.analytics

import com.planterior.helper.core.model.AnalyticsConsentState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsConsentCoordinatorTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `grant collects only after exact server acknowledgement and uses exact next revision`() =
        runTest {
            val acknowledgement = CompletableDeferred<AnalyticsConsentAcknowledgement>()
            val remote = FakeRemote(set = { acknowledgement.await() })
            val local = RecordingLocal()
            val coordinator = coordinator(remote, local)
            coordinator.load("owner")

            val grant = async { coordinator.setEnabled(true) }
            runCurrent()

            assertEquals(AnalyticsConsentState.Enabling, coordinator.state.value)
            assertNull(local.authorization)
            assertEquals(1, remote.commands.single().commandGeneration)
            acknowledgement.complete(AnalyticsConsentAcknowledgement(true, 1, false))
            grant.await()

            assertEquals(AnalyticsConsentState.Enabled(1), coordinator.state.value)
            assertEquals(AnalyticsAuthorization("owner", 1), local.authorization)
        }

    @Test
    fun `revoke disables and purges before remote convergence and failed replay keeps operation id`() =
        runTest {
            val revokeStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var fail = true
            val remote =
                FakeRemote(
                    get = { RemoteAnalyticsConsent(true, 4) },
                    set = { command ->
                        revokeStarted.complete(Unit)
                        release.await()
                        if (fail) throw AnalyticsTransportException(IllegalStateException())
                        AnalyticsConsentAcknowledgement(
                            command.granted,
                            command.commandGeneration,
                            true,
                        )
                    },
                )
            val local = RecordingLocal()
            val coordinator = coordinator(remote, local)
            coordinator.load("owner")

            val revoke = async { coordinator.setEnabled(false) }
            revokeStarted.await()

            assertEquals(AnalyticsConsentState.Disabling, coordinator.state.value)
            assertNull(local.authorization)
            assertEquals(listOf("owner"), local.purgedOwners)
            release.complete(Unit)
            revoke.await()
            assertEquals(AnalyticsConsentState.FailedOff, coordinator.state.value)

            val failedOperation = remote.commands.single().operationId
            fail = false
            coordinator.setEnabled(true)

            assertEquals(2, remote.commands.size)
            assertTrue(remote.commands.all { it.operationId == failedOperation })
            assertTrue(remote.commands.all { !it.granted })
            assertEquals(AnalyticsConsentState.Disabled, coordinator.state.value)
            assertNull(local.authorization)
        }

    @Test
    fun `owner switch while grant is in flight cannot authorize either owner`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val remote =
            FakeRemote(
                set = { command ->
                    started.complete(Unit)
                    release.await()
                    AnalyticsConsentAcknowledgement(
                        command.granted,
                        command.commandGeneration,
                        false,
                    )
                }
            )
        val local = RecordingLocal()
        val coordinator = coordinator(remote, local)
        coordinator.load("owner-a")
        val grant = async { coordinator.setEnabled(true) }
        started.await()

        coordinator.clearLocalOwner("owner-a")
        coordinator.load("owner-b")
        release.complete(Unit)
        grant.await()

        assertNull(local.authorization)
        assertEquals(AnalyticsConsentState.Disabled, coordinator.state.value)
    }

    @Test
    fun `deletion received clears matching local consent without a remote command`() = runTest {
        val remote = FakeRemote(get = { RemoteAnalyticsConsent(true, 4) })
        val local = RecordingLocal()
        val coordinator = coordinator(remote, local)
        coordinator.load("owner")

        assertTrue(coordinator.deletionReceived("owner"))
        coordinator.load("owner")

        assertNull(local.authorization)
        assertEquals(AnalyticsConsentState.FailedOff, coordinator.state.value)
        assertEquals(listOf("owner"), local.purgedOwners)
        assertTrue(remote.commands.isEmpty())

        coordinator.retry()
        assertEquals(AnalyticsAuthorization("owner", 4), local.authorization)
        assertEquals(AnalyticsConsentState.Enabled(4), coordinator.state.value)
        assertTrue(remote.commands.isEmpty())
    }

    @Test
    fun `cancelled received cleanup remains fail-safe off`() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val remote = FakeRemote(get = { RemoteAnalyticsConsent(true, 4) })
        val local =
            RecordingLocal(
                beforePurge = {
                    cleanupStarted.complete(Unit)
                    neverCompletes.await()
                }
            )
        val coordinator = coordinator(remote, local)
        coordinator.load("owner")
        val cleanup = async { coordinator.deletionReceived("owner") }

        cleanupStarted.await()
        cleanup.cancelAndJoin()

        assertNull(local.authorization)
        assertEquals(AnalyticsConsentState.FailedOff, coordinator.state.value)
        assertTrue(remote.commands.isEmpty())
    }

    @Test
    fun `stale deletion owner cannot disable the active account`() = runTest {
        val remote = FakeRemote(get = { RemoteAnalyticsConsent(true, 7) })
        val local = RecordingLocal()
        val coordinator = coordinator(remote, local)
        coordinator.load("owner-r2")

        assertTrue(!coordinator.deletionReceived("owner-r1"))

        assertEquals(AnalyticsAuthorization("owner-r2", 7), local.authorization)
        assertEquals(AnalyticsConsentState.Enabled(7), coordinator.state.value)
        assertTrue(local.purgedOwners.isEmpty())
        assertTrue(remote.commands.isEmpty())
    }

    @Test
    fun `loading failure stays visibly off and retry reloads remote state`() = runTest {
        var fails = true
        val remote =
            FakeRemote(
                get = {
                    if (fails) throw AnalyticsTransportException(IllegalStateException())
                    RemoteAnalyticsConsent(true, 7)
                }
            )
        val local = RecordingLocal()
        val coordinator = coordinator(remote, local)

        coordinator.load("owner")
        assertEquals(AnalyticsConsentState.FailedOff, coordinator.state.value)
        assertNull(local.authorization)

        fails = false
        coordinator.retry()
        assertEquals(AnalyticsConsentState.Enabled(7), coordinator.state.value)
    }

    private fun coordinator(remote: FakeRemote, local: RecordingLocal) =
        AnalyticsConsentCoordinator(remote, local) { "operation-fixed" }

    private class RecordingLocal(private val beforePurge: suspend () -> Unit = {}) :
        AnalyticsConsentLocalBoundary {
        var authorization: AnalyticsAuthorization? = null
        val purgedOwners = mutableListOf<String>()

        override fun enable(authorization: AnalyticsAuthorization) {
            this.authorization = authorization
        }

        override fun disableImmediately() {
            authorization = null
        }

        override suspend fun prepareOwner(ownerUid: String) = Unit

        override suspend fun cancelWorkAndPurge(ownerUid: String?) {
            beforePurge()
            if (ownerUid != null) purgedOwners += ownerUid
        }
    }

    private class FakeRemote(
        private val get: suspend (String) -> RemoteAnalyticsConsent = {
            RemoteAnalyticsConsent(false, 0)
        },
        private val set: suspend (AnalyticsConsentCommand) -> AnalyticsConsentAcknowledgement = {
            AnalyticsConsentAcknowledgement(it.granted, it.commandGeneration, false)
        },
    ) : AnalyticsRemoteGateway {
        val commands = mutableListOf<AnalyticsConsentCommand>()

        override suspend fun getConsent(ownerUid: String): RemoteAnalyticsConsent = get(ownerUid)

        override suspend fun setConsent(
            command: AnalyticsConsentCommand
        ): AnalyticsConsentAcknowledgement {
            commands += command
            return set(command)
        }

        override suspend fun recordEvents(
            command: AnalyticsEventBatchCommand
        ): List<AnalyticsEventAcknowledgement> = error("not used")
    }
}
