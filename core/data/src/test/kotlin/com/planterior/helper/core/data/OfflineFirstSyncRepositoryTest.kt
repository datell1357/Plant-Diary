package com.planterior.helper.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.database.CachedPlantEntity
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

        override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult {
            calls += 1
            return result
        }
    }
}
