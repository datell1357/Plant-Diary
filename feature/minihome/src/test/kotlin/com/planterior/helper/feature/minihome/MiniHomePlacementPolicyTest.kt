package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniHomePlacementPolicyTest {
    @Test
    fun `drag coordinates clamp to the room grid and layering follows depth`() {
        assertEquals(GridPosition(0, 0), GridPosition.fromNormalized(-1.0, -0.2))
        assertEquals(
            GridPosition(MiniHomeGrid.COLUMNS - 1, MiniHomeGrid.ROWS - 1),
            GridPosition.fromNormalized(2.0, 4.0),
        )
        val back = plant("back", "plant-back", GridPosition(4, 0))
        val front = plant("front", "plant-front", GridPosition(0, 3))

        val layered = MiniHomePlacementPolicy.layer(listOf(front, back))

        assertEquals(listOf("front", "back"), layered.map { it.id.value })
        assertEquals(listOf(0, 1), layered.map { it.zIndex.value })
    }

    @Test
    fun `occupied cells and duplicate entities are rejected`() {
        val first = plant("first", "plant-a", GridPosition(1, 1))
        val occupied = plant("second", "plant-b", GridPosition(1, 1))
        val duplicate = plant("third", "plant-a", GridPosition(2, 1))

        assertFalse(MiniHomePlacementPolicy.isValid(listOf(first, occupied)))
        assertFalse(MiniHomePlacementPolicy.isValid(listOf(first, duplicate)))
        assertTrue(MiniHomePlacementPolicy.isValid(listOf(first)))
    }

    @Test
    fun `contiguous but non-projected z order is rejected`() {
        val projectedBack = plant("projected-back", "plant-a", GridPosition(0, 1))
        val projectedFront = plant("projected-front", "plant-b", GridPosition(4, 0))
        val wrong =
            listOf(
                projectedBack.copy(zIndex = MiniHomeZIndex(1)),
                projectedFront.copy(zIndex = MiniHomeZIndex(0)),
            )

        assertFalse(MiniHomePlacementPolicy.isValid(wrong))
        assertTrue(MiniHomePlacementPolicy.isValid(MiniHomePlacementPolicy.layer(wrong)))
    }

    @Test
    fun `typed placement boundaries reject malformed values`() {
        assertThrows(IllegalArgumentException::class.java) { GridPosition(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            MiniHomeZIndex(-1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MiniHomeNormalizedCoordinate(Double.NaN)
        }
    }

    private fun plant(id: String, plantId: String, position: GridPosition) =
        MiniHomePlacement(
            PlacementId(id),
            MiniHomePlacementTarget.Plant(PersonalPlantId(plantId)),
            position,
            MiniHomeZIndex(0),
        )
}
