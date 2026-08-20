package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniHomeRequestContractTest {
    @Test
    fun `every enumerated Unicode White Space code point is rejected at either name boundary`() {
        val whiteSpace =
            listOf(
                0x0009,
                0x000A,
                0x000B,
                0x000C,
                0x000D,
                0x0020,
                0x0085,
                0x00A0,
                0x1680,
                0x2000,
                0x2001,
                0x2002,
                0x2003,
                0x2004,
                0x2005,
                0x2006,
                0x2007,
                0x2008,
                0x2009,
                0x200A,
                0x2028,
                0x2029,
                0x202F,
                0x205F,
                0x3000,
            )

        assertEquals(whiteSpace.toSet(), MiniHomeRequestContract.UNICODE_WHITE_SPACE_CODE_POINTS)
        whiteSpace.forEach { codePoint ->
            val whitespace = String(Character.toChars(codePoint))
            assertEquals(
                "leading U+${codePoint.toString(16).uppercase()}",
                "name",
                MiniHomeRequestContract.validateName("${whitespace}valid")?.field,
            )
            assertEquals(
                "trailing U+${codePoint.toString(16).uppercase()}",
                "name",
                MiniHomeRequestContract.validateName("valid$whitespace")?.field,
            )
        }
    }

    @Test
    fun `name parity corpus covers normalization length surrogate control and bidi boundaries`() {
        val valid =
            listOf(
                "A",
                "가나다",
                "é",
                "A B",
                "A\u00A0B",
                "😀".repeat(100),
            )
        val bidiControls =
            setOf(
                0x061C,
                0x200E,
                0x200F,
                0x202A,
                0x202B,
                0x202C,
                0x202D,
                0x202E,
                0x2066,
                0x2067,
                0x2068,
                0x2069,
            )
        assertEquals(bidiControls, MiniHomeRequestContract.BIDI_CONTROL_CODE_POINTS)
        val invalid =
            listOf(
                "",
                "e\u0301",
                "😀".repeat(101),
                "\uD800",
                "\uDC00",
                "A\u0000B",
                "A\u001FB",
                "A\u007FB",
                "A\u0085B",
                "A\u061CB",
                "A\u200EB",
                "A\u200FB",
                "A\u202AB",
                "A\u202BB",
                "A\u202CB",
                "A\u202DB",
                "A\u202EB",
                "A\u2066B",
                "A\u2067B",
                "A\u2068B",
                "A\u2069B",
            )

        valid.forEach { assertNull(it, MiniHomeRequestContract.validateName(it)) }
        invalid.forEach {
            assertEquals(it, "name", MiniHomeRequestContract.validateName(it)?.field)
        }
    }

    @Test
    fun `request contract rejects every representable server boundary drift`() {
        val valid = request(layout())
        val layered =
            MiniHomePlacementPolicy.layer(
                listOf(
                    placement("placement-a", "plant-a", GridPosition(0, 0)),
                    placement("placement-b", "plant-b", GridPosition(4, 3)),
                )
            )
        val cases =
            listOf(
                "empty name" to request(layout().copy(name = "")),
                "surrounding whitespace" to request(layout().copy(name = " surrounded ")),
                "revision mismatch" to valid.copy(expectedRevision = Revision(2)),
                "unsafe revision" to
                    valid.copy(
                        expectedRevision = Revision(9_007_199_254_740_991L),
                        layout = valid.layout.copy(revision = Revision(9_007_199_254_740_991L)),
                    ),
                "array order differs from z order" to
                    request(layout().copy(placements = layered.reversed())),
            )

        cases.forEach { (label, candidate) ->
            assertTrue(label, MiniHomeRequestContract.validate(candidate) != null)
        }
        assertNull(MiniHomeRequestContract.validate(valid))
    }

    @Test
    fun `typed ids coordinates counts and unicode size share the server limits`() {
        val invalidIds = listOf("", "bad/id", "x".repeat(129))
        invalidIds.forEach { value ->
            assertTrue(runCatching { MiniHomeId(value) }.isFailure)
            assertTrue(runCatching { PlacementId(value) }.isFailure)
            assertTrue(runCatching { PersonalPlantId(value) }.isFailure)
            assertTrue(runCatching { ItemId(value) }.isFailure)
        }
        listOf("short", "bad/id", "x".repeat(129)).forEach { value ->
            assertTrue(runCatching { OperationId(value) }.isFailure)
        }
        listOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY).forEach { coordinate ->
            assertTrue(runCatching { GridPosition.parsePersisted(coordinate, 0.125) }.isFailure)
        }
        assertTrue(
            runCatching {
                layout().copy(name = "😀".repeat(MiniHomeRequestContract.MAX_NAME_CODE_POINTS + 1))
            }
                .isFailure
        )
        assertEquals(
            MiniHomeRequestContract.MAX_NAME_CODE_POINTS,
            "😀"
                .repeat(MiniHomeRequestContract.MAX_NAME_CODE_POINTS)
                .codePointCount(0, MiniHomeRequestContract.MAX_NAME_CODE_POINTS * 2),
        )
        assertTrue(
            runCatching {
                layout()
                    .copy(
                        placements =
                            List(MiniHomeGrid.MAX_PLACEMENTS + 1) { index ->
                                placement(
                                    "placement-$index",
                                    "plant-$index",
                                    GridPosition(0, 0),
                                )
                            }
                    )
            }
                .isFailure
        )
    }

    private fun request(layout: MiniHomeLayout) =
        MiniHomeSaveRequest(
            AccountId("account-a"),
            OperationId("operation-contract"),
            layout.revision,
            layout,
        )

    private fun layout() =
        MiniHomeLayout(
            MiniHomeId("home-a"),
            "나의 미니 식물원",
            emptyList(),
            Revision(1),
            Instant.EPOCH,
        )

    private fun placement(id: String, plantId: String, position: GridPosition) =
        MiniHomePlacement(
            PlacementId(id),
            MiniHomePlacementTarget.Plant(PersonalPlantId(plantId)),
            position,
            MiniHomeZIndex(0),
        )
}
