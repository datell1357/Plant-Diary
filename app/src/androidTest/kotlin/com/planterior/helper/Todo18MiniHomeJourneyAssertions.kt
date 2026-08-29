package com.planterior.helper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.planterior.helper.diagnostic.Todo18WaitId
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomeSaveState
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.feature.minihome.MiniHomeUiState
import com.planterior.helper.navigation.PlanteriorRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/** Assertions for persisted replay and revision-conflict mini-home journeys. */
internal fun Todo18MainActivityJourneyHarness.assertOfflineMiniHomeReplayUsesPersistedOperation() {
    val plantId = runtime.boundary.seedPlant()
    runtime.boundary.miniHomeSaveMode = Todo18MiniHomeSaveMode.OFFLINE_ONCE
    Todo18TransitionDiagnosticCapture(
            runtime,
            compose,
            Todo18WaitId.OFFLINE_INITIAL_VIEWING,
        )
        .run(
            wait = { observer ->
                rendered.awaitMiniHome(
                    matches = { it.state is MiniHomeUiState.Viewing },
                    trigger = { navigateDirectly(PlanteriorRoute.MiniHome) },
                    observer = observer,
                )
            },
            uiPostcondition = {
                compose.onNodeWithTag(MiniHomeTestTags.EDIT).assertIsDisplayed()
            },
        )
    Todo18TransitionDiagnosticCapture(
            runtime,
            compose,
            Todo18WaitId.OFFLINE_BEGIN_EDIT,
        )
        .run(
            wait = { observer ->
                rendered.awaitMiniHome(
                    matches = { it.state is MiniHomeUiState.Editing },
                    trigger = { compose.onNodeWithTag(MiniHomeTestTags.EDIT).performClick() },
                    observer = observer,
                )
            },
            uiPostcondition = {
                compose.onNodeWithTag(MiniHomeTestTags.SAVE).assertIsDisplayed()
            },
        )
    rendered.awaitMiniHome(
        matches = { event ->
            (event.state as? MiniHomeUiState.Editing)?.draft?.placements?.any {
                (it.target as? MiniHomePlacementTarget.Plant)?.plantId == plantId
            } == true
        },
        trigger = { compose.onNodeWithTag(MiniHomeTestTags.plant(plantId)).performClick() },
    )

    lateinit var saveAttempt: Todo18BoundaryEvent
    rendered.awaitMiniHome(
        matches = {
            (it.state as? MiniHomeUiState.Editing)?.saveState is MiniHomeSaveState.Failed
        },
        trigger = {
            saveAttempt =
                events.awaitBoundary("mini-home-save-attempt") {
                    compose.onNodeWithTag(MiniHomeTestTags.SAVE).performClick()
                }
        },
    )
    compose.onNodeWithTag(MiniHomeTestTags.SAVE_FAILURE).assertIsDisplayed()
    compose.onNodeWithTag(MiniHomeTestTags.RETRY).assertIsDisplayed()
    val frozen = runtime.boundary.miniHomeSaveRequests.single().operationId
    assertEquals(frozen.value, saveAttempt.identity)
    assertNotNull(
        runBlocking {
            runtime.database
                .syncDao()
                .operation(Todo18IntegratedRuntimeRule.ACCOUNT_UID, frozen.value)
        }
    )

    lateinit var committed: Todo18BoundaryEvent
    rendered.awaitMiniHome(
        matches = {
            ((it.state as? MiniHomeUiState.Viewing)?.committed?.revision?.value ?: 0L) > 1L
        },
        trigger = {
            committed =
                events.awaitBoundary("mini-home-committed") {
                    compose.onNodeWithTag(MiniHomeTestTags.RETRY).performClick()
                }
        },
    )
    assertEquals(frozen.value, committed.identity)
    assertEquals(
        listOf(frozen, frozen),
        runtime.boundary.miniHomeSaveRequests.map { it.operationId },
    )
    assertEquals(
        null,
        runBlocking {
            runtime.database
                .syncDao()
                .operation(Todo18IntegratedRuntimeRule.ACCOUNT_UID, frozen.value)
        },
    )
    compose.onNodeWithText("저장했어요").assertIsDisplayed()
    captureReceipt("offline-exact-replay", frozen.value)
}

internal fun Todo18MainActivityJourneyHarness.assertMiniHomeConflictPreservesDraft() {
    val plantId = runtime.boundary.seedPlant()
    runtime.boundary.miniHomeSaveMode = Todo18MiniHomeSaveMode.REVISION_CONFLICT
    Todo18MiniHomeInitialLoadDiagnosticCapture(runtime, compose).captureConflictInitialLoad {
        rendered.awaitMiniHome(
            matches = { it.state is MiniHomeUiState.Viewing },
            trigger = { navigateDirectly(PlanteriorRoute.MiniHome) },
        )
    }
    Todo18TransitionDiagnosticCapture(
            runtime,
            compose,
            Todo18WaitId.CONFLICT_BEGIN_EDIT,
        )
        .run(
            wait = { observer ->
                rendered.awaitMiniHome(
                    matches = { it.state is MiniHomeUiState.Editing },
                    trigger = { compose.onNodeWithTag(MiniHomeTestTags.EDIT).performClick() },
                    observer = observer,
                )
            },
            uiPostcondition = {
                compose.onNodeWithTag(MiniHomeTestTags.SAVE).assertIsDisplayed()
            },
        )
    rendered.awaitMiniHome(
        matches = { event ->
            (event.state as? MiniHomeUiState.Editing)?.draft?.placements?.any {
                (it.target as? MiniHomePlacementTarget.Plant)?.plantId == plantId
            } == true
        },
        trigger = { compose.onNodeWithTag(MiniHomeTestTags.plant(plantId)).performClick() },
    )
    lateinit var saveAttempt: Todo18BoundaryEvent
    rendered.awaitMiniHome(
        matches = {
            (it.state as? MiniHomeUiState.Editing)?.saveState is MiniHomeSaveState.Conflict
        },
        trigger = {
            saveAttempt =
                events.awaitBoundary("mini-home-save-attempt") {
                    compose.onNodeWithTag(MiniHomeTestTags.SAVE).performClick()
                }
        },
    )
    compose.onNodeWithTag(MiniHomeTestTags.CONFLICT).assertIsDisplayed()
    compose.onNodeWithTag(MiniHomeTestTags.RETRY).assertIsDisplayed()
    assertEquals(1, runtime.boundary.miniHomeSaveRequests.size)
    val operationId = runtime.boundary.miniHomeSaveRequests.single().operationId
    assertEquals(operationId.value, saveAttempt.identity)
    captureReceipt("revision-conflict", operationId.value)
}
