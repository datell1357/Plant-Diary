package com.planterior.helper.feature.minihome

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniHomeProjectionTest {
    private val projection = MiniHomeIsometricProjection(width = 1000f, height = 800f)

    @Test
    fun `every cell center round trips through the canonical inverse`() {
        for (row in 0 until MiniHomeGrid.ROWS) {
            for (column in 0 until MiniHomeGrid.COLUMNS) {
                val position = GridPosition(column, row)
                val center = projection.cellCenter(position)

                assertEquals(position, projection.positionAt(center))
                assertTrue(projection.floorBounds.contains(center))
            }
        }
    }

    @Test
    fun `inverse property maps every sampled point inside each diamond cell`() {
        val samples = listOf(0.05f, 0.25f, 0.5f, 0.75f, 0.95f)
        for (row in 0 until MiniHomeGrid.ROWS) {
            for (column in 0 until MiniHomeGrid.COLUMNS) {
                samples.forEach { columnOffset ->
                    samples.forEach { rowOffset ->
                        val point =
                            projection.pointAt(
                                (column + columnOffset) / MiniHomeGrid.COLUMNS,
                                (row + rowOffset) / MiniHomeGrid.ROWS,
                            )
                        assertEquals(
                            GridPosition(column, row),
                            projection.positionAt(point),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `all miniature bounds stay inside the responsive room and touch the cell anchor`() {
        for (row in 0 until MiniHomeGrid.ROWS) {
            for (column in 0 until MiniHomeGrid.COLUMNS) {
                val anchor = projection.cellCenter(GridPosition(column, row))
                val left = anchor.x - projection.miniatureWidth / 2f
                val top = anchor.y - projection.miniatureHeight
                assertTrue(left >= 0f)
                assertTrue(left + projection.miniatureWidth <= projection.width)
                assertTrue(top >= 0f)
                assertTrue(anchor.y <= projection.height)
            }
        }
    }

    @Test
    fun `inverse clamps points beyond every floor edge to a valid cell`() {
        val probes =
            listOf(
                projection.pointAt(-1f, -1f) to GridPosition(0, 0),
                projection.pointAt(2f, -1f) to GridPosition(4, 0),
                projection.pointAt(-1f, 2f) to GridPosition(0, 3),
                projection.pointAt(2f, 2f) to GridPosition(4, 3),
            )

        probes.forEach { (point, expected) ->
            assertEquals(expected, projection.positionAt(point))
        }
    }

    @Test
    fun `tile corners and placement anchors share exact projected coordinates`() {
        val position = GridPosition(2, 1)
        val tile = projection.cell(position)
        val center = projection.cellCenter(position)

        assertNear(center.x, (tile.back.x + tile.front.x) / 2f)
        assertNear(center.y, (tile.back.y + tile.front.y) / 2f)
        assertNear(center.x, (tile.left.x + tile.right.x) / 2f)
        assertNear(center.y, (tile.left.y + tile.right.y) / 2f)
    }

    @Test
    fun `canonical layering follows projected floor depth with stable ties`() {
        val placements =
            listOf(
                plant("right", GridPosition(4, 0)),
                plant("front", GridPosition(0, 3)),
                plant("back", GridPosition(0, 0)),
            )

        val layered = MiniHomePlacementPolicy.layer(placements)
        val projectedDepths = layered.map { projection.cellCenter(it.position).y }

        assertEquals(projectedDepths.sorted(), projectedDepths)
        assertEquals(listOf(0, 1, 2), layered.map { it.zIndex.value })
    }

    private fun plant(id: String, position: GridPosition) =
        MiniHomePlacement(
            com.planterior.helper.core.model.PlacementId(id),
            MiniHomePlacementTarget.Plant(
                com.planterior.helper.core.model.PersonalPlantId("plant-$id")
            ),
            position,
            MiniHomeZIndex(0),
        )

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue("Expected $expected, actual $actual", abs(expected - actual) < 0.001f)
    }
}
