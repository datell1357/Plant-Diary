package com.planterior.helper

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18ShareViewingProbeTest {
    @Test
    fun `prearmed share raw event settles before displayed peer and detaches`() {
        val raw = TestEventSource<ShareEvent>()
        val displayed = TestEventSource<ShareEvent>()
        val rawSubscription =
            subscription(raw) { it.owner == OWNER && it.render == Render.RENDERING }
        val displayedSubscription =
            subscription(displayed) { it.owner == OWNER && it.render == Render.READY }
        val trace = mutableListOf<String>()

        rawSubscription.use { upstream ->
            displayedSubscription.use { rendered ->
                upstream.arm()
                rendered.arm()
                val observed =
                    triggerAwaitSettleAndAwait(
                        trigger = {
                            trace += "trigger"
                            raw.publish(ShareEvent(OWNER, Render.RENDERING))
                            raw.publish(ShareEvent(OWNER, Render.READY))
                        },
                        awaitUpstream = {
                            trace += "raw"
                            upstream.await(1, TimeUnit.SECONDS, "share raw")
                        },
                        settle = {
                            trace += "settle"
                            displayed.publish(ShareEvent("other", Render.READY))
                            displayed.publish(ShareEvent(OWNER, Render.RENDERING))
                            displayed.publish(ShareEvent(OWNER, Render.READY))
                        },
                        awaitRendered = {
                            trace += "displayed"
                            rendered.await(1, TimeUnit.SECONDS, "share displayed")
                        },
                    )
                assertEquals(observed.first.copy(render = Render.READY), observed.second)
                assertEquals(listOf("trigger", "raw", "settle", "displayed"), trace)
            }
        }
        assertEquals(0, raw.listenerCount)
        assertEquals(0, displayed.listenerCount)
    }

    @Test
    fun `mismatched displayed state fails after both exact events`() {
        val raw = TestEventSource<ShareEvent>()
        val displayed = TestEventSource<ShareEvent>()
        val upstream = subscription(raw) { it.owner == OWNER && it.render == Render.RENDERING }
        val rendered = subscription(displayed) { it.owner == OWNER && it.render == Render.READY }
        upstream.use { upstreamSubscription ->
            rendered.use { renderedSubscription ->
                upstreamSubscription.arm()
                renderedSubscription.arm()
                val actual =
                    triggerAwaitSettleAndAwait(
                        trigger = { raw.publish(ShareEvent(OWNER, Render.RENDERING)) },
                        awaitUpstream = {
                            upstreamSubscription.await(1, TimeUnit.SECONDS, "share raw")
                        },
                        settle = {
                            displayed.publish(ShareEvent(OWNER, Render.READY, revision = 2))
                        },
                        awaitRendered = {
                            renderedSubscription.await(1, TimeUnit.SECONDS, "share displayed")
                        },
                    )
                assertThrows(IllegalArgumentException::class.java) {
                    require(actual.first.copy(render = Render.READY) == actual.second) {
                        "share state mismatch"
                    }
                }
            }
        }
        assertTrue(raw.listenerCount == 0 && displayed.listenerCount == 0)
    }

    private fun subscription(
        source: TestEventSource<ShareEvent>,
        matches: (ShareEvent) -> Boolean,
    ) = ExactEventSubscription(matches = matches, subscribe = { source.registration(it) })

    private data class ShareEvent(val owner: String, val render: Render, val revision: Int = 1)

    private enum class Render {
        RENDERING,
        READY,
    }

    private class TestEventSource<T> {
        private val listeners = linkedSetOf<(T) -> Unit>()
        val listenerCount: Int
            get() = listeners.size

        fun registration(receiver: (T) -> Unit): ExactEventRegistration =
            LeasedExactEventRegistration(
                receiver = receiver,
                register = { listeners += it },
                unregister = { listeners -= it },
            )

        fun publish(value: T) = listeners.toList().forEach { it(value) }
    }

    private companion object {
        const val OWNER = "account-18"
    }
}
