package com.planterior.helper.action

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.ROBOLECTRIC_MAX_SDK
import com.planterior.helper.Todo18BoundaryEvent
import com.planterior.helper.Todo18MiniHomeRepositoryFixture
import com.planterior.helper.Todo18MiniHomeSaveMode
import com.planterior.helper.Todo18Scenario
import com.planterior.helper.core.database.PlanteriorDatabase
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.minihome.FirebaseMiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomeRoute
import com.planterior.helper.feature.minihome.MiniHomeSaveActionDecision
import com.planterior.helper.feature.minihome.MiniHomeSaveActionDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeSaveActionObservation
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.minihome.MiniHomeSaveState
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRecorder
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRepository
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
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
class MiniHomeSaveActionEntryTraceTest {
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
        MiniHomeSaveActionDiagnostics.installationMustBeClear()
        database.close()
    }

    @Test
    fun `Conflict SAVE traces semantics to exact fixture listener in order`() {
        assertSaveTrace(Todo18MiniHomeSaveMode.REVISION_CONFLICT)
    }

    @Test
    fun `Offline SAVE traces semantics to exact fixture listener in order`() {
        assertSaveTrace(Todo18MiniHomeSaveMode.OFFLINE_ONCE)
    }

    private fun assertSaveTrace(mode: Todo18MiniHomeSaveMode) {
        val scenario = Todo18Scenario(AccountId("action-trace-owner"))
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
        val trace = OrderedActionStageTrace(MiniHomeSaveActionStage.entries)
        val boundaryEvents = mutableListOf<Todo18BoundaryEvent>()
        val diagnostic = MiniHomeSaveActionDiagnostics.install { observation ->
            observations += observation
            trace.record(observation.stage)
        }
        val boundary: Closeable = scenario.subscribe { event ->
            if (event.kind == "mini-home-save-attempt") boundaryEvents += event
        }
        val rawStates = mutableListOf<MiniHomeUiState>()
        val routeReady = AtomicBoolean(false)
        val routeIdlingResource = ActionPathIdlingResource(routeReady::get)
        assertEquals(1, scenario.listenerCount())
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
                        },
                    )
                }
            }
            compose.registerIdlingResource(routeIdlingResource)
            compose.waitForIdle()

            compose.onNodeWithTag(MiniHomeTestTags.EDIT).performClick()
            compose.onNodeWithTag(MiniHomeTestTags.plant(plantId)).performClick()
            recordSaveSemantics(trace)
            compose.onNodeWithTag(MiniHomeTestTags.SAVE).performClick()
            compose.waitForIdle()
            val editing = rawStates.last() as MiniHomeUiState.Editing
            val saveState = editing.saveState
            when (mode) {
                Todo18MiniHomeSaveMode.REVISION_CONFLICT -> {
                    assertTrue(
                        "expected conflict reconciliation, got $saveState",
                        saveState is MiniHomeSaveState.ReconciliationRequired,
                    )
                    assertEquals(
                        com.planterior.helper.feature.minihome.MiniHomeSaveFailure
                            .REVISION_CONFLICT,
                        (saveState as MiniHomeSaveState.ReconciliationRequired).failure,
                    )
                }
                Todo18MiniHomeSaveMode.OFFLINE_ONCE ->
                    assertTrue(
                        "expected Failed, got $saveState",
                        saveState is MiniHomeSaveState.Failed,
                    )
                Todo18MiniHomeSaveMode.APPLY -> error("not an action-entry failure mode")
            }

            trace.requireComplete()
            val request = scenario.miniHomeSaveRequests.single()
            assertEquals(
                setOf(plantId),
                request.layout.placements
                    .mapNotNull { it.target as? MiniHomePlacementTarget.Plant }
                    .map { it.plantId }
                    .toSet(),
            )
            val boundaryEvent = boundaryEvents.single()
            assertEquals(request.operationId.value, boundaryEvent.identity)
            assertEquals(
                setOf(request.operationId),
                observations.mapNotNull(MiniHomeSaveActionObservation::operationId).toSet(),
            )
            assertEquals(
                listOf(
                    MiniHomeSaveActionDecision.ACCEPTED,
                    MiniHomeSaveActionDecision.ACCEPTED,
                ),
                observations.mapNotNull(MiniHomeSaveActionObservation::decision),
            )
            assertEquals(1, scenario.miniHomeSaveRequests.size)
        } finally {
            compose.unregisterIdlingResource(routeIdlingResource)
            boundary.close()
            diagnostic.close()
        }
        assertEquals(0, scenario.listenerCount())
        assertEquals(0, MiniHomeSaveActionDiagnostics.listenerCount())
    }

    private fun recordSaveSemantics(trace: OrderedActionStageTrace<MiniHomeSaveActionStage>) {
        val nodes = compose.onAllNodesWithTag(MiniHomeTestTags.SAVE).fetchSemanticsNodes()
        assertEquals(1, nodes.size)
        trace.record(MiniHomeSaveActionStage.SAVE_NODE_COUNT)
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).assertIsDisplayed()
        trace.record(MiniHomeSaveActionStage.SAVE_NODE_DISPLAYED)
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).assertIsEnabled()
        trace.record(MiniHomeSaveActionStage.SAVE_NODE_ENABLED)
        assertTrue(nodes.single().config.contains(SemanticsActions.OnClick))
        trace.record(MiniHomeSaveActionStage.SAVE_NODE_ON_CLICK)
    }

    private fun MiniHomeSaveActionDiagnostics.installationMustBeClear() {
        check(listenerCount() == 0) { "MiniHome action diagnostic listener leaked" }
    }
}
