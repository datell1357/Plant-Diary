package com.planterior.helper.feature.auth

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.data.OfflineFirstSyncRepository
import com.planterior.helper.core.data.RemoteMutationCommand
import com.planterior.helper.core.data.RemoteMutationGateway
import com.planterior.helper.core.data.RemoteMutationResult
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
            assertTrue(
                requireNotNull(database.cacheDao().plantBlocking(account, "remote")).detailsComplete
            )
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
    fun `account sync retains an authoritative due schedule without notification preferences`() =
        runTest {
            val schedule =
                RemoteWateringSchedule(
                    id = "plant-a",
                    plantId = "plant-a",
                    dueDate = "2026-08-22",
                    reminderTime = null,
                    zoneId = "Asia/Seoul",
                    revision = 1,
                    updatedAtEpochMillis = now.toEpochMilli(),
                    enabled = null,
                )

            FirestoreAccountSynchronizer(
                    FakeRemote(schedules = listOf(schedule)),
                    database,
                    now = { now },
                )
                .sync(account)

            val cached = requireNotNull(database.cacheDao().schedule(account, "plant-a"))
            assertEquals("2026-08-22", cached.dueDate)
            assertNull(cached.reminderTime)
            assertNull(cached.enabled)
        }

    @Test
    fun `session synchronization replays account outbox before authoritative plant snapshot`() =
        runTest {
            val events = mutableListOf<String>()
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        "operation-replay",
                        account,
                        "personalPlants",
                        "plant-a",
                        "UPDATE",
                        1,
                        "{\"location\":\"침실\"}",
                        now.toEpochMilli(),
                    )
                )
            val outbox =
                OfflineFirstSyncRepository(
                    database,
                    RemoteMutationGateway { command: RemoteMutationCommand ->
                        events += "replay:${command.operationId.value}"
                        RemoteMutationResult.Applied(2)
                    },
                )
            val remote =
                object : AccountSyncRemote {
                    override suspend fun plants(accountUid: String): List<RemotePlant> {
                        events += "snapshot"
                        return listOf(RemotePlant("plant-a", "서버 식물", null, 2, now.toEpochMilli()))
                    }

                    override suspend fun wateringSchedules(accountUid: String) =
                        emptyList<RemoteWateringSchedule>()

                    override suspend fun miniHome(accountUid: String): RemoteMiniHome? = null

                    override suspend fun verifyDomain(accountUid: String, domain: SyncDomain) = Unit
                }
            val synchronizer =
                FirestoreAccountSynchronizer(remote, database, outbox = outbox, now = { now })

            val summary = synchronizer.sync(account)

            assertEquals(listOf("replay:operation-replay", "snapshot"), events)
            assertTrue(database.syncDao().pending(account).isEmpty())
            assertEquals(
                "서버 식물",
                database.cacheDao().plantBlocking(account, "plant-a")?.displayName,
            )
            assertEquals(SyncStatus.SUCCESS, summary.records.getValue(SyncDomain.PLANTS).status)
        }

    @Test
    fun `account sync adapter rethrows cancellation and writes no false failure record`() =
        runTest {
            val cancellation = CancellationException("session left")
            val remote =
                object : AccountSyncRemote {
                    override suspend fun plants(accountUid: String): List<RemotePlant> =
                        throw cancellation

                    override suspend fun wateringSchedules(accountUid: String) =
                        emptyList<RemoteWateringSchedule>()

                    override suspend fun miniHome(accountUid: String): RemoteMiniHome? = null

                    override suspend fun verifyDomain(accountUid: String, domain: SyncDomain) = Unit
                }

            try {
                FirestoreAccountSynchronizer(remote, database, now = { now }).sync(account)
                fail("CancellationException expected")
            } catch (error: CancellationException) {
                assertSame(cancellation, error)
            }
            assertNull(database.syncDao().lastSync(account, SyncDomain.PLANTS.name))
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

    @Test
    fun `mini home snapshot is cached so home can render it without the network`() = runTest {
        val remote =
            FakeRemote(miniHome = RemoteMiniHome("home-a", "민지의 미니 식물원", 3, 2, now.toEpochMilli()))
        val synchronizer = FirestoreAccountSynchronizer(remote, database, now = { now })

        val summary = synchronizer.sync(account)

        val cached = database.cacheDao().miniHome(account)
        assertEquals("민지의 미니 식물원", cached?.name)
        assertEquals(3, cached?.placedPlantCount)
        assertEquals("home-a", cached?.miniHomeId)
        assertEquals(SyncStatus.SUCCESS, summary.records.getValue(SyncDomain.MINI_HOME).status)
    }

    @Test
    fun `remote mini home update replaces the cached configuration`() = runTest {
        FirestoreAccountSynchronizer(
                FakeRemote(miniHome = RemoteMiniHome("home-a", "이전 이름", 1, 1, now.toEpochMilli())),
                database,
                now = { now },
            )
            .sync(account)

        FirestoreAccountSynchronizer(
                FakeRemote(miniHome = RemoteMiniHome("home-a", "새 이름", 5, 2, now.toEpochMilli())),
                database,
                now = { now },
            )
            .sync(account)

        assertEquals("새 이름", database.cacheDao().miniHome(account)?.name)
        assertEquals(5, database.cacheDao().miniHome(account)?.placedPlantCount)
    }

    @Test
    fun `remote mini home deletion clears the cached configuration`() = runTest {
        FirestoreAccountSynchronizer(
                FakeRemote(miniHome = RemoteMiniHome("home-a", "사라질 방", 2, 1, now.toEpochMilli())),
                database,
                now = { now },
            )
            .sync(account)
        assertNotNull(database.cacheDao().miniHome(account))

        FirestoreAccountSynchronizer(FakeRemote(miniHome = null), database, now = { now })
            .sync(account)

        assertNull(
            "서버에서 지워진 구성을 계속 보여주면 안 된다",
            database.cacheDao().miniHome(account),
        )
    }

    @Test
    fun `mini home sync failure leaves the previously cached configuration usable`() = runTest {
        FirestoreAccountSynchronizer(
                FakeRemote(miniHome = RemoteMiniHome("home-a", "캐시된 방", 4, 1, now.toEpochMilli())),
                database,
                now = { now },
            )
            .sync(account)

        val summary =
            FirestoreAccountSynchronizer(
                    FakeRemote(failures = setOf(SyncDomain.MINI_HOME)),
                    database,
                    now = { now },
                )
                .sync(account)

        assertEquals("캐시된 방", database.cacheDao().miniHome(account)?.name)
        assertEquals(SyncStatus.FAILED, summary.records.getValue(SyncDomain.MINI_HOME).status)
    }

    @Test
    fun `mini home cache never leaks across accounts`() = runTest {
        FirestoreAccountSynchronizer(
                FakeRemote(miniHome = RemoteMiniHome("home-a", "A의 방", 3, 1, now.toEpochMilli())),
                database,
                now = { now },
            )
            .sync(account)
        FirestoreAccountSynchronizer(
                FakeRemote(miniHome = RemoteMiniHome("home-b", "B의 방", 7, 1, now.toEpochMilli())),
                database,
                now = { now },
            )
            .sync("account-b")

        // 계정별 분할이 기본이다. 두 계정을 번갈아 동기화해도 서로의 방을 덮어쓰지 않는다.
        assertEquals("A의 방", database.cacheDao().miniHome(account)?.name)
        assertEquals("B의 방", database.cacheDao().miniHome("account-b")?.name)

        // 계정 전환 시 이전 계정의 보이는 캐시를 지우면 미니홈피도 함께 사라져야 한다.
        database.cacheDao().clearVisibleAccount(account)

        assertNull(database.cacheDao().miniHome(account))
        assertEquals("B의 방", database.cacheDao().miniHome("account-b")?.name)
    }

    private fun plant(id: String) = CachedPlantEntity(account, id, id, null, 1, now.toEpochMilli())

    private class FakeRemote(
        private val plants: List<RemotePlant> = emptyList(),
        private val schedules: List<RemoteWateringSchedule> = emptyList(),
        private val miniHome: RemoteMiniHome? = null,
        private val failures: Set<SyncDomain> = emptySet(),
    ) : AccountSyncRemote {
        override suspend fun plants(accountUid: String): List<RemotePlant> =
            result(SyncDomain.PLANTS, plants)

        override suspend fun wateringSchedules(accountUid: String): List<RemoteWateringSchedule> =
            result(SyncDomain.WATERING, schedules)

        override suspend fun miniHome(accountUid: String): RemoteMiniHome? =
            result(SyncDomain.MINI_HOME, miniHome)

        override suspend fun verifyDomain(accountUid: String, domain: SyncDomain) {
            result(domain, Unit)
        }

        private fun <T> result(domain: SyncDomain, value: T): T {
            if (domain in failures) error("offline")
            return value
        }
    }
}
