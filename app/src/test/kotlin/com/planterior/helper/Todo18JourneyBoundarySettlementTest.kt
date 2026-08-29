package com.planterior.helper

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class Todo18JourneyBoundarySettlementTest {
    private val probeSource =
        repositoryRoot()
            .resolve("app/src/androidTest/kotlin/com/planterior/helper/Todo18JourneyEventProbe.kt")
            .readText()

    @Test
    fun `current boundary trigger exception keeps identity and closes the subscription`() {
        // Given
        val failure = BoundaryFailure("trigger")
        val signal = BoundarySignal("operation-trigger").apply { triggerFailure = failure }

        // When
        val thrown = assertThrows(BoundaryFailure::class.java) { runBoundaryProbe(signal) }

        // Then
        assertSame(failure, thrown)
        assertEquals(listOf("arm", "trigger", "close"), signal.trace)
        assertEquals(1, signal.closeCount)
        assertEquals(0, signal.settleCount)
        assertEquals(0, signal.awaitCount)
    }

    @Test
    fun `boundary await exception keeps identity after settlement and closes the subscription`() {
        // Given
        val failure = BoundaryFailure("await")
        val signal = BoundarySignal("operation-await").apply { awaitFailure = failure }

        // When
        val thrown = assertThrows(BoundaryFailure::class.java) { runBoundaryProbe(signal) }

        // Then
        assertSame(failure, thrown)
        assertEquals(
            listOf("arm", "trigger", "settle", "repository-entry", "await", "close"),
            signal.trace,
        )
        assertEquals(1, signal.closeCount)
        assertEquals(1, signal.settleCount)
        assertEquals(1, signal.awaitCount)
    }

    @Test
    fun `conflict save attempt enters repository during settlement with exact operation id`() {
        // Given
        val signal = BoundarySignal("conflict-operation-18")

        // When
        val observed = runBoundaryProbe(signal)

        // Then
        assertEquals("conflict-operation-18", observed)
        assertEquals(
            listOf("arm", "trigger", "settle", "repository-entry", "await", "close"),
            signal.trace,
        )
        assertEquals(
            listOf(1, 1, 1),
            listOf(signal.settleCount, signal.awaitCount, signal.closeCount),
        )
    }

    @Test
    fun `offline failed save enters repository during settlement with exact operation id`() {
        // Given
        val signal = BoundarySignal("offline-operation-18")

        // When
        val observed = runBoundaryProbe(signal)

        // Then
        assertEquals("offline-operation-18", observed)
        assertEquals(
            listOf("arm", "trigger", "settle", "repository-entry", "await", "close"),
            signal.trace,
        )
        assertEquals(
            listOf(1, 1, 1),
            listOf(signal.settleCount, signal.awaitCount, signal.closeCount),
        )
    }

    @Test
    fun `boundary settlement exception keeps identity skips await and closes subscription`() {
        // Given
        val failure = BoundaryFailure("settle")
        val signal = BoundarySignal("operation-settle").apply { settleFailure = failure }

        // When
        val thrown = assertThrows(BoundaryFailure::class.java) { runBoundaryProbe(signal) }

        // Then
        assertSame(failure, thrown)
        assertEquals(listOf("arm", "trigger", "settle", "close"), signal.trace)
        assertEquals(1, signal.settleCount)
        assertEquals(0, signal.awaitCount)
        assertEquals(1, signal.closeCount)
    }

    private fun runBoundaryProbe(signal: BoundarySignal): String {
        val awaitBoundary =
            probeSource
                .substringAfter("fun awaitBoundary(")
                .substringBefore("fun awaitRegistrationCommit(")
        return signal.use { subscription ->
            subscription.arm()
            if (awaitBoundary.contains("triggerSettleAndAwait(")) {
                triggerSettleAndAwait(
                    trigger = subscription::trigger,
                    settle = subscription::settle,
                    await = subscription::awaitExact,
                )
            } else {
                subscription.trigger()
                subscription.awaitExact()
            }
        }
    }

    private class BoundarySignal(private val expectedOperationId: String) : AutoCloseable {
        val trace = mutableListOf<String>()
        var triggerFailure: Throwable? = null
        var settleFailure: Throwable? = null
        var awaitFailure: Throwable? = null
        var closeCount = 0
            private set

        var settleCount = 0
            private set

        var awaitCount = 0
            private set

        private var armed = false
        private var pendingRepositoryEntry: (() -> Unit)? = null
        private var observedOperationId: String? = null

        fun arm() {
            check(!armed) { "boundary subscription already armed" }
            armed = true
            trace += "arm"
        }

        fun trigger() {
            check(armed) { "boundary trigger ran before pre-arm" }
            trace += "trigger"
            triggerFailure?.let { throw it }
            pendingRepositoryEntry = {
                trace += "repository-entry"
                observedOperationId = expectedOperationId
            }
        }

        fun settle() {
            settleCount += 1
            trace += "settle"
            settleFailure?.let { throw it }
            checkNotNull(pendingRepositoryEntry).invoke()
        }

        fun awaitExact(): String {
            awaitCount += 1
            trace += "await"
            awaitFailure?.let { throw it }
            return checkNotNull(observedOperationId) {
                "exact boundary awaited before repository entry settled"
            }
        }

        override fun close() {
            closeCount += 1
            trace += "close"
            armed = false
        }
    }

    private class BoundaryFailure(message: String) : IllegalStateException(message)

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}
