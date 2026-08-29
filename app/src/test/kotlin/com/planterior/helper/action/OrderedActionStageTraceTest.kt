package com.planterior.helper.action

import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.watering.WateringConfirmActionStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OrderedActionStageTraceTest(
    private val chain: String,
    private val expected: List<Enum<*>>,
    private val withheld: Enum<*>,
) {
    @Test
    fun `withheld stage is the exact first missing stage without waiting`() {
        val trace = OrderedActionStageTrace(expected)
        expected.filterNot { it == withheld }.forEach(trace::record)

        assertEquals(withheld, trace.firstMissing())
        val failure = assertThrows(AssertionError::class.java, trace::requireComplete)
        assertEquals(true, failure.message?.startsWith("first missing stage: $withheld"))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} withholds {2}")
        fun cases(): List<Array<Any>> = buildList {
            addCases("MiniHome", MiniHomeSaveActionStage.entries)
            addCases("Watering", WateringConfirmActionStage.entries)
        }

        private fun MutableList<Array<Any>>.addCases(chain: String, stages: List<Enum<*>>) {
            stages.forEach { withheld -> add(arrayOf(chain, stages, withheld)) }
        }
    }
}
