package com.planterior.helper.core.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RoomTransactionOwnerDiagnosticsTest {
    @Test
    fun `observer off and on preserve a fixed result throw cancellation schedule`() = runTest {
        val off = runFixedSchedule(RoomTransactionOwnerDiagnostics())
        val observations = mutableListOf<RoomTransactionOwnerObservation>()
        val on = runFixedSchedule(RoomTransactionOwnerDiagnostics(observations::add))

        assertEquals(off, on)
        assertEquals(6, observations.size)
        assertEquals(
            observations
                .filterIsInstance<RoomTransactionOwnerObservation.Began>()
                .map { it.token }
                .toSet(),
            observations
                .filterIsInstance<RoomTransactionOwnerObservation.Terminal>()
                .map { it.token }
                .toSet(),
        )
    }

    @Test
    fun `observer off and on preserve result and exactly one terminal`() = runTest {
        val observations = mutableListOf<RoomTransactionOwnerObservation>()
        val diagnostics = RoomTransactionOwnerDiagnostics(observations::add)

        val result = diagnostics.observe(RoomTransactionOwner.ANALYTICS_ENQUEUE) { "result" }

        assertEquals("result", result)
        assertEquals(2, observations.size)
        assertEquals(observations[0].token, observations[1].token)
        assertEquals(
            listOf(
                RoomTransactionOwnerObservation.Began::class,
                RoomTransactionOwnerObservation.Returned::class,
            ),
            observations.map { it::class },
        )
    }

    @Test
    fun `throw and cancellation retain exact primary identity`() = runTest {
        val observations = mutableListOf<RoomTransactionOwnerObservation>()
        val diagnostics = RoomTransactionOwnerDiagnostics(observations::add)
        val thrown = IllegalStateException("write failed")
        val cancelled = CancellationException("write cancelled")

        val actualThrown =
            try {
                diagnostics.observe(RoomTransactionOwner.ACCOUNT_SYNC_WRITE) { throw thrown }
                error("Expected failure")
            } catch (failure: IllegalStateException) {
                failure
            }
        val actualCancelled =
            try {
                diagnostics.observe(RoomTransactionOwner.ANALYTICS_WORKER_DELIVERY) {
                    throw cancelled
                }
                error("Expected cancellation")
            } catch (failure: CancellationException) {
                failure
            }

        assertSame(thrown, actualThrown)
        assertSame(cancelled, actualCancelled)
        assertSame(thrown, (observations[1] as RoomTransactionOwnerObservation.Threw).failure)
        assertSame(
            cancelled,
            (observations[3] as RoomTransactionOwnerObservation.Cancelled).failure,
        )
    }

    @Test
    fun `immediate cancellation records cancelled with exact primary identity`() {
        val observations = mutableListOf<RoomTransactionOwnerObservation>()
        val diagnostics = RoomTransactionOwnerDiagnostics(observations::add)
        val expected = CancellationException("immediate cancellation")

        val actual =
            try {
                diagnostics.observeImmediate(
                    RoomTransactionOwner.ACCOUNT_SESSION_CACHE_ACTIVATION
                ) {
                    throw expected
                }
                error("Expected cancellation")
            } catch (failure: CancellationException) {
                failure
            }

        assertSame(expected, actual)
        assertSame(
            expected,
            (observations.single { it is RoomTransactionOwnerObservation.Cancelled }
                    as RoomTransactionOwnerObservation.Cancelled)
                .failure,
        )
    }

    @Test
    fun `observer faults do not change write result or invocation count`() = runTest {
        listOf<Throwable>(RuntimeException("observer"), AssertionError("observer")).forEach {
            observerFailure ->
            var calls = 0
            val diagnostics = RoomTransactionOwnerDiagnostics { throw observerFailure }

            val result =
                diagnostics.observe(RoomTransactionOwner.ANALYTICS_CONSENT_PURGE) {
                    calls += 1
                    7
                }

            assertEquals(7, result)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `nested writes receive distinct monotonic tokens and paired terminals`() = runTest {
        val observations = mutableListOf<RoomTransactionOwnerObservation>()
        val diagnostics = RoomTransactionOwnerDiagnostics(observations::add)

        diagnostics.observe(RoomTransactionOwner.ACCOUNT_SYNC_WRITE) {
            diagnostics.observe(RoomTransactionOwner.ANALYTICS_ENQUEUE) { Unit }
        }

        val began = observations.filterIsInstance<RoomTransactionOwnerObservation.Began>()
        assertEquals(2, began.size)
        assertEquals(true, began[0].token.value < began[1].token.value)
        assertEquals(
            began.map { it.token }.toSet(),
            observations
                .filterIsInstance<RoomTransactionOwnerObservation.Terminal>()
                .map { it.token }
                .toSet(),
        )
    }

    private suspend fun runFixedSchedule(
        diagnostics: RoomTransactionOwnerDiagnostics
    ): FixedScheduleResult {
        var invocations = 0
        val result =
            diagnostics.observe(RoomTransactionOwner.ANALYTICS_ENQUEUE) {
                invocations += 1
                "result"
            }
        val thrown =
            try {
                diagnostics.observe(RoomTransactionOwner.ACCOUNT_SYNC_WRITE) {
                    invocations += 1
                    throw IllegalStateException("fixed failure")
                }
                error("Expected failure")
            } catch (failure: IllegalStateException) {
                failure
            }
        val cancelled =
            try {
                diagnostics.observe(RoomTransactionOwner.ANALYTICS_WORKER_DELIVERY) {
                    invocations += 1
                    throw CancellationException("fixed cancellation")
                }
                error("Expected cancellation")
            } catch (failure: CancellationException) {
                failure
            }
        return FixedScheduleResult(
            result,
            thrown.javaClass.name,
            thrown.message,
            cancelled.javaClass.name,
            cancelled.message,
            invocations,
        )
    }

    private data class FixedScheduleResult(
        val result: String,
        val failureClass: String,
        val failureMessage: String?,
        val cancellationClass: String,
        val cancellationMessage: String?,
        val invocations: Int,
    )
}
