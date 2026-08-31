package com.planterior.helper.action

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.planterior.helper.feature.minihome.MiniHomeCacheConflictDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeCacheDiagnosticOutcome
import com.planterior.helper.feature.minihome.MiniHomeCacheDiagnosticStage
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomeRepository
import com.planterior.helper.feature.minihome.MiniHomeRetryDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeRetryObservation
import com.planterior.helper.feature.minihome.MiniHomeRetryStage
import com.planterior.helper.feature.minihome.MiniHomeRoute
import com.planterior.helper.feature.minihome.MiniHomeSaveActionDecision
import com.planterior.helper.feature.minihome.MiniHomeSaveActionDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeSaveActionObservation
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.minihome.MiniHomeSaveFailure
import com.planterior.helper.feature.minihome.MiniHomeSaveRequest
import com.planterior.helper.feature.minihome.MiniHomeSaveResult
import com.planterior.helper.feature.minihome.MiniHomeSaveResultDetails
import com.planterior.helper.feature.minihome.MiniHomeSaveState
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRecorder
import com.planterior.helper.minihome.Todo18MiniHomeLoadDiagnosticRepository
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun `Offline once production Room retry applies Saved revision 2 through raw Viewing`() {
        val scenario = Todo18Scenario(AccountId("offline-result-discriminator-owner"))
        scenario.miniHomeSaveMode = Todo18MiniHomeSaveMode.OFFLINE_ONCE
        val plantId = scenario.seedPlant()
        val retryReturned = AtomicBoolean(false)
        val loadDiagnostics =
            Todo18MiniHomeLoadDiagnosticRecorder(scenario::emitMiniHomeLoadDiagnostic)
        val repository =
            ActionPathMiniHomeRepository(
                Todo18MiniHomeLoadDiagnosticRepository(
                    FirebaseMiniHomeRepository(
                        database,
                        Todo18MiniHomeRepositoryFixture(scenario, loadDiagnostics),
                        scenario::now,
                    ),
                    loadDiagnostics,
                ),
                onSaveReturned = { retryReturned.set(true) },
            )
        val retryObservations = mutableListOf<MiniHomeRetryObservation>()
        val rawStates = mutableListOf<MiniHomeUiState>()
        val committed = mutableListOf<Todo18BoundaryEvent>()
        val retryDiagnostic = MiniHomeRetryDiagnostics.install(retryObservations::add)
        var cacheDiagnostic: Closeable? = null
        val boundary = scenario.subscribe { event ->
            if (event.kind == "mini-home-committed") committed += event
        }
        val routeReady = AtomicBoolean(false)
        val routeIdlingResource = ActionPathIdlingResource(routeReady::get)
        val retryIdlingResource = ActionPathIdlingResource(retryReturned::get)

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
            compose.onNodeWithTag(MiniHomeTestTags.SAVE).performClick()
            compose.waitForIdle()

            val repositoryReturns = retryObservations.filter {
                it.stage == MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED
            }
            assertEquals("observed=$repositoryReturns", 1, repositoryReturns.size)
            val failed = repositoryReturns.single()
            assertEquals(
                MiniHomeSaveResultDetails.Failed(
                    MiniHomeSaveFailure.NETWORK,
                    hasDiscardHandle = true,
                ),
                failed.resultDetails,
            )
            val firstRequest = scenario.miniHomeSaveRequests.single()
            val cacheRecorder =
                com.planterior.helper.feature.minihome.MiniHomeCacheDiagnosticRecorder(
                    scenario.accountId,
                    firstRequest.operationId,
                )
            cacheDiagnostic = MiniHomeCacheConflictDiagnostics.install(cacheRecorder)
            assertEquals(firstRequest.operationId, failed.operationId)
            assertFalse(
                retryObservations.any {
                    it.stage == MiniHomeRetryStage.SAVED_APPLY_ENTRY
                }
            )

            retryReturned.set(false)
            compose.onNodeWithTag(MiniHomeTestTags.RETRY).performScrollTo().performClick()
            compose.registerIdlingResource(retryIdlingResource)
            compose.waitForIdle()

            assertEquals(
                listOf(firstRequest.operationId, firstRequest.operationId),
                scenario.miniHomeSaveRequests.map { it.operationId },
            )
            assertEquals("observed=$committed", 1, committed.size)
            assertEquals(firstRequest.operationId.value, committed.single().identity)
            assertEquals(
                listOf(
                    MiniHomeSaveResultDetails.Failed(
                        MiniHomeSaveFailure.NETWORK,
                        hasDiscardHandle = true,
                    ),
                    MiniHomeSaveResultDetails.Saved(
                        layoutId = "todo18-home",
                        revision = 2,
                    ),
                ),
                retryObservations
                    .filter { it.stage == MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED }
                    .map { it.resultDetails },
            )
            val retryStages =
                retryObservations
                    .filter { it.operationId == firstRequest.operationId }
                    .map { it.stage }
            assertEquals(1, retryStages.count { it == MiniHomeRetryStage.CALLBACK_ENTRY })
            assertEquals(1, retryStages.count { it == MiniHomeRetryStage.COROUTINE_ENTRY })
            assertEquals(1, retryStages.count { it == MiniHomeRetryStage.COROUTINE_RETURNED })
            assertEquals(2, retryStages.count { it == MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY })
            assertEquals(2, retryStages.count { it == MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED })
            assertEquals(1, retryStages.count { it == MiniHomeRetryStage.SAVED_APPLY_ENTRY })
            assertEquals(1, retryStages.count { it == MiniHomeRetryStage.SET_STATE_ATTEMPTED })
            assertEquals(1, retryStages.count { it == MiniHomeRetryStage.SET_STATE_APPLIED })
            assertTrue(retryStages.none { it == MiniHomeRetryStage.SAVED_APPLY_REJECTED })
            assertTrue(retryStages.none { it == MiniHomeRetryStage.SET_STATE_REJECTED })
            assertTrue(
                retryStages.indexOf(MiniHomeRetryStage.SAVED_APPLY_ENTRY) <
                    retryStages.indexOf(MiniHomeRetryStage.SET_STATE_ATTEMPTED)
            )
            assertTrue(
                retryStages.indexOf(MiniHomeRetryStage.SET_STATE_ATTEMPTED) <
                    retryStages.indexOf(MiniHomeRetryStage.SET_STATE_APPLIED)
            )
            val finalViewing = rawStates.filterIsInstance<MiniHomeUiState.Viewing>().last()
            assertEquals(2, finalViewing.committed.revision.value)
            assertEquals(firstRequest.operationId, finalViewing.exitOutcome?.operationId)
            cacheDiagnostic.close()
            cacheDiagnostic = null
            val retryCache = cacheRecorder.close().observations
            assertEquals(
                listOf(
                    MiniHomeCacheDiagnosticStage.LAYOUT_APPLY,
                    MiniHomeCacheDiagnosticStage.INVENTORY_APPLY,
                    MiniHomeCacheDiagnosticStage.CURRENT_SNAPSHOT,
                    MiniHomeCacheDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                ),
                retryCache.map { it.stage },
            )
            assertEquals(
                listOf(
                    MiniHomeCacheDiagnosticOutcome.APPLIED,
                    MiniHomeCacheDiagnosticOutcome.APPLIED,
                    MiniHomeCacheDiagnosticOutcome.VERIFIED,
                    MiniHomeCacheDiagnosticOutcome.VERIFIED,
                ),
                retryCache.map { it.outcome },
            )
            assertTrue(retryCache.all { it.accountId == scenario.accountId })
            assertTrue(retryCache.all { it.category == null && it.predicate == null })
            compose.onNodeWithTag(MiniHomeTestTags.EDIT).assertIsDisplayed()
            compose.onAllNodesWithTag(MiniHomeTestTags.RETRY).assertCountEquals(0)
        } finally {
            cacheDiagnostic?.close()
            compose.unregisterIdlingResource(retryIdlingResource)
            compose.unregisterIdlingResource(routeIdlingResource)
            boundary.close()
            retryDiagnostic.close()
        }
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
        val savingObserved = AtomicBoolean(false)
        val terminalSaveObserved = AtomicBoolean(false)
        val routeIdlingResource = ActionPathIdlingResource(routeReady::get)
        val terminalSaveIdlingResource = ActionPathIdlingResource(terminalSaveObserved::get)
        var routeIdlingRegistered = false
        var terminalSaveIdlingRegistered = false
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
            val beforeSave = rawStates.last() as MiniHomeUiState.Editing
            recordSaveSemantics(trace)
            compose.onNodeWithTag(MiniHomeTestTags.SAVE).performClick()
            compose.registerIdlingResource(terminalSaveIdlingResource)
            terminalSaveIdlingRegistered = true
            compose.waitForIdle()
            val editing = rawStates.last() as MiniHomeUiState.Editing
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
                    assertEquals(beforeSave.operationId, editing.operationId)
                    assertEquals(beforeSave.draft, editing.draft)
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
            if (mode == Todo18MiniHomeSaveMode.OFFLINE_ONCE) {
                compose.onAllNodesWithTag(MiniHomeTestTags.RETRY).assertCountEquals(1)
                val retry = compose.onNodeWithTag(MiniHomeTestTags.RETRY)
                assertThrows(AssertionError::class.java) { retry.assertIsDisplayed() }
                retry.performScrollTo()
                retry.assertIsDisplayed()
            }
            if (mode == Todo18MiniHomeSaveMode.REVISION_CONFLICT) {
                compose.onAllNodesWithTag(MiniHomeTestTags.CONFLICT).assertCountEquals(0)
                compose.onAllNodesWithTag(MiniHomeTestTags.RETRY).assertCountEquals(0)
                compose.onAllNodesWithTag(MiniHomeTestTags.RECONCILE).assertCountEquals(1)
                val reconcile = compose.onNodeWithTag(MiniHomeTestTags.RECONCILE)
                reconcile.performScrollTo()
                reconcile.assertIsDisplayed()
            }
        } finally {
            if (terminalSaveIdlingRegistered) {
                compose.unregisterIdlingResource(terminalSaveIdlingResource)
            }
            if (routeIdlingRegistered) compose.unregisterIdlingResource(routeIdlingResource)
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

    private class ActionPathMiniHomeRepository(
        private val delegate: MiniHomeRepository,
        private val onSaveReturned: () -> Unit,
    ) : MiniHomeRepository by delegate {
        override suspend fun save(request: MiniHomeSaveRequest): MiniHomeSaveResult =
            delegate.save(request).also { onSaveReturned() }
    }
}
