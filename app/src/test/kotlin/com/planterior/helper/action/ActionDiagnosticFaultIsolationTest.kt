package com.planterior.helper.action

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.Todo18BoundaryEvent
import com.planterior.helper.Todo18MiniHomeRepositoryFixture
import com.planterior.helper.Todo18MiniHomeSaveMode
import com.planterior.helper.Todo18PlantRepositoryFixture
import com.planterior.helper.Todo18Scenario
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.collection.CollectionWateringPreparationSource
import com.planterior.helper.feature.collection.FirebaseCollectionRepository
import com.planterior.helper.feature.minihome.FirebaseMiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomeRoute
import com.planterior.helper.feature.minihome.MiniHomeSaveActionDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeSaveActionObservation
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.minihome.MiniHomeSaveState
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.feature.watering.OutboxWateringRepository
import com.planterior.helper.feature.watering.WateringCompletionReceipt
import com.planterior.helper.feature.watering.WateringConfirmActionDiagnostics
import com.planterior.helper.feature.watering.WateringConfirmActionObservation
import com.planterior.helper.feature.watering.WateringConfirmActionStage
import com.planterior.helper.feature.watering.WateringConfirmationRoute
import com.planterior.helper.feature.watering.WateringTestTags
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRecorder
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRepository
import java.io.Closeable
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [ROBOLECTRIC_MAX_SDK],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class ActionDiagnosticFaultIsolationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
        assertEquals(0, MiniHomeSaveActionDiagnostics.listenerCount())
        assertEquals(0, WateringConfirmActionDiagnostics.listenerCount())
        database.close()
    }

    @Test
    fun `RuntimeException from every reached MiniHome hook preserves conflict action`() {
        assertMiniHomeFaultIsolation(
            Todo18MiniHomeSaveMode.REVISION_CONFLICT,
            RuntimeException("MiniHome diagnostic fault"),
        )
    }

    @Test
    fun `AssertionError from every reached MiniHome hook preserves offline action`() {
        assertMiniHomeFaultIsolation(
            Todo18MiniHomeSaveMode.OFFLINE_ONCE,
            AssertionError("MiniHome diagnostic fault"),
        )
    }

    @Test
    fun `RuntimeException from every reached Watering hook preserves real action`() {
        assertWateringFaultIsolation(RuntimeException("Watering diagnostic fault"))
    }

    @Test
    fun `AssertionError from every reached Watering hook preserves real action`() {
        assertWateringFaultIsolation(AssertionError("Watering diagnostic fault"))
    }

    private fun assertMiniHomeFaultIsolation(
        mode: Todo18MiniHomeSaveMode,
        observerFailure: Throwable,
    ) {
        val scenario = Todo18Scenario(AccountId("mini-fault-${mode.name.lowercase()}"))
        scenario.miniHomeSaveMode = mode
        val plantId = scenario.seedPlant()
        val loadDiagnostics =
            Todo18MiniHomeLoadDiagnosticRecorder(scenario::emitMiniHomeLoadDiagnostic)
        val repository =
            Todo18MiniHomeLoadDiagnosticRepository(
                FirebaseMiniHomeRepository(
                    database,
                    Todo18MiniHomeRepositoryFixture(scenario, loadDiagnostics),
                    scenario::now,
                ),
                loadDiagnostics,
            )
        val observations = mutableListOf<MiniHomeSaveActionObservation>()
        val thrown = mutableListOf<Throwable>()
        val boundaryEvents = mutableListOf<Todo18BoundaryEvent>()
        val diagnostic = MiniHomeSaveActionDiagnostics.install { observation ->
            observations += observation
            thrown += observerFailure
            throw observerFailure
        }
        val boundary: Closeable = scenario.subscribe { event ->
            if (event.kind == "mini-home-save-attempt") boundaryEvents += event
        }
        val rawStates = mutableListOf<MiniHomeUiState>()
        val routeReady = AtomicBoolean(false)
        val savingObserved = AtomicBoolean(false)
        val terminalSaveObserved = AtomicBoolean(false)
        val routeIdlingResource = ActionPathIdlingResource(routeReady::get)
        val terminalSaveIdlingResource = ActionPathIdlingResource(terminalSaveObserved::get)
        var routeIdlingRegistered = false
        var terminalSaveIdlingRegistered = false
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.setContent {
                PlanteriorTheme {
                    MiniHomeRoute(
                        repository = repository,
                        onBack = {},
                        onOpenCollection = {},
                        onRawStateObserved = { state ->
                            rawStates += state
                            if (state is MiniHomeUiState.Viewing) routeReady.set(true)
                            if (state is MiniHomeUiState.Editing) {
                                if (state.saveState is MiniHomeSaveState.Saving) {
                                    savingObserved.set(true)
                                } else if (savingObserved.get()) {
                                    terminalSaveObserved.set(true)
                                }
                            }
                        },
                    )
                }
            }
            compose.registerIdlingResource(routeIdlingResource)
            routeIdlingRegistered = true
            compose.waitForIdle()

            compose.onNodeWithTag(MiniHomeTestTags.EDIT).performClick()
            compose.onNodeWithTag(MiniHomeTestTags.plant(plantId)).performClick()
            compose.onNodeWithTag(MiniHomeTestTags.SAVE).performClick()
            compose.registerIdlingResource(terminalSaveIdlingResource)
            terminalSaveIdlingRegistered = true
            compose.waitForIdle()

            val savingIndex = rawStates.indexOfFirst {
                it is MiniHomeUiState.Editing && it.saveState is MiniHomeSaveState.Saving
            }
            val terminalIndex =
                rawStates
                    .withIndex()
                    .firstOrNull { (index, state) ->
                        index > savingIndex &&
                            state is MiniHomeUiState.Editing &&
                            state.saveState !is MiniHomeSaveState.Saving
                    }
                    ?.index ?: -1
            assertTrue("raw Saving was not observed", savingIndex >= 0)
            assertTrue("raw terminal state did not follow Saving", terminalIndex > savingIndex)
            val saveState = (rawStates.last() as MiniHomeUiState.Editing).saveState
            when (mode) {
                Todo18MiniHomeSaveMode.REVISION_CONFLICT ->
                    assertTrue(saveState is MiniHomeSaveState.ReconciliationRequired)
                Todo18MiniHomeSaveMode.OFFLINE_ONCE ->
                    assertTrue(saveState is MiniHomeSaveState.Failed)
                Todo18MiniHomeSaveMode.APPLY -> error("not a fault-isolation failure mode")
            }
            assertEquals(MINI_HOME_REACHED_STAGES, observations.map { it.stage })
            assertEquals(observations.size, thrown.size)
            thrown.forEach { assertSame(observerFailure, it) }
            val request = scenario.miniHomeSaveRequests.single()
            assertEquals(
                setOf(plantId),
                request.layout.placements
                    .mapNotNull { it.target as? MiniHomePlacementTarget.Plant }
                    .map { it.plantId }
                    .toSet(),
            )
            assertEquals(request.operationId.value, boundaryEvents.single().identity)
            assertEquals(
                setOf(request.operationId),
                observations.mapNotNull { it.operationId }.toSet(),
            )
        } finally {
            if (terminalSaveIdlingRegistered) {
                compose.unregisterIdlingResource(terminalSaveIdlingResource)
            }
            if (routeIdlingRegistered) compose.unregisterIdlingResource(routeIdlingResource)
            boundary.close()
            diagnostic.close()
        }
        assertEquals(0, scenario.listenerCount())
    }

    private fun assertWateringFaultIsolation(observerFailure: Throwable) {
        val scenario = Todo18Scenario(AccountId("watering-fault-owner"))
        val plantId = scenario.seedPlant()
        val plants = Todo18PlantRepositoryFixture(scenario)
        val routeReady = AtomicBoolean(false)
        val repository =
            OutboxWateringRepository(
                database,
                ActionPathWateringPreparationSource(
                    CollectionWateringPreparationSource(
                        FirebaseCollectionRepository(database, plants, plants, scenario::now)
                    )
                ) {
                    routeReady.set(true)
                },
                plants,
                plants,
                scenario::now,
            )
        val observations = mutableListOf<WateringConfirmActionObservation>()
        val thrown = mutableListOf<Throwable>()
        val boundaryEvents = mutableListOf<Todo18BoundaryEvent>()
        val diagnostic = WateringConfirmActionDiagnostics.install { observation ->
            observations += observation
            thrown += observerFailure
            throw observerFailure
        }
        val boundary: Closeable = scenario.subscribe { event ->
            if (event.kind == "watering-receipt") boundaryEvents += event
        }
        val completed = mutableListOf<WateringCompletionReceipt>()
        val routeIdlingResource = ActionPathIdlingResource(routeReady::get)
        val completedIdlingResource = ActionPathIdlingResource(completed::isNotEmpty)
        var routeIdlingRegistered = false
        var completionIdlingRegistered = false
        try {
            compose.setContent {
                PlanteriorTheme {
                    WateringConfirmationRoute(
                        plantId = plantId,
                        repository = repository,
                        onBack = {},
                        onDone = {},
                        clock = Clock.fixed(scenario.now(), scenario.zone),
                        onCompleted = completed::add,
                    )
                }
            }
            compose.registerIdlingResource(routeIdlingResource)
            routeIdlingRegistered = true
            compose.waitForIdle()

            compose.onNodeWithTag(WateringTestTags.CONFIRM).performClick()
            assertTrue(observations.any { it.stage == WateringConfirmActionStage.COROUTINE_ENTRY })
            compose.registerIdlingResource(completedIdlingResource)
            completionIdlingRegistered = true
            compose.waitForIdle()
            compose.onNodeWithTag(WateringTestTags.RESULT).assertIsDisplayed()

            assertEquals(WATERING_REACHED_STAGES, observations.map { it.stage })
            assertEquals(observations.size, thrown.size)
            thrown.forEach { assertSame(observerFailure, it) }
            val boundaryEvent = boundaryEvents.single()
            assertEquals(
                setOf(boundaryEvent.identity),
                completed.map { it.operationId.value }.toSet(),
            )
            assertEquals(
                setOf(boundaryEvent.identity),
                observations.mapNotNull { it.operationId?.value }.toSet(),
            )
            assertEquals(setOf(plantId), observations.mapNotNull { it.plantId }.toSet())
            runBlocking {
                assertEquals(
                    LocalDate.of(2026, 8, 26),
                    database
                        .cacheDao()
                        .plant(scenario.accountId.value, plantId.value)
                        ?.lastWateredDate
                        ?.let(LocalDate::parse),
                )
                assertTrue(database.syncDao().pending(scenario.accountId.value).isEmpty())
            }
        } finally {
            if (completionIdlingRegistered) {
                compose.unregisterIdlingResource(completedIdlingResource)
            }
            if (routeIdlingRegistered) compose.unregisterIdlingResource(routeIdlingResource)
            boundary.close()
            diagnostic.close()
        }
        assertEquals(0, scenario.listenerCount())
    }

    private companion object {
        val MINI_HOME_REACHED_STAGES =
            listOf(
                MiniHomeSaveActionStage.SCREEN_CALLBACK,
                MiniHomeSaveActionStage.COROUTINE_ENTRY,
                MiniHomeSaveActionStage.CONTROLLER_ENTRY,
                MiniHomeSaveActionStage.GUARD_DECISION,
                MiniHomeSaveActionStage.VALIDATION_DECISION,
                MiniHomeSaveActionStage.SAVING_PUBLICATION,
                MiniHomeSaveActionStage.FIXTURE_SAVE_ENTRY,
                MiniHomeSaveActionStage.FIXTURE_EVENT_EMIT,
                MiniHomeSaveActionStage.LISTENER_DELIVERY,
            )

        val WATERING_REACHED_STAGES =
            listOf(
                WateringConfirmActionStage.SCREEN_CALLBACK,
                WateringConfirmActionStage.COROUTINE_ENTRY,
                WateringConfirmActionStage.CONTROLLER_ENTRY,
                WateringConfirmActionStage.VALIDATION_DECISION,
                WateringConfirmActionStage.SAVING_PUBLICATION,
                WateringConfirmActionStage.REPOSITORY_COMPLETE_ENTRY,
                WateringConfirmActionStage.APPLY_RESULT,
                WateringConfirmActionStage.RECEIPT_LOOKUP_ENTRY,
                WateringConfirmActionStage.RECEIPT_LOOKUP_RESULT,
                WateringConfirmActionStage.FIXTURE_RECEIPT_EMIT,
                WateringConfirmActionStage.LISTENER_DELIVERY,
            )
    }
}
