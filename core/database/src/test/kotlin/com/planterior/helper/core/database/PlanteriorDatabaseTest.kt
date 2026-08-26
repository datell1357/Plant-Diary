package com.planterior.helper.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.model.AccountId
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanteriorDatabaseTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    PlanteriorDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `cache is partitioned by account and switching clears prior account view`() = runTest {
        val dao = database.cacheDao()
        dao.upsertPlant(CachedPlantEntity("account-a", "plant-a", "A 몬스테라", null, 1, 1))
        dao.upsertPlant(CachedPlantEntity("account-b", "plant-b", "B 선인장", null, 1, 1))
        assertEquals(listOf("plant-a"), dao.observePlants("account-a").first().map { it.plantId })
        dao.clearVisibleAccount("account-a")
        assertTrue(dao.observePlants("account-a").first().isEmpty())
        assertEquals(listOf("plant-b"), dao.observePlants("account-b").first().map { it.plantId })
    }

    @Test
    fun `outbox is ordered and duplicate operation merges without reordering`() = runTest {
        val dao = database.syncDao()
        dao.enqueue(
            OperationOutboxEntity("op-b", "account-a", "plant", "p2", "UPDATE", 2, "draft-b", 20)
        )
        dao.enqueue(
            OperationOutboxEntity("op-a", "account-a", "plant", "p1", "UPDATE", 1, "draft-a", 10)
        )
        dao.enqueue(
            OperationOutboxEntity("op-a", "account-a", "plant", "p1", "UPDATE", 1, "draft-new", 30)
        )
        val pending = dao.pending("account-a")
        assertEquals(listOf("op-a", "op-b"), pending.map { it.operationId })
        assertEquals("draft-new", pending.first().draftPayload)
        assertEquals(10, pending.first().createdAtEpochMillis)
    }

    @Test
    fun `mini home outbox persists reconciliation phase reason details and receipt identity`() =
        runTest {
            val dao = database.syncDao()
            dao.enqueue(
                OperationOutboxEntity(
                    "mini-home-operation",
                    "account-a",
                    "miniHomeLayouts",
                    "home-a",
                    "REPLACE",
                    7,
                    "canonical-payload",
                    10,
                    payloadHash = "b".repeat(64),
                )
            )

            dao.markMayHaveCommitted(
                "account-a",
                "mini-home-operation",
                "NETWORK",
                "callable response unavailable",
            )
            dao.recordCommittedReceipt(
                "account-a",
                "mini-home-operation",
                "mini-home-operation",
                7,
                8,
                "b".repeat(64),
            )
            var restored = requireNotNull(dao.operation("account-a", "mini-home-operation"))
            assertEquals("MAY_HAVE_COMMITTED", restored.state)
            assertEquals("NETWORK", restored.lastErrorCode)
            assertEquals("callable response unavailable", restored.failureDetails)
            assertEquals("mini-home-operation", restored.committedOperationId)
            assertEquals(7L, restored.committedExpectedRevision)
            assertEquals(8L, restored.committedRevision)
            assertEquals("b".repeat(64), restored.payloadHash)
            assertEquals("b".repeat(64), restored.committedPayloadHash)
            assertEquals("canonical-payload", restored.draftPayload)

            dao.markReconciliationRequired(
                "account-a",
                "mini-home-operation",
                "OUTBOX_MISMATCH",
                "authoritative receipt does not match payload",
                8,
                "different-operation",
                7,
                8,
                "c".repeat(64),
            )
            restored = requireNotNull(dao.operation("account-a", "mini-home-operation"))
            assertEquals("RECONCILIATION_REQUIRED", restored.state)
            assertEquals("OUTBOX_MISMATCH", restored.lastErrorCode)
            assertEquals("authoritative receipt does not match payload", restored.failureDetails)
            assertEquals("different-operation", restored.committedOperationId)
            assertEquals("b".repeat(64), restored.payloadHash)
            assertEquals("c".repeat(64), restored.committedPayloadHash)
        }

    @Test
    fun `mini home lineage removal is atomic owner scoped and preserves unrelated operations`() =
        runTest {
            val dao = database.syncDao()
            listOf(
                    OperationOutboxEntity(
                        "lineage-root",
                        "account-a",
                        "miniHomeLayouts",
                        "home-a",
                        "REPLACE",
                        1,
                        "old-invalid",
                        1,
                        lineageId = "lineage-root",
                    ),
                    OperationOutboxEntity(
                        "lineage-successor",
                        "account-a",
                        "miniHomeLayouts",
                        "home-a",
                        "REPLACE",
                        1,
                        "corrected",
                        2,
                        lineageId = "lineage-root",
                        supersedesOperationId = "lineage-root",
                    ),
                    OperationOutboxEntity(
                        "unrelated-mini-home",
                        "account-a",
                        "miniHomeLayouts",
                        "home-a",
                        "REPLACE",
                        1,
                        "unrelated",
                        3,
                        lineageId = "unrelated-mini-home",
                    ),
                    OperationOutboxEntity(
                        "lineage-root",
                        "account-b",
                        "miniHomeLayouts",
                        "home-b",
                        "REPLACE",
                        1,
                        "other-owner",
                        4,
                        lineageId = "lineage-root",
                    ),
                    OperationOutboxEntity(
                        "other-domain",
                        "account-a",
                        "personalPlants",
                        "plant-a",
                        "UPDATE",
                        1,
                        "other-domain",
                        5,
                        lineageId = "lineage-root",
                    ),
                )
                .forEach { dao.enqueue(it) }

            dao.removeLineage("account-a", "miniHomeLayouts", "lineage-root")

            assertNull(dao.operation("account-a", "lineage-root"))
            assertNull(dao.operation("account-a", "lineage-successor"))
            assertNotNull(dao.operation("account-a", "unrelated-mini-home"))
            assertNotNull(dao.operation("account-b", "lineage-root"))
            assertNotNull(dao.operation("account-a", "other-domain"))
        }

    @Test
    fun `persisted discard anchor accepts malformed identity and cannot cross owner type or changed lineage`() =
        runTest {
            val dao = database.syncDao()
            listOf(
                    OperationOutboxEntity(
                        "malformed/operation",
                        "account-a",
                        "miniHomeLayouts",
                        "home-a",
                        "REPLACE",
                        1,
                        "{bad-json",
                        2,
                        lineageId = "malformed/lineage",
                    ),
                    OperationOutboxEntity(
                        "related-operation",
                        "account-a",
                        "miniHomeLayouts",
                        "home-a",
                        "REPLACE",
                        1,
                        "{bad-json-2",
                        1,
                        lineageId = "malformed/lineage",
                    ),
                    OperationOutboxEntity(
                        "malformed/operation",
                        "account-b",
                        "miniHomeLayouts",
                        "home-b",
                        "REPLACE",
                        1,
                        "{foreign",
                        3,
                        lineageId = "malformed/lineage",
                    ),
                    OperationOutboxEntity(
                        "other-type-operation",
                        "account-a",
                        "personalPlants",
                        "plant-a",
                        "UPDATE",
                        1,
                        "other",
                        4,
                        lineageId = "malformed/lineage",
                    ),
                )
                .forEach { dao.enqueue(it) }
            val rowHandleId =
                requireNotNull(dao.operation("account-a", "malformed/operation")).rowHandleId

            assertTrue(
                dao.discardPersistedOperation(
                    "account-a",
                    "miniHomeLayouts",
                    "malformed/operation",
                    "stale-lineage",
                    rowHandleId,
                    0,
                ) is PersistedOperationDiscardResult.Stale
            )
            assertNotNull(dao.operation("account-a", "malformed/operation"))

            assertEquals(
                PersistedOperationDiscardResult.Consumed(2),
                dao.discardPersistedOperation(
                    "account-a",
                    "miniHomeLayouts",
                    "malformed/operation",
                    "malformed/lineage",
                    rowHandleId,
                    0,
                ),
            )
            assertNull(dao.operation("account-a", "malformed/operation"))
            assertNull(dao.operation("account-a", "related-operation"))
            assertNotNull(dao.operation("account-b", "malformed/operation"))
            assertNotNull(dao.operation("account-a", "other-type-operation"))
        }

    @Test
    fun `persisted handle rejects stale generation after delete and reinsert ABA`() = runTest {
        val dao = database.syncDao()
        val original =
            OperationOutboxEntity(
                "aba-operation",
                "account-a",
                "miniHomeLayouts",
                "home-a",
                "REPLACE",
                1,
                "{bad-json",
                1,
                lineageId = "aba-lineage",
                rowHandleId = "aba-generation-1",
            )
        dao.enqueue(original)
        val handleRow =
            requireNotNull(
                dao.operationByHandle(
                    "account-a",
                    "miniHomeLayouts",
                    "aba-operation",
                    "aba-lineage",
                    "aba-generation-1",
                )
            )
        assertEquals(original.rowHandleId, handleRow.rowHandleId)
        dao.remove("account-a", "aba-operation")
        dao.enqueue(original.copy(rowHandleId = "aba-generation-2"))

        assertTrue(
            dao.discardPersistedOperation(
                "account-a",
                "miniHomeLayouts",
                "aba-operation",
                "aba-lineage",
                "aba-generation-1",
                0,
            ) is PersistedOperationDiscardResult.Stale
        )
        assertEquals(
            "aba-generation-2",
            dao.operation("account-a", "aba-operation")?.rowHandleId,
        )
    }

    @Test
    fun `full persisted handle CAS rejects ABA replacement for every mutable outbox field`() =
        runTest {
            val dao = database.syncDao()
            val original =
                OperationOutboxEntity(
                    "cas-operation",
                    "account-a",
                    "miniHomeLayouts",
                    "home-a",
                    "REPLACE",
                    3,
                    "original-payload",
                    1,
                    payloadHash = null,
                    lineageId = "cas-lineage",
                    rowHandleId = "cas-generation-1",
                    rowVersion = 0,
                )
            dao.enqueue(original)
            val first =
                dao.compareAndSetOperation(
                    original,
                    original.copy(
                        state = "MAY_HAVE_COMMITTED",
                        payloadHash = "a".repeat(64),
                        lastErrorCode = "NETWORK",
                        failureDetails = "first mutation",
                    ),
                ) as OperationOutboxCompareAndSetResult.Updated
            assertEquals(1L, first.operation.rowVersion)

            dao.remove("account-a", "cas-operation")
            val replacement =
                original.copy(
                    draftPayload = "replacement-payload",
                    state = "RECONCILIATION_REQUIRED",
                    payloadHash = "b".repeat(64),
                    committedOperationId = "replacement-receipt",
                    committedExpectedRevision = 9,
                    committedRevision = 10,
                    committedPayloadHash = "c".repeat(64),
                    lastErrorCode = "PAYLOAD_MISMATCH",
                    failureDetails = "replacement details",
                    rowHandleId = "cas-generation-2",
                    rowVersion = 0,
                )
            dao.enqueue(replacement)

            val stale =
                dao.compareAndSetOperation(
                    first.operation,
                    first.operation.copy(
                        state = "PENDING",
                        payloadHash = "d".repeat(64),
                        committedOperationId = "stale-receipt",
                        committedExpectedRevision = 3,
                        committedRevision = 4,
                        committedPayloadHash = "e".repeat(64),
                        lastErrorCode = null,
                        failureDetails = null,
                    ),
                )

            assertTrue(stale is OperationOutboxCompareAndSetResult.Stale)
            assertEquals(replacement, dao.operation("account-a", "cas-operation"))
        }

    @Test
    fun `typed persisted discard distinguishes consumed stale duplicate and rejected handles`() =
        runTest {
            val dao = database.syncDao()
            val original =
                OperationOutboxEntity(
                    "typed-discard",
                    "account-a",
                    "miniHomeLayouts",
                    "home-a",
                    "REPLACE",
                    3,
                    "payload",
                    1,
                    lineageId = "typed-lineage",
                    rowHandleId = "typed-generation-1",
                    rowVersion = 4,
                )
            dao.enqueue(original)

            assertTrue(
                dao.discardPersistedOperation(
                    "account-a",
                    "personalPlants",
                    "typed-discard",
                    "typed-lineage",
                    "typed-generation-1",
                    4,
                ) is PersistedOperationDiscardResult.Rejected
            )
            dao.remove("account-a", "typed-discard")
            val replacement =
                original.copy(
                    draftPayload = "replacement",
                    rowHandleId = "typed-generation-2",
                    rowVersion = 0,
                )
            dao.enqueue(replacement)
            val stale =
                dao.discardPersistedOperation(
                    "account-a",
                    "miniHomeLayouts",
                    "typed-discard",
                    "typed-lineage",
                    "typed-generation-1",
                    4,
                )
            assertTrue(stale is PersistedOperationDiscardResult.Stale)
            assertEquals(replacement, (stale as PersistedOperationDiscardResult.Stale).current)

            val consumed =
                dao.discardPersistedOperation(
                    "account-a",
                    "miniHomeLayouts",
                    "typed-discard",
                    "typed-lineage",
                    "typed-generation-2",
                    0,
                )
            assertEquals(PersistedOperationDiscardResult.Consumed(1), consumed)
            assertEquals(
                PersistedOperationDiscardResult.Missing,
                dao.discardPersistedOperation(
                    "account-a",
                    "miniHomeLayouts",
                    "typed-discard",
                    "typed-lineage",
                    "typed-generation-2",
                    0,
                ),
            )
        }

    @Test
    fun `same operation id persists independently for two accounts`() = runTest {
        val dao = database.syncDao()
        dao.enqueue(
            OperationOutboxEntity(
                "shared-operation",
                "account-a",
                "plant",
                "p-a",
                "UPDATE",
                0,
                "draft-a",
                1,
            )
        )
        dao.enqueue(
            OperationOutboxEntity(
                "shared-operation",
                "account-b",
                "plant",
                "p-b",
                "UPDATE",
                0,
                "draft-b",
                2,
            )
        )

        assertEquals("draft-a", dao.pending("account-a").single().draftPayload)
        assertEquals("draft-b", dao.pending("account-b").single().draftPayload)
    }

    @Test
    fun `last sync and LocalDate schedule round trip exactly`() = runTest {
        val cache = database.cacheDao()
        val sync = database.syncDao()
        cache.upsertSchedule(
            CachedWateringScheduleEntity(
                "account-a",
                "schedule-a",
                "plant-a",
                "2026-08-12",
                "09:00",
                "Asia/Seoul",
                4,
                1,
            )
        )
        sync.upsertLastSync(
            LastSyncEntity("account-a", "plants", 1_786_500_000_000, "SUCCESS", null)
        )
        assertEquals(
            LocalDate.of(2026, 8, 12),
            cache.schedule("account-a", "schedule-a")?.dueLocalDate,
        )
        assertEquals(1_786_500_000_000, sync.lastSync("account-a", "plants")?.syncedAtEpochMillis)
    }

    @Test
    fun `mini home cache is partitioned by account and cleared with the account view`() = runTest {
        val dao = database.cacheDao()
        dao.upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "민지의 미니 식물원", 3, 2, 10))
        dao.upsertMiniHome(CachedMiniHomeEntity("account-b", "home-b", "다른 사람의 방", 9, 1, 20))

        assertEquals("민지의 미니 식물원", dao.miniHome("account-a")?.name)
        assertEquals(3, dao.miniHome("account-a")?.placedPlantCount)
        assertEquals("다른 사람의 방", dao.miniHome("account-b")?.name)

        dao.clearVisibleAccount("account-a")

        assertNull("계정 캐시를 비우면 미니홈피도 사라져야 한다", dao.miniHome("account-a"))
        assertEquals("다른 계정은 그대로여야 한다", "다른 사람의 방", dao.miniHome("account-b")?.name)
    }

    @Test
    fun `mini home reconcile applies remote updates and removes remote deletions`() = runTest {
        val dao = database.cacheDao()
        dao.upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "이전 이름", 1, 1, 10))

        dao.applyAuthoritativeMiniHome(
            AuthoritativeMiniHomeCacheWrite.Layout(
                "account-a",
                2,
                "operation-update",
                "a".repeat(64),
                CachedMiniHomeEntity("account-a", "home-a", "새 이름", 4, 2, 20),
                emptyList(),
            )
        )
        assertEquals("새 이름", dao.miniHome("account-a")?.name)
        assertEquals(4, dao.miniHome("account-a")?.placedPlantCount)

        dao.applyAuthoritativeMiniHome(
            AuthoritativeMiniHomeCacheWrite.Deletion(
                "account-a",
                3,
                "deletion-operation",
                30,
            )
        )
        assertNull(dao.miniHome("account-a"))
    }

    @Test
    fun `mini home survives reopening the database file`() = runTest {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getDatabasePath("persisted.db").path)
        file.delete()

        val first =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "persisted.db")
                .allowMainThreadQueries()
                .build()
        first
            .cacheDao()
            .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "저장된 방", 2, 1, 30))
        first.close()

        // 프로세스가 죽었다 살아나는 경우를 흑낸다. 다시 열어도 같은 구성이 남아 있어야 한다.
        val second =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "persisted.db")
                .allowMainThreadQueries()
                .build()
        assertEquals("저장된 방", second.cacheDao().miniHome("account-a")?.name)
        assertEquals(2, second.cacheDao().miniHome("account-a")?.placedPlantCount)
        second.close()
        file.delete()

        database =
            Room.inMemoryDatabaseBuilder(context, PlanteriorDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @Test
    fun `migration three to twelve runs on a real version three database and keeps every row`() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = createVersion3Database(context, MIGRATION_3_4_DB)

        // 마이그레이션 전에 진짜 v3인지 먼저 확인한다. 이게 없으면 테스트가 헛돈다이 통과할 수 있다.
        assertEquals("마이그레이션 전 user_version은 3이어야 한다", 3, readUserVersion(file))
        assertFalse(
            "v3에는 cached_mini_homes가 없어야 한다",
            readTableNames(file).contains("cached_mini_homes"),
        )

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, MIGRATION_3_4_DB)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()

        runTest {
            // 1. 이전 버전의 모든 행이 그대로 남아야 한다.
            assertEquals(
                listOf("plant-a1", "plant-a2"),
                migrated.cacheDao().plants("account-a").map { it.plantId }.sorted(),
            )
            assertEquals(
                "몬몬이",
                migrated.cacheDao().plant("account-a", "plant-a1")?.displayName,
            )
            assertEquals(
                "MANUAL",
                migrated.cacheDao().plant("account-a", "plant-a1")?.registrationMethod,
            )
            assertNull(migrated.cacheDao().plant("account-a", "plant-a1")?.location)
            assertNull(migrated.cacheDao().plant("account-a", "plant-a1")?.note)
            assertNull(migrated.cacheDao().plant("account-a", "plant-a1")?.lastWateredDate)
            assertFalse(
                "v4 rows did not contain complete detail fields",
                requireNotNull(migrated.cacheDao().plant("account-a", "plant-a1")).detailsComplete,
            )
            assertEquals(
                listOf("plant-b1"),
                migrated.cacheDao().plants("account-b").map { it.plantId },
            )
            assertEquals(
                LocalDate.of(2026, 8, 12),
                migrated.cacheDao().schedule("account-a", "schedule-a1")?.dueLocalDate,
            )
            assertEquals(
                "America/Los_Angeles",
                migrated.cacheDao().schedule("account-a", "schedule-a2")?.zoneId,
            )
            assertEquals(
                listOf("operation-a1", "operation-a2", "operation-a3"),
                migrated.syncDao().pending("account-a").map { it.operationId },
            )
            assertEquals(
                "남아야 하는 draft",
                migrated.syncDao().pending("account-a").first().draftPayload,
            )
            val migratedReconciliation =
                requireNotNull(migrated.syncDao().operation("account-a", "operation-a3"))
            assertEquals("RECONCILIATION_REQUIRED", migratedReconciliation.state)
            assertEquals("OUTBOX_MISMATCH", migratedReconciliation.lastErrorCode)
            assertNull(migratedReconciliation.failureDetails)
            assertNull(migratedReconciliation.committedOperationId)
            assertEquals("operation-a3", migratedReconciliation.lineageId)
            assertNull(migratedReconciliation.supersedesOperationId)
            assertTrue(migratedReconciliation.rowHandleId.isNotBlank())
            assertEquals(
                1_786_500_000_000,
                migrated.syncDao().lastSync("account-a", "PLANTS")?.syncedAtEpochMillis,
            )

            // 2. 새 테이블은 정직하게 비어 있어야 한다. 임의의 기본 행을 만들지 않는다.
            assertNull(migrated.cacheDao().miniHome("account-a"))
            assertNull(migrated.cacheDao().miniHome("account-b"))

            // 3. 새 테이블에 쓰고 읽을 수 있고 계정별로 격리되어야 한다.
            migrated
                .cacheDao()
                .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "A의 방", 3, 1, 20))
            migrated
                .cacheDao()
                .upsertMiniHome(CachedMiniHomeEntity("account-b", "home-b", "B의 방", 7, 1, 21))
            assertEquals("A의 방", migrated.cacheDao().miniHome("account-a")?.name)
            assertEquals("B의 방", migrated.cacheDao().miniHome("account-b")?.name)
            assertEquals(3, migrated.cacheDao().miniHome("account-a")?.placedPlantCount)
        }
        migrated.close()

        // 4. 모든 마이그레이션이 실행되어 현재 스키마 버전까지 올라가야 한다.
        assertEquals("마이그레이션 후 user_version은 21이어야 한다", 21, readUserVersion(file))
        assertMatchesVersion14Schema(file)

        // 5. 닫았다 다시 열어도 내용이 유지되어야 한다.
        val reopened =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, MIGRATION_3_4_DB)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()
        runTest {
            assertEquals("A의 방", reopened.cacheDao().miniHome("account-a")?.name)
            assertEquals(2, reopened.cacheDao().plants("account-a").size)
        }
        reopened.close()
        assertEquals(21, readUserVersion(file))

        assertTrue(file.delete())
        restoreInMemoryDatabase(context)
    }

    @Test
    fun `the migrated database keeps the version three indexes and adds no foreign keys`() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = createVersion3Database(context, INDEX_DB)

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, INDEX_DB)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()
        runTest { assertEquals(2, migrated.cacheDao().plants("account-a").size) }
        migrated.close()

        // 기존 인덱스는 그대로 살아 있어야 한다.
        assertTrue(readIndexNames(file, "cached_plants").contains("index_cached_plants_accountId"))
        assertTrue(
            readIndexNames(file, "cached_watering_schedules")
                .contains("index_cached_watering_schedules_accountId_plantId")
        )
        assertTrue(
            readIndexNames(file, "operation_outbox")
                .contains("index_operation_outbox_accountId_state_createdAtEpochMillis")
        )

        // 현재 스키마는 외래 키가 없다. 마이그레이션이 임의로 만들지 않았는지 확인한다.
        VERSION_14_TABLES.forEach { table ->
            assertEquals("${table}에 외래 키가 생기면 안 된다", 0, readForeignKeyCount(file, table))
        }

        assertTrue(file.delete())
        restoreInMemoryDatabase(context)
    }

    @Test
    fun `applying the mini home migration twice is idempotent and never drops rows`() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = createVersion3Database(context, DUPLICATE_DB)

        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DUPLICATE_DB)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()
        runTest {
            migrated
                .cacheDao()
                .upsertMiniHome(CachedMiniHomeEntity("account-a", "home-a", "지키는 방", 5, 1, 30))
        }
        migrated.close()

        // 같은 마이그레이션을 한 번 더 적용해도 테이블을 다시 만들거나 행을 날리면 안 된다.
        // 마이그레이션이 실행하는 SQL을 그대로 한 번 더 돌려 멱등성을 확인한다.
        applyMigrationDirectly(context, DUPLICATE_DB, MIGRATION_3_4)

        val reopened =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DUPLICATE_DB)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()
        runTest {
            assertEquals("지키는 방", reopened.cacheDao().miniHome("account-a")?.name)
            assertEquals(2, reopened.cacheDao().plants("account-a").size)
        }
        reopened.close()
        assertEquals(1, readTableNames(file).count { it == "cached_mini_homes" })

        assertTrue(file.delete())
        restoreInMemoryDatabase(context)
    }

    @Test
    fun `a database from a newer version is rejected instead of silently downgraded`() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = createVersion3Database(context, DOWNGRADE_DB)
        // 앞서 배포된 더 높은 버전에서 되돌아온 상황을 흑낸다.
        openRaw(file).use { it.execSQL("PRAGMA user_version = 22") }
        assertEquals(22, readUserVersion(file))

        val downgraded =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, DOWNGRADE_DB)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()

        val failure =
            assertThrows(IllegalStateException::class.java) {
                runTest { downgraded.cacheDao().plants("account-a") }
            }
        assertTrue(
            "다운그레이드는 명시적으로 거부되어야 한다: ${failure.message}",
            failure.message.orEmpty().contains("Downgrade", ignoreCase = true) ||
                failure.message.orEmpty().contains("migration", ignoreCase = true),
        )
        downgraded.close()

        assertTrue(file.delete())
        restoreInMemoryDatabase(context)
    }

    @Test
    fun `migration one to two preserves rows and adds account partition`() = runTest {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.getDatabasePath("migration.db").path)
        file.delete()
        val configuration =
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration.db")
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE cached_plants (plantId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, representativePhotoPath TEXT, revision INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)"
                            )
                            db.execSQL(
                                "INSERT INTO cached_plants VALUES ('legacy-plant', 'Legacy', NULL, 1, 1)"
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    }
                )
                .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also {
            it.writableDatabase.close()
            it.close()
        }
        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, "migration.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()
        assertEquals(
            "Legacy",
            migrated.cacheDao().plant(AccountId.LEGACY.value, "legacy-plant")?.displayName,
        )
        migrated.close()
        assertTrue(file.delete())
    }

    @Test
    fun `migration two to three partitions outbox identity by account`() = runTest {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-2-3.db"
        context.deleteDatabase(name)
        val configuration =
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE cached_plants (`accountId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `representativePhotoPath` TEXT, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `plantId`))"
                            )
                            db.execSQL(
                                "CREATE INDEX index_cached_plants_accountId ON cached_plants (`accountId`)"
                            )
                            db.execSQL(
                                "CREATE TABLE cached_watering_schedules (`accountId` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `dueDate` TEXT NOT NULL, `reminderTime` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `scheduleId`))"
                            )
                            db.execSQL(
                                "CREATE INDEX index_cached_watering_schedules_accountId_plantId ON cached_watering_schedules (`accountId`, `plantId`)"
                            )
                            db.execSQL(
                                "CREATE TABLE operation_outbox (`operationId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `aggregateType` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `mutationType` TEXT NOT NULL, `expectedRevision` INTEGER NOT NULL, `draftPayload` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `actualRevision` INTEGER, `lastErrorCode` TEXT, PRIMARY KEY(`operationId`))"
                            )
                            db.execSQL(
                                "CREATE INDEX index_operation_outbox_accountId_state_createdAtEpochMillis ON operation_outbox (`accountId`, `state`, `createdAtEpochMillis`)"
                            )
                            db.execSQL(
                                "CREATE TABLE last_sync (`accountId` TEXT NOT NULL, `domain` TEXT NOT NULL, `syncedAtEpochMillis` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorCode` TEXT, PRIMARY KEY(`accountId`, `domain`))"
                            )
                            db.execSQL(
                                "INSERT INTO operation_outbox VALUES ('shared-operation', 'account-a', 'plant', 'p-a', 'UPDATE', 0, 'draft-a', 1, 'PENDING', NULL, NULL)"
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    }
                )
                .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also {
            it.writableDatabase.close()
            it.close()
        }
        val migrated =
            Room.databaseBuilder(context, PlanteriorDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .allowMainThreadQueries()
                .build()
        migrated
            .syncDao()
            .enqueue(
                OperationOutboxEntity(
                    "shared-operation",
                    "account-b",
                    "plant",
                    "p-b",
                    "UPDATE",
                    0,
                    "draft-b",
                    2,
                )
            )
        assertEquals("draft-a", migrated.syncDao().pending("account-a").single().draftPayload)
        assertEquals("draft-b", migrated.syncDao().pending("account-b").single().draftPayload)
        migrated.close()
        assertTrue(context.deleteDatabase(name))
    }

    /**
     * 진짜 버전 3 데이터베이스 파일을 만든다.
     *
     * 테이블·인덱스 SQL은 committed 스키마 `3.json`의 `createSql`과 글자 단위로 같고, `user_version`과
     * `room_master_table`의 identity hash도 실제 v3 Room 데이터베이스와 동일하게 맞췄다. 그래야 Room이 이 파일을 열 때 실제로 3 ->
     * 4 마이그레이션 경로를 타게 된다.
     */
    private fun createVersion3Database(context: Context, name: String): File {
        val file = File(context.getDatabasePath(name).path)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        val configuration =
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(VERSION_3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            VERSION_3_SCHEMA.forEach(db::execSQL)
                            VERSION_3_SEED.forEach(db::execSQL)
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
                            )
                            db.execSQL(
                                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '$VERSION_3_IDENTITY_HASH')"
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    }
                )
                .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also {
            it.writableDatabase.close()
            it.close()
        }
        return file
    }

    /** 마이그레이션을 거치지 않고 파일을 그대로 여는다. 스키마 상태를 있는 그대로 관찰하기 위해서이다. */
    private fun openRaw(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)

    /**
     * 마이그레이션 SQL을 Room 밖에서 한 번 더 적용한다.
     *
     * 현재 버전으로 열어 업그레이드 콜백을 건드리지 않고, 순수하게 같은 SQL을 다시 돌렸을 때의 결과만 본다.
     */
    private fun applyMigrationDirectly(context: Context, name: String, migration: Migration) {
        val configuration =
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(VERSION_21) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    }
                )
                .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            migration.migrate(helper.writableDatabase)
        }
    }

    private fun readUserVersion(file: File): Int =
        openRaw(file).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

    private fun readTableNames(file: File): List<String> =
        openRaw(file).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

    private fun readIndexNames(file: File, table: String): List<String> =
        openRaw(file).use { db ->
            db.rawQuery("PRAGMA index_list(`$table`)", null).use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                buildList { while (cursor.moveToNext()) add(cursor.getString(nameColumn)) }
            }
        }

    private fun readForeignKeyCount(file: File, table: String): Int =
        openRaw(file).use { db ->
            db.rawQuery("PRAGMA foreign_key_list(`$table`)", null).use { it.count }
        }

    /** `(이름, 타입, NOT NULL 여부, 기본값, 기본키 순서)` 목록을 돌려준다. */
    private fun readColumns(file: File, table: String): List<ColumnInfo> =
        openRaw(file).use { db ->
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            ColumnInfo(
                                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                                type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                                notNull =
                                    cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                                defaultValue =
                                    cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                                primaryKeyPosition =
                                    cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                            )
                        )
                    }
                }
            }
        }

    /** 마이그레이션 결과가 현재 committed 스키마와 같은지 확인한다. */
    private fun assertMatchesVersion14Schema(file: File) {
        VERSION_14_TABLES.forEach { table ->
            assertTrue("$table 테이블이 있어야 한다", readTableNames(file).contains(table))
        }

        val miniHome = readColumns(file, "cached_mini_homes")
        assertEquals(
            listOf(
                "accountId" to "TEXT",
                "miniHomeId" to "TEXT",
                "name" to "TEXT",
                "placedPlantCount" to "INTEGER",
                "revision" to "INTEGER",
                "updatedAtEpochMillis" to "INTEGER",
            ),
            miniHome.map { it.name to it.type },
        )
        assertTrue("모든 열은 NOT NULL이어야 한다", miniHome.all { it.notNull })
        assertTrue(
            "임의의 기본값을 두지 않는다",
            miniHome.all { it.defaultValue == null },
        )
        assertEquals(
            "accountId 하나만 기본키여야 한다",
            listOf("accountId"),
            miniHome.filter { it.primaryKeyPosition > 0 }.map { it.name },
        )
        assertEquals(0, readForeignKeyCount(file, "cached_mini_homes"))

        val watermark = readColumns(file, "mini_home_cache_watermarks")
        val verified = watermark.first { it.name == "verified" }
        assertEquals("INTEGER", verified.type)
        assertTrue(verified.notNull)
        assertEquals("1", verified.defaultValue)
        assertFalse(watermark.first { it.name == "snapshotToken" }.notNull)
        assertFalse(watermark.first { it.name == "snapshotGeneration" }.notNull)

        val schedules = readColumns(file, "cached_watering_schedules")
        assertFalse(schedules.first { it.name == "reminderTime" }.notNull)
        assertFalse(schedules.first { it.name == "enabled" }.notNull)
        assertNull(schedules.first { it.name == "reminderTime" }.defaultValue)
        assertNull(schedules.first { it.name == "enabled" }.defaultValue)

        val plants = readColumns(file, "cached_plants")
        assertEquals(
            listOf(
                "contentId",
                "registrationMethod",
                "location",
                "note",
                "lastWateredDate",
                "detailsComplete",
            ),
            plants.takeLast(6).map { it.name },
        )
        assertFalse(plants.first { it.name == "contentId" }.notNull)
        assertTrue(plants.first { it.name == "registrationMethod" }.notNull)
        assertEquals("'MANUAL'", plants.first { it.name == "registrationMethod" }.defaultValue)
        assertFalse(plants.first { it.name == "location" }.notNull)
        assertFalse(plants.first { it.name == "note" }.notNull)
        assertFalse(plants.first { it.name == "lastWateredDate" }.notNull)
        assertTrue(plants.first { it.name == "detailsComplete" }.notNull)
        assertEquals("0", plants.first { it.name == "detailsComplete" }.defaultValue)

        val outbox = readColumns(file, "operation_outbox")
        assertEquals(
            listOf(
                "failureDetails",
                "committedOperationId",
                "committedExpectedRevision",
                "committedRevision",
                "payloadHash",
                "committedPayloadHash",
                "lineageId",
                "supersedesOperationId",
                "rowHandleId",
                "rowVersion",
            ),
            outbox.takeLast(10).map { it.name },
        )
        assertTrue(outbox.dropLast(2).takeLast(8).all { !it.notNull && it.defaultValue == null })
        assertTrue(outbox.takeLast(2).all { it.notNull })
        assertEquals("''", outbox[outbox.lastIndex - 1].defaultValue)
        assertEquals("0", outbox.last().defaultValue)
    }

    private fun restoreInMemoryDatabase(context: Context) {
        database =
            Room.inMemoryDatabaseBuilder(context, PlanteriorDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )

    private companion object {
        const val VERSION_3 = 3
        const val VERSION_12 = 12
        const val VERSION_13 = 13
        const val VERSION_14 = 14
        const val VERSION_21 = 21
        const val MIGRATION_3_4_DB = "migration-3-4.db"
        const val INDEX_DB = "migration-3-4-index.db"
        const val DUPLICATE_DB = "migration-3-4-duplicate.db"
        const val DOWNGRADE_DB = "migration-3-4-downgrade.db"

        /** `schemas/.../3.json`의 identityHash. 실제 v3 Room 파일과 동일하게 재현하기 위해 필요하다. */
        const val VERSION_3_IDENTITY_HASH = "aaa3850f1960bd80061701475b7929c5"

        val VERSION_14_TABLES =
            listOf(
                "cached_plants",
                "cached_watering_schedules",
                "cached_mini_homes",
                "cached_mini_home_placements",
                "mini_home_cache_watermarks",
                "operation_outbox",
                "last_sync",
            )

        /** `schemas/.../3.json`의 `createSql`을 그대로 옮겨 둔 것이다. */
        val VERSION_3_SCHEMA =
            listOf(
                "CREATE TABLE IF NOT EXISTS `cached_plants` (`accountId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `representativePhotoPath` TEXT, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `plantId`))",
                "CREATE INDEX IF NOT EXISTS `index_cached_plants_accountId` ON `cached_plants` (`accountId`)",
                "CREATE TABLE IF NOT EXISTS `cached_watering_schedules` (`accountId` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `plantId` TEXT NOT NULL, `dueDate` TEXT NOT NULL, `reminderTime` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `revision` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `scheduleId`))",
                "CREATE INDEX IF NOT EXISTS `index_cached_watering_schedules_accountId_plantId` ON `cached_watering_schedules` (`accountId`, `plantId`)",
                "CREATE TABLE IF NOT EXISTS `operation_outbox` (`operationId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `aggregateType` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `mutationType` TEXT NOT NULL, `expectedRevision` INTEGER NOT NULL, `draftPayload` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `actualRevision` INTEGER, `lastErrorCode` TEXT, PRIMARY KEY(`accountId`, `operationId`))",
                "CREATE INDEX IF NOT EXISTS `index_operation_outbox_accountId_state_createdAtEpochMillis` ON `operation_outbox` (`accountId`, `state`, `createdAtEpochMillis`)",
                "CREATE TABLE IF NOT EXISTS `last_sync` (`accountId` TEXT NOT NULL, `domain` TEXT NOT NULL, `syncedAtEpochMillis` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorCode` TEXT, PRIMARY KEY(`accountId`, `domain`))",
            )

        /** v3의 모든 테이블에 걸쳐 두 계정치 행을 넣어 보존 여부를 확인할 수 있게 한다. */
        val VERSION_3_SEED =
            listOf(
                "INSERT INTO `cached_plants` VALUES ('account-a', 'plant-a1', '몬몬이', NULL, 1, 10)",
                "INSERT INTO `cached_plants` VALUES ('account-a', 'plant-a2', '븾족이', 'photo/a2.jpg', 2, 11)",
                "INSERT INTO `cached_plants` VALUES ('account-b', 'plant-b1', '남의 식물', NULL, 1, 12)",
                "INSERT INTO `cached_watering_schedules` VALUES ('account-a', 'schedule-a1', 'plant-a1', '2026-08-12', '09:00', 'Asia/Seoul', 1, 13)",
                "INSERT INTO `cached_watering_schedules` VALUES ('account-a', 'schedule-a2', 'plant-a2', '2026-08-15', '10:00', 'America/Los_Angeles', 1, 14)",
                "INSERT INTO `operation_outbox` VALUES ('operation-a1', 'account-a', 'personalPlant', 'plant-a1', 'UPDATE', 1, '남아야 하는 draft', 15, 'PENDING', NULL, NULL)",
                "INSERT INTO `operation_outbox` VALUES ('operation-a2', 'account-a', 'personalPlant', 'plant-a2', 'UPDATE', 2, '두 번째 draft', 16, 'CONFLICT', 9, 'REVISION_CONFLICT')",
                "INSERT INTO `operation_outbox` VALUES ('operation-a3', 'account-a', 'miniHomeLayouts', 'home-a', 'REPLACE', 3, '미니홈 draft', 17, 'FAILED', NULL, 'OUTBOX_MISMATCH')",
                "INSERT INTO `last_sync` VALUES ('account-a', 'PLANTS', 1786500000000, 'SUCCESS', NULL)",
                "INSERT INTO `last_sync` VALUES ('account-a', 'MINI_HOME', 1786500000001, 'FAILED', 'unavailable')",
            )
    }
}
