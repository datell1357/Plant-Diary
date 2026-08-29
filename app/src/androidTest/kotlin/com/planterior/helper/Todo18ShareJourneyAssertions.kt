package com.planterior.helper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.planterior.helper.feature.share.MiniHomeShareTestTags
import com.planterior.helper.navigation.PlanteriorRoute

/** Assertions for expired and deleted share responses. */
internal fun Todo18MainActivityJourneyHarness.assertExpiredAndDeletedShareStates() {
    runtime.boundary.seedPlant()
    runtime.boundary.shareMode = Todo18ShareMode.EXPIRED
    events.navigateAndAwaitBoundary(
        route = PlanteriorRoute.MiniHomeShare,
        boundaryKind = "mini-home-loaded",
    )
    events.awaitBoundary("share-create") {
        compose.onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE).performClick()
    }
    compose.waitForIdle()
    compose.onNodeWithTag(MiniHomeShareTestTags.LINK_FAILURE).assertIsDisplayed()

    runtime.boundary.shareMode = Todo18ShareMode.DELETED
    events.navigateAndAwaitBoundary(
        route = PlanteriorRoute.MiniHome,
        boundaryKind = "mini-home-loaded",
    )
    events.navigateAndAwaitBoundary(
        route = PlanteriorRoute.MiniHomeShare,
        boundaryKind = "share-deleted",
    )
    compose.onNodeWithTag(MiniHomeShareTestTags.NO_TARGET).assertIsDisplayed()
    captureReceipt("share-expired-deleted", "expired,deleted")
}
