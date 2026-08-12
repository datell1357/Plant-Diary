package com.planterior.helper.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.database.CachedPlantEntity
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepositoryManualDriverTest {
    @Test
    fun `account switch idempotent resume and conflict draft driver`() = runTest {
        val database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    PlanteriorDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        try {
            database
                .cacheDao()
                .upsertPlant(CachedPlantEntity("account-a", "plant-a", "A", null, 1, 1))
            database
                .cacheDao()
                .upsertPlant(CachedPlantEntity("account-b", "plant-b", "B", null, 1, 1))
            val gateway = DriverGateway()
            val repository =
                OfflineFirstSyncRepository(database, gateway) {
                    Instant.parse("2026-08-12T00:00:00Z")
                }
            repository.activate(AccountId("account-a"))
            val aBefore = repository.visiblePlants().map { it.id.value }
            repository.activate(AccountId("account-b"))
            val bVisible = repository.visiblePlants().map { it.id.value }
            repository.activate(AccountId("account-a"))
            val aResumed = repository.visiblePlants().map { it.id.value }
            val appliedId =
                OperationId.stable(AccountId("account-a"), "plant-a", "rename", "applied")
            repository.enqueuePlantDraft(AccountId("account-a"), appliedId, "plant-a", 1, "applied")
            repository.enqueuePlantDraft(AccountId("account-a"), appliedId, "plant-a", 1, "applied")
            val applied = repository.sync(AccountId("account-a"))
            val conflictId =
                OperationId.stable(AccountId("account-a"), "plant-a", "rename", "conflict")
            repository.enqueuePlantDraft(
                AccountId("account-a"),
                conflictId,
                "plant-a",
                1,
                "recoverable-draft",
            )
            gateway.conflict = true
            val conflict = repository.sync(AccountId("account-a"))
            val retained = database.syncDao().pending("account-a").single()
            assertEquals(listOf("plant-a"), aBefore)
            assertEquals(listOf("plant-b"), bVisible)
            assertEquals(aBefore, aResumed)
            assertEquals(1, applied.applied)
            assertEquals(1, gateway.appliedCalls)
            assertEquals(1, conflict.conflicts)
            assertEquals("recoverable-draft", retained.draftPayload)
            println(
                "MANUAL_QA accountA=$aBefore accountB=$bVisible resumedA=$aResumed idempotentRemoteCalls=${gateway.appliedCalls} conflictDraft=${retained.draftPayload}"
            )
        } finally {
            database.close()
        }
    }

    private class DriverGateway : RemoteMutationGateway {
        var conflict = false
        var appliedCalls = 0

        override suspend fun apply(command: RemoteMutationCommand): RemoteMutationResult {
            if (conflict) return RemoteMutationResult.Conflict(3)
            appliedCalls += 1
            return RemoteMutationResult.Applied(2)
        }
    }
}
