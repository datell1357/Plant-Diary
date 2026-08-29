package com.planterior.helper.action

import com.planterior.helper.Todo18BoundaryEvent
import com.planterior.helper.Todo18Scenario
import com.planterior.helper.core.model.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class Todo18ScenarioActionListenerContractTest {
    @Test
    fun `action diagnostic delivery preserves exact semantic listener exception and cleanup`() {
        val scenario = Todo18Scenario(AccountId("listener-identity-owner"))
        val expected = ListenerFailure()
        val subscription = scenario.subscribe { throw expected }
        assertEquals(1, scenario.listenerCount())

        try {
            val actual =
                assertThrows(ListenerFailure::class.java) {
                    scenario.emit("mini-home-save-attempt", "operation-identity")
                }
            assertSame(expected, actual)
        } finally {
            subscription.close()
        }
        assertEquals(0, scenario.listenerCount())
    }

    @Test
    fun `throwing diagnostic listener cannot prevent exact semantic event delivery`() {
        val scenario = Todo18Scenario(AccountId("listener-fault-owner"))
        val delivered = mutableListOf<Todo18BoundaryEvent>()
        val diagnostic =
            com.planterior.helper.feature.minihome.MiniHomeSaveActionDiagnostics.install {
                throw ListenerFailure()
            }
        val subscription = scenario.subscribe(delivered::add)

        try {
            scenario.emit("mini-home-save-attempt", "exact-operation")
            assertEquals(
                listOf(Todo18BoundaryEvent("mini-home-save-attempt", "exact-operation")),
                delivered,
            )
        } finally {
            subscription.close()
            diagnostic.close()
        }
        assertEquals(0, scenario.listenerCount())
    }

    private class ListenerFailure : RuntimeException()
}
