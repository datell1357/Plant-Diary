package com.planterior.helper.accountdeletion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.DeletionRequestId
import com.planterior.helper.feature.auth.AccountProfileStore
import com.planterior.helper.feature.auth.AccountSessionCache
import com.planterior.helper.feature.auth.AccountSyncRemote
import com.planterior.helper.feature.auth.AccountSyncWriteGate
import com.planterior.helper.feature.auth.AuthAccount
import com.planterior.helper.feature.auth.AuthCoordinator
import com.planterior.helper.feature.auth.AuthProvider
import com.planterior.helper.feature.auth.FirebaseIdentityGateway
import com.planterior.helper.feature.auth.FirestoreAccountSynchronizer
import com.planterior.helper.feature.auth.ProviderProof
import com.planterior.helper.feature.auth.RemoteMiniHome
import com.planterior.helper.feature.auth.RemoteMiniHomeAuthoritativeState
import com.planterior.helper.feature.auth.RemotePlant
import com.planterior.helper.feature.auth.RemoteWateringSchedule
import com.planterior.helper.feature.auth.SyncDomain
import com.planterior.helper.feature.auth.SyncNotAttemptedException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class TerminalAccountDeletionSyncRaceTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    PlanteriorDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `plant snapshot resumed after terminal sign out and purge cannot restore owner rows`() =
        runTest {
            assertTerminalCleanupWinsAgainst(SyncDomain.PLANTS)
        }

    @Test
    fun `watering snapshot resumed after terminal sign out and purge cannot restore owner rows`() =
        runTest {
            assertTerminalCleanupWinsAgainst(SyncDomain.WATERING)
        }

    @Test
    fun `mini home snapshot resumed after terminal sign out and purge cannot restore owner rows`() =
        runTest {
            assertTerminalCleanupWinsAgainst(SyncDomain.MINI_HOME)
        }

    @Test
    fun `plant snapshot applied before terminal cleanup is removed by purge`() = runTest {
        assertSyncWinsBeforeTerminalCleanup(SyncDomain.PLANTS)
    }

    @Test
    fun `watering snapshot applied before terminal cleanup is removed by purge`() = runTest {
        assertSyncWinsBeforeTerminalCleanup(SyncDomain.WATERING)
    }

    @Test
    fun `mini home snapshot applied before terminal cleanup is removed by purge`() = runTest {
        assertSyncWinsBeforeTerminalCleanup(SyncDomain.MINI_HOME)
    }

    @Test
    fun `outbox write that owns gate completes before sign out and purge removes its owner cache`() =
        runTest {
            val identity = SessionIdentity(OWNER)
            val writeGate = AccountSyncWriteGate()
            val mutationStarted = CompletableDeferred<Unit>()
            val releaseMutation = CompletableDeferred<Unit>()
            val outbox =
                OfflineFirstSyncRepository(
                    database,
                    RemoteMutationGateway {
                        mutationStarted.complete(Unit)
                        releaseMutation.await()
                        RemoteMutationResult.Applied(2)
                    },
                )
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        "operation",
                        OWNER,
                        "personalPlants",
                        "plant",
                        "UPDATE",
                        1,
                        "draft",
                        1,
                    )
                )
            database.cacheDao().upsertPlant(CachedPlantEntity(OWNER, "plant", "Plant", null, 1, 1))
            val remote = BlockingRemote(SyncDomain.PLANTS)
            remote.releaseBlockedCall.complete(Unit)
            val synchronizer =
                FirestoreAccountSynchronizer(
                    remote,
                    database,
                    outbox = outbox,
                    writeGate = writeGate,
                )
            val coordinator =
                AuthCoordinator(
                    emptyMap(),
                    identity,
                    AccountProfileStore {},
                    NoOpAccountSessionCache,
                    synchronizer,
                    accountSyncWriteGate = writeGate,
                )
            val signOutAttempted = CompletableDeferred<Unit>()
            val purgeStarted = CompletableDeferred<Unit>()
            val releasePurge = CompletableDeferred<Unit>()
            val sync = async { runCatching { synchronizer.sync(OWNER) } }
            mutationStarted.await()
            val cleanup =
                async(start = CoroutineStart.UNDISPATCHED) {
                    terminalCleanup(
                            coordinator,
                            onSignOutAttempt = { signOutAttempted.complete(Unit) },
                            beforePurge = {
                                purgeStarted.complete(Unit)
                                releasePurge.await()
                            },
                        )
                        .execute(COMMAND)
                }
            signOutAttempted.await()

            releaseMutation.complete(Unit)
            purgeStarted.await()
            assertTrue(database.syncDao().pending(OWNER).isEmpty())
            assertTrue(ownerRowCount(OWNER) > 0)
            releasePurge.complete(Unit)
            cleanup.await()
            sync.await()

            assertEquals(0, ownerRowCount(OWNER))
        }

    private suspend fun assertTerminalCleanupWinsAgainst(blockedDomain: SyncDomain) =
        coroutineScope {
            val identity = SessionIdentity(OWNER)
            val remote = BlockingRemote(blockedDomain)
            val writeGate = AccountSyncWriteGate()
            val synchronizer =
                FirestoreAccountSynchronizer(
                    remote,
                    database,
                    now = { Instant.parse("2026-08-25T00:00:00Z") },
                    writeGate = writeGate,
                )
            val coordinator =
                AuthCoordinator(
                    emptyMap(),
                    identity,
                    AccountProfileStore {},
                    NoOpAccountSessionCache,
                    synchronizer,
                    accountSyncWriteGate = writeGate,
                )
            val cleanup = terminalCleanup(coordinator)
            val sync = async { runCatching { synchronizer.sync(OWNER) } }
            remote.blockedCallStarted.await()

            cleanup.execute(COMMAND)
            remote.releaseBlockedCall.complete(Unit)
            val failure = sync.await().exceptionOrNull()

            assertTrue(failure is SyncNotAttemptedException)
            assertEquals(0, ownerRowCount(OWNER))
        }

    private suspend fun assertSyncWinsBeforeTerminalCleanup(blockedDomain: SyncDomain) =
        coroutineScope {
            val identity = SessionIdentity(OWNER)
            val remote = BlockingRemote(blockedDomain)
            val writeGate = AccountSyncWriteGate()
            val synchronizer =
                FirestoreAccountSynchronizer(
                    remote,
                    database,
                    now = { Instant.parse("2026-08-25T00:00:00Z") },
                    writeGate = writeGate,
                )
            val coordinator =
                AuthCoordinator(
                    emptyMap(),
                    identity,
                    AccountProfileStore {},
                    NoOpAccountSessionCache,
                    synchronizer,
                    accountSyncWriteGate = writeGate,
                )
            val sync = async { synchronizer.sync(OWNER) }
            remote.blockedCallStarted.await()

            remote.releaseBlockedCall.complete(Unit)
            sync.await()
            assertTrue(ownerRowCount(OWNER) > 0)

            terminalCleanup(coordinator).execute(COMMAND)

            assertEquals(0, ownerRowCount(OWNER))
        }

    private fun terminalCleanup(
        coordinator: AuthCoordinator,
        onSignOutAttempt: () -> Unit = {},
        beforePurge: suspend () -> Unit = {},
    ) =
        TerminalAccountDeletionCleanupRuntime(
            InMemoryJournal(),
            TerminalAccountDeletionCleanupActions { phase, command ->
                when (phase) {
                    TerminalCleanupPhase.SIGN_OUT_LOCAL -> {
                        onSignOutAttempt()
                        check(coordinator.completeTerminalAccountDeletion(command.owner.value))
                    }
                    TerminalCleanupPhase.PURGE_ROOM -> {
                        beforePurge()
                        database.terminalAccountDeletionDao().purgeOwner(command.owner.value)
                    }
                    else -> Unit
                }
            },
        )

    private fun ownerRowCount(owner: String): Int {
        val sqlite = database.openHelper.readableDatabase
        val tables =
            sqlite
                .query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'"
                )
                .use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val table = cursor.getString(0)
                            val ownerScoped =
                                sqlite.query("PRAGMA table_info(`$table`)").use { columns ->
                                    val name = columns.getColumnIndexOrThrow("name")
                                    var found = false
                                    while (columns.moveToNext()) {
                                        if (columns.getString(name) == "accountId") found = true
                                    }
                                    found
                                }
                            if (ownerScoped) add(table)
                        }
                    }
                }
        return tables.sumOf { table ->
            sqlite.query("SELECT COUNT(*) FROM `$table` WHERE accountId = ?", arrayOf(owner)).use {
                cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }
    }

    private class BlockingRemote(private val blockedDomain: SyncDomain) : AccountSyncRemote {
        val blockedCallStarted = CompletableDeferred<Unit>()
        val releaseBlockedCall = CompletableDeferred<Unit>()

        override suspend fun plants(accountUid: String): List<RemotePlant> {
            blockIf(SyncDomain.PLANTS)
            return listOf(RemotePlant("plant", "Plant", null, 1, 1))
        }

        override suspend fun wateringSchedules(accountUid: String): List<RemoteWateringSchedule> {
            blockIf(SyncDomain.WATERING)
            return listOf(
                RemoteWateringSchedule("schedule", "plant", "2026-08-26", null, "UTC", 1, 1)
            )
        }

        override suspend fun miniHome(accountUid: String): RemoteMiniHome? =
            miniHomeAuthoritativeState(accountUid).layout

        override suspend fun miniHomeAuthoritativeState(
            accountUid: String
        ): RemoteMiniHomeAuthoritativeState {
            blockIf(SyncDomain.MINI_HOME)
            val home = RemoteMiniHome("home", "Home", 1, 1, 1)
            return RemoteMiniHomeAuthoritativeState(
                generation = 1,
                layout = home,
                operationId = "operation",
                payloadHash = "0".repeat(64),
                tombstoneId = null,
                authoritativeAtEpochMillis = 1,
            )
        }

        override suspend fun verifyDomain(accountUid: String, domain: SyncDomain) = Unit

        private suspend fun blockIf(domain: SyncDomain) {
            if (domain != blockedDomain) return
            blockedCallStarted.complete(Unit)
            releaseBlockedCall.await()
        }
    }

    private class SessionIdentity(uid: String) : FirebaseIdentityGateway {
        private var account: AuthAccount? = AuthAccount(uid, null, null, setOf(AuthProvider.GOOGLE))

        override fun current(): AuthAccount? = account

        override suspend fun signIn(proof: ProviderProof): AuthAccount = error("unused")

        override suspend fun reauthenticate(proof: ProviderProof): AuthAccount = error("unused")

        override suspend fun link(proof: ProviderProof): AuthAccount = error("unused")

        override suspend fun signOut() {
            account = null
        }
    }

    private object NoOpAccountSessionCache : AccountSessionCache {
        override suspend fun clearVisible(accountUid: String?) = Unit

        override fun activate(accountUid: String?) = Unit
    }

    private class InMemoryJournal : TerminalAccountDeletionCleanupJournal {
        private val completed = linkedSetOf<TerminalCleanupPhase>()

        override fun begin(command: TerminalAccountDeletionCleanupCommand) = Unit

        override fun commands(): List<TerminalAccountDeletionCleanupCommand> = listOf(COMMAND)

        override fun completedPhases(
            command: TerminalAccountDeletionCleanupCommand
        ): Set<TerminalCleanupPhase> = completed.toSet()

        override fun markCompleted(
            command: TerminalAccountDeletionCleanupCommand,
            phase: TerminalCleanupPhase,
        ) {
            completed += phase
        }

        override fun finish(command: TerminalAccountDeletionCleanupCommand) = Unit
    }

    private companion object {
        const val OWNER = "owner-one"
        val COMMAND =
            TerminalAccountDeletionCleanupCommand(
                AccountId(OWNER),
                DeletionRequestId("request-one"),
            )
    }
}
