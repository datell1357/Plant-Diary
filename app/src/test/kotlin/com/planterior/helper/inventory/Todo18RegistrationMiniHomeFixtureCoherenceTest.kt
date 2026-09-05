package com.planterior.helper.inventory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.Todo18InventoryRepositoryFixture
import com.planterior.helper.Todo18MiniHomeRepositoryFixture
import com.planterior.helper.Todo18Scenario
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.minihome.FirebaseMiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeLoadResult
import com.planterior.helper.feature.shop.FirebaseInventoryRepository
import com.planterior.helper.feature.shop.InventoryAcquireRequest
import com.planterior.helper.feature.shop.InventoryAcquireResult
import com.planterior.helper.feature.shop.InventoryLoadResult
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRecorder
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRepository
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
@Config(sdk = [ROBOLECTRIC_MAX_SDK])
class Todo18RegistrationMiniHomeFixtureCoherenceTest {
    private lateinit var database: PlanteriorDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    PlanteriorDatabase::class.java,
                )
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `registration acquisition reload publishes coherent MiniHome terminal ready`() = runTest {
        val scenario = Todo18Scenario(AccountId("todo18-registration-owner"))
        scenario.seedPlant()
        val inventory =
            FirebaseInventoryRepository(
                database,
                Todo18InventoryRepositoryFixture(scenario),
                scenario::now,
            )
        val catalog = (inventory.load() as InventoryLoadResult.Ready).snapshot.catalog.single()

        assertTrue(
            inventory.acquire(
                InventoryAcquireRequest(
                    scenario.accountId,
                    catalog.id,
                    catalog.revision,
                    OperationId("todo18-registration-acquire"),
                )
            ) is InventoryAcquireResult.Success
        )
        val reloaded = (inventory.load(forceRefresh = true) as InventoryLoadResult.Ready).snapshot
        assertEquals(2L, reloaded.generation)
        assertEquals(listOf(catalog.id), reloaded.owned.map { it.itemId })

        val diagnostics = Todo18MiniHomeLoadDiagnosticRecorder(scenario::emitMiniHomeLoadDiagnostic)
        val miniHome =
            Todo18MiniHomeLoadDiagnosticRepository(
                FirebaseMiniHomeRepository(
                    database,
                    Todo18MiniHomeRepositoryFixture(scenario, diagnostics),
                    scenario::now,
                ),
                diagnostics,
            )

        val result = miniHome.load()
        val progress = miniHome.loadProgressSnapshot()

        assertTrue(
            "expected Ready/terminal-ready after inventory generation 2; " +
                "result=$result terminal=${progress.lastReachedStage} valid=${progress.valid}",
            result is MiniHomeLoadResult.Ready &&
                progress.valid &&
                progress.lastReachedStage == "terminal-ready",
        )
    }
}
