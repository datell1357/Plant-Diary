package com.planterior.helper.feature.share

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeGrid
import com.planterior.helper.feature.minihome.MiniHomeIsometricProjection
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeShareCaptureTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `capture surface renders the committed layout only`() {
        val target = MiniHomeShareFixtures.target(7)
        compose.setContent {
            PlanteriorTheme {
                MiniHomeShareCaptureSurface(
                    target = target,
                    modifier = Modifier.size(360.dp, 300.dp),
                )
            }
        }

        compose.onNodeWithTag(MiniHomeShareTestTags.CAPTURE).assertIsDisplayed()
        target.committed.placements.forEach { placement ->
            compose
                .onNodeWithTag(MiniHomeShareTestTags.capturePlacement(placement.id))
                .assertExists()
        }
    }

    @Test
    fun `capture geometry equals the canonical mini home projection at the export size`() {
        val exportProjection =
            MiniHomeIsometricProjection(
                MiniHomeShareImage.WIDTH_PX.toFloat(),
                MiniHomeShareImage.HEIGHT_PX.toFloat(),
            )
        val previewProjection = MiniHomeIsometricProjection(600f, 500f)
        val scale = MiniHomeShareImage.WIDTH_PX.toFloat() / 600f

        for (row in 0 until MiniHomeGrid.ROWS) {
            for (column in 0 until MiniHomeGrid.COLUMNS) {
                val position = com.planterior.helper.feature.minihome.GridPosition(column, row)
                val preview = previewProjection.cellCenter(position)
                val export = exportProjection.cellCenter(position)

                assertTrue(abs(preview.x * scale - export.x) < 0.01f)
                assertTrue(abs(preview.y * scale - export.y) < 0.01f)
            }
        }
    }

    @Test
    fun `export request is derived from the committed revision only`() {
        val target = MiniHomeShareFixtures.target(11)

        val request = MiniHomeShareExportRequest.of(target)

        assertEquals(Revision(11), request.revision)
        assertEquals(MiniHomeShareImage.fileName(Revision(11)), request.fileName)
        assertEquals(MiniHomeShareImage.WIDTH_PX, request.widthPx)
        assertEquals(MiniHomeShareImage.HEIGHT_PX, request.heightPx)
    }
}
