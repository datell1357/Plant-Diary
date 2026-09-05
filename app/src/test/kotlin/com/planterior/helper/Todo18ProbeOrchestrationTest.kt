package com.planterior.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class Todo18ProbeOrchestrationTest {
    @Test
    fun `rendered correlation rejects unrelated accepted state`() {
        requireMatchingRenderedState("target", "target")
        assertThrows(IllegalArgumentException::class.java) {
            requireMatchingRenderedState("target", "other target")
        }
    }

    @Test
    fun `initial viewing waits upstream before settling and awaiting rendered event`() {
        val trace = mutableListOf<String>()

        val observed =
            triggerAwaitSettleAndAwait(
                trigger = { trace += "trigger" },
                awaitUpstream = {
                    trace += "await-upstream"
                    "raw"
                },
                settle = { trace += "settle" },
                awaitRendered = {
                    trace += "await-rendered"
                    "displayed"
                },
            )

        assertEquals("raw" to "displayed", observed)
        assertEquals(
            listOf("trigger", "await-upstream", "settle", "await-rendered"),
            trace,
        )
    }

    @Test
    fun `conflict editing signal completes after settlement`() {
        // Given
        val signal = QueuedSignal()
        signal.subscribe()
        signal.arm()
        var triggerCount = 0
        var settleCount = 0
        var awaitCount = 0

        // When
        val observed =
            triggerSettleAndAwait(
                trigger = {
                    triggerCount += 1
                    signal.queue("Conflict Editing")
                },
                settle = {
                    settleCount += 1
                    signal.settle()
                },
                await = {
                    awaitCount += 1
                    signal.awaitExact()
                },
            )

        // Then
        assertEquals("Conflict Editing", observed)
        assertEquals(1, signal.subscriptionCount)
        assertEquals(listOf("subscribe", "arm", "trigger", "settle", "await"), signal.trace)
        assertEquals(listOf(1, 1, 1), listOf(triggerCount, settleCount, awaitCount))
    }

    @Test
    fun `offline editing signal completes after settlement`() {
        // Given
        val signal = QueuedSignal()
        signal.subscribe()
        signal.arm()

        // When
        val observed =
            triggerSettleAndAwait(
                trigger = { signal.queue("Offline Editing") },
                settle = signal::settle,
                await = signal::awaitExact,
            )

        // Then
        assertEquals("Offline Editing", observed)
        assertEquals(1, signal.subscriptionCount)
        assertEquals(listOf("subscribe", "arm", "trigger", "settle", "await"), signal.trace)
    }

    @Test
    fun `registration completion remote commit and destination complete after settlement`() {
        // Given
        val completed = QueuedSignal()
        val remoteCommit = QueuedSignal()
        val destination = QueuedSignal()
        listOf(completed, remoteCommit, destination).forEach {
            it.subscribe()
            it.arm()
        }
        var settleCount = 0

        // When
        val observed =
            triggerSettleAndAwait(
                trigger = {
                    completed.queue("Completed:plant-18")
                    remoteCommit.queue("RemoteCommit:plant-18")
                    destination.queue("PlantDetail:plant-18")
                },
                settle = {
                    settleCount += 1
                    completed.settle()
                    remoteCommit.settle()
                    destination.settle()
                },
                await = {
                    listOf(
                        completed.awaitExact(),
                        remoteCommit.awaitExact(),
                        destination.awaitExact(),
                    )
                },
            )

        // Then
        assertEquals(
            listOf(
                "Completed:plant-18",
                "RemoteCommit:plant-18",
                "PlantDetail:plant-18",
            ),
            observed,
        )
        assertEquals(1, settleCount)
        assertEquals(
            listOf(1, 1, 1),
            listOf(completed, remoteCommit, destination).map { it.subscriptionCount },
        )
        listOf(completed, remoteCommit, destination).forEach {
            assertEquals(listOf("subscribe", "arm", "trigger", "settle", "await"), it.trace)
        }
    }

    @Test
    fun `trigger exception identity is exact and skips settlement and await`() {
        // Given
        val failure = ProbeFailure("trigger")
        var settleCount = 0
        var awaitCount = 0

        // When
        val thrown =
            assertThrows(ProbeFailure::class.java) {
                triggerSettleAndAwait(
                    trigger = { throw failure },
                    settle = { settleCount += 1 },
                    await = {
                        awaitCount += 1
                        error("unreachable")
                    },
                )
            }

        // Then
        assertSame(failure, thrown)
        assertEquals(0, settleCount)
        assertEquals(0, awaitCount)
    }

    @Test
    fun `settlement exception identity is exact and skips await`() {
        // Given
        val failure = ProbeFailure("settle")
        var triggerCount = 0
        var awaitCount = 0

        // When
        val thrown =
            assertThrows(ProbeFailure::class.java) {
                triggerSettleAndAwait(
                    trigger = { triggerCount += 1 },
                    settle = { throw failure },
                    await = {
                        awaitCount += 1
                        error("unreachable")
                    },
                )
            }

        // Then
        assertSame(failure, thrown)
        assertEquals(1, triggerCount)
        assertEquals(0, awaitCount)
    }

    @Test
    fun `exact await exception identity is preserved after settlement`() {
        // Given
        val failure = ProbeFailure("await")
        var triggerCount = 0
        var settleCount = 0

        // When
        val thrown =
            assertThrows(ProbeFailure::class.java) {
                triggerSettleAndAwait(
                    trigger = { triggerCount += 1 },
                    settle = { settleCount += 1 },
                    await = { throw failure },
                )
            }

        // Then
        assertSame(failure, thrown)
        assertEquals(1, triggerCount)
        assertEquals(1, settleCount)
    }

    @Test
    fun `settled callback exception remains the primary exception by identity`() {
        // Given
        val failure = ProbeFailure("callback")
        val signal = QueuedSignal()
        signal.subscribe { throw failure }
        signal.arm()
        var awaitCount = 0

        // When
        val thrown =
            assertThrows(ProbeFailure::class.java) {
                triggerSettleAndAwait(
                    trigger = { signal.queue("Completed") },
                    settle = signal::settle,
                    await = {
                        awaitCount += 1
                        signal.awaitExact()
                    },
                )
            }

        // Then
        assertSame(failure, thrown)
        assertEquals(0, awaitCount)
        assertEquals(1, signal.subscriptionCount)
    }

    private class QueuedSignal {
        private var armed = false
        private var receiver: ((String) -> Unit)? = null
        private val queued = ArrayDeque<String>()
        private var observed: String? = null

        val trace = mutableListOf<String>()
        var subscriptionCount = 0
            private set

        fun subscribe(callback: (String) -> Unit = { observed = it }) {
            check(receiver == null) { "subscription replaced" }
            receiver = callback
            subscriptionCount += 1
            trace += "subscribe"
        }

        fun arm() {
            check(receiver != null) { "subscription missing" }
            check(!armed) { "subscription already armed" }
            armed = true
            trace += "arm"
        }

        fun queue(value: String) {
            check(armed) { "trigger ran before pre-arm" }
            queued.addLast(value)
            trace += "trigger"
        }

        fun settle() {
            trace += "settle"
            val callback = checkNotNull(receiver)
            while (queued.isNotEmpty()) callback(queued.removeFirst())
        }

        fun awaitExact(): String {
            trace += "await"
            return checkNotNull(observed) { "await called before settlement" }
        }
    }

    private class ProbeFailure(message: String) : IllegalStateException(message)
}
