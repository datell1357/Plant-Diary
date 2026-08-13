package com.planterior.helper.feature.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountSynchronizerPersistenceTest {
    private lateinit var database: PlanteriorDatabase
    private val account = "account-a"
    private var now = Instant.parse("2026-08-12T12:00:00Z")

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
    fun `successful remote snapshot removes stale rows but preserves draft protected rows`() =
        runTest {
            database.cacheDao().upsertPlant(plant("stale"))
            database.cacheDao().upsertPlant(plant("draft-protected"))
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        "operation-draft",
                        account,
                        "personalPlant",
                        "draft-protected",
                        "UPDATE",
                        1,
                        "local draft",
                        now.toEpochMilli(),
                    )
                )
            val remote =
                FakeRemote(
                    plants =
                        listOf(RemotePlant("remote", "Remote plant", null, 4, now.toEpochMilli()))
                )
            val synchronizer = FirestoreAccountSynchronizer(remote, database, now = { now })

            val summary = synchronizer.sync(account)

            assertEquals(
                listOf("draft-protected", "remote"),
                database.cacheDao().plants(account).map { it.plantId }.sorted(),
            )
            assertEquals("local draft", database.syncDao().pending(account).single().draftPayload)
            assertEquals(SyncStatus.SUCCESS, summary.records.getValue(SyncDomain.PLANTS).status)
        }

    @Test
    fun `conflicted local draft also prevents authoritative deletion`() = runTest {
        database.cacheDao().upsertPlant(plant("conflicted"))
        database
            .syncDao()
            .enqueue(
                OperationOutboxEntity(
                    "operation-conflict",
                    account,
                    "personalPlant",
                    "conflicted",
                    "UPDATE",
                    1,
                    "recoverable",
                    now.toEpochMilli(),
                )
            )
        database.syncDao().markConflict(account, "operation-conflict", 9)
        val synchronizer =
            FirestoreAccountSynchronizer(FakeRemote(plants = emptyList()), database, now = { now })

        synchronizer.sync(account)

        assertNotNull(database.cacheDao().plantBlocking(account, "conflicted"))
        assertEquals("CONFLICT", database.syncDao().pending(account).single().state)
    }

    @Test
    fun `account deactivation hides scope without deleting its cached draft or outbox`() = runTest {
        database.cacheDao().upsertPlant(plant("draft"))
        database
            .syncDao()
            .enqueue(
                OperationOutboxEntity(
                    "operation-draft",
                    account,
                    "personalPlant",
                    "draft",
                    "UPDATE",
                    1,
                    "recoverable",
                    now.toEpochMilli(),
                )
            )
        val repository =
            OfflineFirstSyncRepository(
                database,
                RemoteMutationGateway { RemoteMutationResult.Failed("offline") },
            )
        val cache = RoomAccountSessionCache(repository)
        cache.activate(account)

        cache.clearVisible(account)

        assertNotNull(database.cacheDao().plantBlocking(account, "draft"))
        assertEquals("recoverable", database.syncDao().pending(account).single().draftPayload)
    }

    @Test
    fun `last sync success and partial failure survive a new synchronizer instance`() = runTest {
        val failing = FakeRemote(failures = setOf(SyncDomain.MINI_HOME))
        val first = FirestoreAccountSynchronizer(failing, database, now = { now })

        val partial = first.sync(account)
        now = now.plusSeconds(3600)
        val restored =
            FirestoreAccountSynchronizer(
                    FakeRemote(failures = SyncDomain.entries.toSet()),
                    database,
                    now = { now },
                )
                .lastKnown(account)

        assertEquals(true, partial.isPartial)
        assertEquals(SyncStatus.FAILED, partial.records.getValue(SyncDomain.MINI_HOME).status)
        assertEquals("unavailable", partial.records.getValue(SyncDomain.MINI_HOME).errorCode)
        assertEquals(
            Instant.parse("2026-08-12T12:00:00Z"),
            restored.records.getValue(SyncDomain.PLANTS).attemptedAt,
        )
        assertEquals(SyncStatus.FAILED, restored.records.getValue(SyncDomain.MINI_HOME).status)
    }

    private fun plant(id: String) = CachedPlantEntity(account, id, id, null, 1, now.toEpochMilli())

    private class FakeRemote(
        private val plants: List<RemotePlant> = emptyList(),
        private val schedules: List<RemoteWateringSchedule> = emptyList(),
        private val failures: Set<SyncDomain> = emptySet(),
    ) : AccountSyncRemote {
        override suspend fun plants(accountUid: String): List<RemotePlant> =
            result(SyncDomain.PLANTS, plants)

        override suspend fun wateringSchedules(accountUid: String): List<RemoteWateringSchedule> =
            result(SyncDomain.WATERING, schedules)

        override suspend fun verifyDomain(accountUid: String, domain: SyncDomain) {
            result(domain, Unit)
        }

        private fun <T> result(domain: SyncDomain, value: T): T {
            if (domain in failures) error("offline")
            return value
        }
    }
}
