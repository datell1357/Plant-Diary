package com.planterior.helper.feature.collection

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.model.PersonalPlantId
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [36],
    qualifiers = "w402dp-h874dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
class PlantThumbnailTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `non null path renders loading then failure placeholder without Firebase in UI`() {
        val result = CompletableDeferred<Bitmap>()
        show(path = "plant-photos/account-a/plant-a/representative.jpg") { result.await() }

        composeRule
            .onNodeWithTag(CollectionTestTags.THUMBNAIL_LOADING, useUnmergedTree = true)
            .assertIsDisplayed()

        composeRule.runOnIdle {
            result.completeExceptionally(IllegalStateException("unavailable"))
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(CollectionTestTags.THUMBNAIL_FAILURE, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(CollectionTestTags.THUMBNAIL_PLACEHOLDER, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `non null path renders decoded image after exact loader completion`() {
        val result = CompletableDeferred<Bitmap>()
        show(path = "plant-photos/account-a/plant-a/representative.jpg") { result.await() }

        composeRule.runOnIdle {
            result.complete(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(CollectionTestTags.THUMBNAIL_IMAGE, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun `cached loader bounds source bytes decodes to target size and reads a path once`() =
        runTest {
            var calls = 0
            var requestedMaximum = 0L
            val source = ThumbnailByteSource { _, maximumBytes ->
                calls++
                requestedMaximum = maximumBytes
                png(width = 512, height = 512)
            }
            val loader = CachedPlantThumbnailLoader(source)

            val first = loader.load("plant-photos/account-a/plant-a/representative.png")
            val second = loader.load("plant-photos/account-a/plant-a/representative.png")

            assertEquals(MAX_THUMBNAIL_SOURCE_BYTES, requestedMaximum)
            assertEquals(1, calls)
            assertTrue(first.width <= THUMBNAIL_TARGET_PIXELS)
            assertTrue(first.height <= THUMBNAIL_TARGET_PIXELS)
            assertTrue(first === second)
        }

    private fun show(path: String, loader: PlantThumbnailLoader) {
        composeRule.setContent {
            com.planterior.helper.core.designsystem.theme.PlanteriorTheme {
                CollectionScreen(
                    state =
                        CollectionUiState.Content(
                            listOf(CollectionPlant(PersonalPlantId("plant-a"), "몬스테라", path)),
                            stale = false,
                        ),
                    listPosition = CollectionListPosition.ZERO,
                    onListPositionChanged = { _, _ -> },
                    onOpenPlant = {},
                    onIdentify = {},
                    onRegisterDirectly = {},
                    onRetry = {},
                    thumbnailLoader = loader,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun png(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
}
