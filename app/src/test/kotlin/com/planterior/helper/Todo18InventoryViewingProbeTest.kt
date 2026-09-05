package com.planterior.helper

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class Todo18InventoryViewingProbeTest {
    @Test
    fun `prearmed inventory raw event is settled before its exact displayed peer`() {
        val rawSource = TestEventSource<InventoryEvent>()
        val displayedSource = TestEventSource<InventoryEvent>()
        val raw = subscription(rawSource) { it.kind == "content" && it.owner == "account-18" }
        val displayed =
            subscription(displayedSource) { it.kind == "content" && it.owner == "account-18" }
        val trace = mutableListOf<String>()

        raw.use { rawSubscription ->
            displayed.use { displayedSubscription ->
                rawSubscription.arm()
                displayedSubscription.arm()
                val observed =
                    triggerAwaitSettleAndAwait(
                        trigger = {
                            trace += "trigger"
                            rawSource.publish(InventoryEvent("loading", "account-18"))
                            rawSource.publish(InventoryEvent("content", "account-18"))
                        },
                        awaitUpstream = {
                            trace += "raw"
                            rawSubscription.await(1, TimeUnit.SECONDS, "inventory raw")
                        },
                        settle = {
                            trace += "settle"
                            displayedSource.publish(InventoryEvent("content", "other-account"))
                            displayedSource.publish(InventoryEvent("content", "account-18"))
                        },
                        awaitRendered = {
                            trace += "displayed"
                            displayedSubscription.await(1, TimeUnit.SECONDS, "inventory displayed")
                        },
                    )

                assertEquals(observed.first, observed.second)
                assertEquals(listOf("trigger", "raw", "settle", "displayed"), trace)
            }
        }
        assertEquals(0, rawSource.listenerCount)
        assertEquals(0, displayedSource.listenerCount)
    }

    private fun subscription(
        source: TestEventSource<InventoryEvent>,
        matches: (InventoryEvent) -> Boolean,
    ) =
        ExactEventSubscription(
            matches = matches,
            subscribe = { receiver -> source.registration(receiver) },
        )

    private data class InventoryEvent(val kind: String, val owner: String)

    private class TestEventSource<T> {
        private val listeners = linkedSetOf<(T) -> Unit>()
        val listenerCount: Int
            get() = listeners.size

        fun registration(receiver: (T) -> Unit): ExactEventRegistration {
            lateinit var listener: (T) -> Unit
            return LeasedExactEventRegistration(
                receiver = receiver,
                register = { dispatch ->
                    listener = dispatch
                    listeners += listener
                },
                unregister = { listeners -= listener },
            )
        }

        fun publish(value: T) {
            listeners.toList().forEach { it(value) }
        }
    }
}
