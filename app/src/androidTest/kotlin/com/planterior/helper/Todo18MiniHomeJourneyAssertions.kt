package com.planterior.helper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.planterior.helper.feature.minihome.MiniHomeTestTags
import com.planterior.helper.navigation.PlanteriorRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/** Assertions for persisted replay and revision-conflict mini-home journeys. */
internal fun Todo18MainActivityJourneyHarness.assertOfflineMiniHomeReplayUsesPersistedOperation() {
    val plantId = runtime.boundary.seedPlant()
    runtime.boundary.miniHomeSaveMode = Todo18MiniHomeSaveMode.OFFLINE_ONCE
    events.navigateAndAwaitBoundary(
        route = PlanteriorRoute.MiniHome,
        boundaryKind = "mini-home-loaded",
    )
    compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
    compose.onNodeWithTag(MiniHomeTestTags.plant(plantId)).performScrollTo().performClick()

    events.awaitBoundary("mini-home-save-attempt") {
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
    }
    compose.waitForIdle()
    compose.onNodeWithTag(MiniHomeTestTags.SAVE_FAILURE).assertIsDisplayed()
    val frozen = runtime.boundary.miniHomeSaveRequests.single().operationId
    assertNotNull(
        runBlocking {
            runtime.database
                .syncDao()
                .operation(Todo18IntegratedRuntimeRule.ACCOUNT_UID, frozen.value)
        }
    )

    events.awaitBoundary("mini-home-committed") {
        compose.onNodeWithTag(MiniHomeTestTags.RETRY).performScrollTo().performClick()
    }
    compose.waitForIdle()
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
    events.navigateAndAwaitBoundary(
        route = PlanteriorRoute.MiniHome,
        boundaryKind = "mini-home-loaded",
    )
    compose.onNodeWithTag(MiniHomeTestTags.EDIT).performScrollTo().performClick()
    compose.onNodeWithTag(MiniHomeTestTags.plant(plantId)).performScrollTo().performClick()
    events.awaitBoundary("mini-home-save-attempt") {
        compose.onNodeWithTag(MiniHomeTestTags.SAVE).performScrollTo().performClick()
    }
    compose.waitForIdle()
    compose.onNodeWithTag(MiniHomeTestTags.CONFLICT).assertIsDisplayed()
    compose.onNodeWithTag(MiniHomeTestTags.RETRY).assertIsDisplayed()
    assertEquals(1, runtime.boundary.miniHomeSaveRequests.size)
    captureReceipt(
        "revision-conflict",
        runtime.boundary.miniHomeSaveRequests.single().operationId.value,
    )
}
