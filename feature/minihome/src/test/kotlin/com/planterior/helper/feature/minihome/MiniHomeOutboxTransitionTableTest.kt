package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MiniHomeOutboxTransitionTableTest {
    @Test
    fun `payload hash matches callable canonical json contract`() {
        val layout =
            MiniHomeLayout(
                MiniHomeId("home-a"),
                "저장된 방",
                listOf(
                    MiniHomePlacement(
                        PlacementId("placement-a"),
                        MiniHomePlacementTarget.Plant(PersonalPlantId("plant-a")),
                        GridPosition(2, 2),
                        MiniHomeZIndex(0),
                    )
                ),
                Revision(3),
                Instant.ofEpochMilli(3),
            )

        assertEquals(
            "93883e4c557a94029397d2d8f15d79d26f3f47ee4a11c2d06c0816557ad5bcdd",
            MiniHomePayloadHash.create(Revision(3), layout),
        )
    }

    @Test
    fun `state and reason transition table is exhaustive and only transient rows transmit`() {
        val reasons = listOf(null) + MiniHomeSaveFailure.entries
        val expectedRows = MiniHomeOutboxPhase.entries.size * reasons.size

        assertEquals(expectedRows, MiniHomeOutboxTransitionTable.rows.size)
        assertEquals(
            expectedRows,
            MiniHomeOutboxTransitionTable.rows.map { it.phase to it.reason }.distinct().size,
        )

        MiniHomeOutboxTransitionTable.rows.forEach { row ->
            val expectedTransmission =
                row.phase != MiniHomeOutboxPhase.RECONCILIATION_REQUIRED &&
                    (row.reason == null || row.reason.retryable)
            assertEquals(
                "${row.phase} with ${row.reason}",
                expectedTransmission,
                row.mayTransmitExactRequest,
            )
            if (row.reason?.permanent == true) {
                assertFalse(row.mayTransmitExactRequest)
                assertEquals(
                    row.reason.requiresCorrection,
                    row.requiresCorrection,
                )
                assertEquals(
                    row.reason.requiresReconciliation,
                    row.requiresExplicitReconciliation,
                )
            }
        }
    }
}
