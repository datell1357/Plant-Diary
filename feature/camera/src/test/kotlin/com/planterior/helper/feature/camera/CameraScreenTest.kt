package com.planterior.helper.feature.camera

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav")
class CameraScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `source screen provides camera picker and direct registration alternatives`() {
        show(CameraFlowState.Source())

        listOf(CameraTestTags.CAMERA, CameraTestTags.PICKER, CameraTestTags.DIRECT).forEach {
            composeRule.onNodeWithTag(it).assertIsDisplayed().assertTouchHeightIsEqualTo(48.dp)
        }
    }

    @Test
    fun `permission denial shows settings picker and direct alternatives`() {
        show(CameraFlowState.PermissionBlocked(permanentlyDenied = true))

        listOf(CameraTestTags.SETTINGS, CameraTestTags.PICKER, CameraTestTags.DIRECT).forEach {
            composeRule.onNodeWithTag(it).assertIsDisplayed()
        }
    }

    @Test
    fun `capture screen exposes a square accessible shutter without wrapping text`() {
        show(CameraFlowState.Capturing("content://authority/camera/photo.jpg"))

        composeRule
            .onNodeWithTag(CameraTestTags.CAPTURE)
            .assertContentDescriptionEquals("촬영")
            .assertTouchWidthIsEqualTo(72.dp)
            .assertTouchHeightIsEqualTo(72.dp)
    }

    @Test
    fun `capture screen provides framing guide gallery flash and close actions`() {
        val actions = mutableListOf<String>()
        show(
            CameraFlowState.Capturing("content://authority/camera/photo.jpg"),
            onPicker = { actions += "picker" },
            onFlash = { actions += "flash" },
        )

        listOf(
                CameraTestTags.CAPTURE_GUIDE,
                CameraTestTags.PICKER,
                CameraTestTags.FLASH,
                CameraTestTags.CLOSE,
            )
            .forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        composeRule.onNodeWithTag(CameraTestTags.PICKER).performClick()
        composeRule.onNodeWithTag(CameraTestTags.FLASH).performClick()

        assertEquals(listOf("picker", "flash"), actions)
    }

    @Test
    fun `review invokes replace retake and disclosure actions`() {
        val actions = mutableListOf<String>()
        show(
            CameraFlowState.Review(photo()),
            onReplace = { actions += "replace" },
            onRetake = { actions += "retake" },
            onSubmit = { actions += "submit" },
        )

        composeRule.onNodeWithTag(CameraTestTags.REPLACE).performClick()
        composeRule.onNodeWithTag(CameraTestTags.RETAKE).performClick()
        composeRule.onNodeWithTag(CameraTestTags.SUBMIT).performClick()

        assertEquals(listOf("replace", "retake", "submit"), actions)
    }

    @Test
    fun `disclosure names purpose remote processor and 24 hour lifecycle`() {
        show(
            CameraFlowState.Disclosure(
                photo = photo(),
                requestId = "request-1",
                disclosure = PhotoDisclosure.Product,
            )
        )

        composeRule.onNodeWithTag(CameraTestTags.DISCLOSURE).assertIsDisplayed()
        composeRule.onNodeWithTag(CameraTestTags.APPROVE).assertIsDisplayed()
        composeRule.onNodeWithTag(CameraTestTags.CANCEL).assertIsDisplayed()
    }

    private fun show(
        state: CameraFlowState,
        onReplace: () -> Unit = {},
        onRetake: () -> Unit = {},
        onSubmit: () -> Unit = {},
        onPicker: () -> Unit = {},
        onFlash: () -> Unit = {},
    ) {
        composeRule.setContent {
            PlanteriorTheme {
                CameraScreen(
                    state = state,
                    preview = null,
                    onCamera = {},
                    onPicker = onPicker,
                    onDirect = {},
                    onSettings = {},
                    onCapture = {},
                    onCloseCapture = {},
                    onReplace = onReplace,
                    onRetake = onRetake,
                    onSubmit = onSubmit,
                    onApprove = {},
                    onCancelDisclosure = {},
                    onBack = {},
                    onFlash = onFlash,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun photo() =
        PreparedPhoto(
            privateUri = "content://authority/camera/photo.jpg",
            mime = PhotoMime.Jpeg,
            byteSize = 1024,
            width = 1200,
            height = 900,
            rotationDegrees = 90,
            source = PhotoSource.Camera,
        )
}
