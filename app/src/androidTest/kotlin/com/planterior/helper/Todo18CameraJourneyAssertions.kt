package com.planterior.helper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.planterior.helper.feature.camera.CameraTestTags
import com.planterior.helper.feature.camera.Todo18DebugPhotoPreparationTerminal
import com.planterior.helper.navigation.PlanteriorRoute
import org.junit.Assert.assertTrue

/** Assertions for camera permission and malformed picker boundary failures. */
internal fun Todo18MainActivityJourneyHarness.assertCameraPermissionDenial() {
    runtime.denyCameraPermission()
    navigateDirectly(PlanteriorRoute.Camera)
    compose.onNodeWithTag(CameraTestTags.CAMERA).performClick()
    compose.onNodeWithText("카메라 권한이 필요해요").assertIsDisplayed()
    compose.onNodeWithTag(CameraTestTags.SETTINGS).assertIsDisplayed()
    captureReceipt("camera-permission-denied", "permission-denied")
}

internal fun Todo18MainActivityJourneyHarness.assertMalformedPickerUriRejected() {
    runtime.returnMalformedPickerUri()
    navigateDirectly(PlanteriorRoute.Camera)
    val event = events.awaitRejectedPhoto {
        compose.onNodeWithTag(CameraTestTags.PICKER).performClick()
    }
    assertTrue(
        "Unexpected photo preparation terminal: ${event.terminal}",
        event.terminal is Todo18DebugPhotoPreparationTerminal.Returned,
    )
    val terminal = event.terminal as Todo18DebugPhotoPreparationTerminal.Returned
    assertTrue(terminal.accepted == false)
    compose.waitForIdle()
    compose.onNodeWithText("사진을 찾을 수 없어요. 다른 사진을 선택해 주세요.").assertIsDisplayed()
    compose.onNodeWithTag(CameraTestTags.PICKER).assertIsDisplayed()
    captureReceipt("camera-malformed-uri", "malformed-uri")
}
