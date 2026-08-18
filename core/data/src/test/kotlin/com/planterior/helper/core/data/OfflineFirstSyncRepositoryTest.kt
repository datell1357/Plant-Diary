package com.planterior.helper.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.OperationOutboxEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineFirstSyncRepositoryTest {
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
    fun `duplicate idempotency operation applies once`() = runTest {
        val remote = RecordingGateway()
        val repository = OfflineFirstSyncRepository(database, remote)
        val operation = OperationId.stable(AccountId("account-a"), "plant-a", "rename", "leaf")
        repository.enqueuePlantDraft(AccountId("account-a"), operation, "plant-a", 1, "새 이름")
        repository.enqueuePlantDraft(AccountId("account-a"), operation, "plant-a", 1, "새 이름")
        val report = repository.sync(AccountId("account-a"))
        assertEquals(1, report.applied)
        assertEquals(1, remote.calls)
        assertEquals("personalPlants", remote.commands.single().aggregateType)
        assertTrue(database.syncDao().pending("account-a").isEmpty())
    }

    @Test
    fun `revision conflict retains recoverable draft`() = runTest {
        val remote = RecordingGateway(RemoteMutationResult.Conflict(actualRevision = 3))
        val repository = OfflineFirstSyncRepository(database, remote)
        val operation = OperationId.stable(AccountId("account-a"), "plant-a", "rename", "conflict")
        repository.enqueuePlantDraft(AccountId("account-a"), operation, "plant-a", 1, "보존할 초안")
        val report = repository.sync(AccountId("account-a"))
        val retained = database.syncDao().pending("account-a").single()
        val resumedWithoutMerge = repository.sync(AccountId("account-a"))
        assertEquals(1, report.conflicts)
        assertEquals(0, resumedWithoutMerge.conflicts)
        assertEquals(1, remote.calls)
        assertEquals("보존할 초안", retained.draftPayload)
        assertEquals("CONFLICT", retained.state)
        assertEquals(3L, retained.actualRevision)
    }

    @Test
    fun `replay retries only explicitly transient failed operations`() = runTest {
        val transient = OperationId("operation-transient")
        val permanent = OperationId("operation-permanent")
        val remote = RecordingGateway()
        val repository = OfflineFirstSyncRepository(database, remote)
        repository.enqueuePlantDraft(AccountId("account-a"), transient, "plant-a", 1, "transient")
        repository.enqueuePlantDraft(AccountId("account-a"), permanent, "plant-a", 1, "permanent")
        database.syncDao().markFailed("account-a", transient.value, "UNAVAILABLE")
        database.syncDao().markFailed("account-a", permanent.value, "INVALID_ARGUMENT")

        val report = repository.sync(AccountId("account-a"))

        assertEquals(1, report.applied)
        assertEquals(listOf(transient), remote.commands.map { it.operationId })
        val retained = database.syncDao().pending("account-a").single()
        assertEquals(permanent.value, retained.operationId)
        assertEquals("INVALID_ARGUMENT", retained.lastErrorCode)
    }

    @Test
    fun `generic replay never removes watering outbox before receipt cache reconciliation`() =
        runTest {
            database
                .syncDao()
                .enqueue(
                    OperationOutboxEntity(
                        operationId = "watering-operation-stable",
                        accountId = "account-a",
                        aggregateType = "wateringCompletions",
                        aggregateId = "plant-a",
                        mutationType = "UPDATE",
                        expectedRevision = 4,
                        draftPayload = "{\"wateredDate\":\"2026-08-12\"}",
                        createdAtEpochMillis = 1,
                    )
                )
            val remote = RecordingGateway(RemoteMutationResult.Applied(5))

            val report = OfflineFirstSyncRepository(database, remote).sync(AccountId("account-a"))

            assertEquals(SyncReport(0, 0, 0), report)
            assertEquals(0, remote.calls)
            assertEquals(
                listOf("watering-operation-stable"),
                database.syncDao().pending("account-a").map { it.operationId },
            )
        }

    @Test
    fun `account switch hides A while B is active and A can resume`() = runTest {
        database.cacheDao().upsertPlant(CachedPlantEntity("account-a", "plant-a", "A", null, 1, 1))
        database.cacheDao().upsertPlant(CachedPlantEntity("account-b", "plant-b", "B", null, 1, 1))
        val repository = OfflineFirstSyncRepository(database, RecordingGateway())
        repository.activate(AccountId("account-a"))
        assertEquals(listOf("plant-a"), repository.visiblePlants().map { it.id.value })
        repository.activate(AccountId("account-b"))
        assertEquals(listOf("plant-b"), repository.visiblePlants().map { it.id.value })
        repository.activate(AccountId("account-a"))
        assertEquals(listOf("plant-a"), repository.visiblePlants().map { it.id.value })
    }

    private class RecordingGateway(
        private val result: RemoteMutationResult = RemoteMutationResult.Applied(2)
    ) : RemoteMutationGateway {
        var calls = 0
        val commands = mutableListOf<RemoteMutationCommand>()

        override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult {
            calls += 1
            commands += command
            return result
        }
    }
}
