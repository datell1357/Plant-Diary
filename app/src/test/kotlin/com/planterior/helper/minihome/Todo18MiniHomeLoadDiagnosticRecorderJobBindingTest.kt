package com.planterior.helper.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.minihome.MiniHomeCacheTransactionDiagnosticObservation
import com.planterior.helper.feature.minihome.MiniHomeCacheTransactionDiagnosticStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Todo18MiniHomeLoadDiagnosticRecorderJobBindingTest {
    @Test
    fun `cache body and internal stages retain the load across a changed coroutine context`() =
        runTest {
            val observations = mutableListOf<Todo18MiniHomeLoadObservation>()
            val recorder = Todo18MiniHomeLoadDiagnosticRecorder(observations::add)
            val load = recorder.startLoad()
            val account = AccountId("account-a")
            val operation = OperationId("operation-a")
            load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)

            recorder.withLoad(load) {
                load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
                load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
                load.record(Todo18MiniHomeLoadDiagnostic.CacheApplyEntered(account))
                load.record(
                    Todo18MiniHomeLoadDiagnostic.CacheTransaction(
                        MiniHomeCacheTransactionDiagnosticObservation(
                            MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_CALL_ENTERED,
                            account,
                            operation,
                        )
                    )
                )
                withContext(NonCancellable) {
                    recorder.recordCurrentCacheTransaction(
                        MiniHomeCacheTransactionDiagnosticObservation(
                            MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_BODY_ENTERED,
                            account,
                            operation,
                        )
                    )
                    recorder.recordCurrentCacheTransaction(
                        MiniHomeCacheTransactionDiagnosticObservation(
                            MiniHomeCacheTransactionDiagnosticStage.LAYOUT_APPLY,
                            account,
                            operation,
                        )
                    )
                    recorder.recordCurrentCacheTransaction(
                        MiniHomeCacheTransactionDiagnosticObservation(
                            MiniHomeCacheTransactionDiagnosticStage.INVENTORY_APPLY,
                            account,
                            operation,
                        )
                    )
                    recorder.recordCurrentCacheTransaction(
                        MiniHomeCacheTransactionDiagnosticObservation(
                            MiniHomeCacheTransactionDiagnosticStage.CURRENT_SNAPSHOT,
                            account,
                            operation,
                        )
                    )
                    recorder.recordCurrentCacheTransaction(
                        MiniHomeCacheTransactionDiagnosticObservation(
                            MiniHomeCacheTransactionDiagnosticStage.VERIFIED_INVENTORY_DECODE,
                            account,
                            operation,
                        )
                    )
                }
                load.record(
                    Todo18MiniHomeLoadDiagnostic.CacheTransaction(
                        MiniHomeCacheTransactionDiagnosticObservation(
                            MiniHomeCacheTransactionDiagnosticStage.TRANSACTION_RETURNED,
                            account,
                            operation,
                            result =
                                com.planterior.helper.feature.minihome
                                    .MiniHomeCacheTransactionResult
                                    .CURRENT,
                        )
                    )
                )
                load.record(
                    Todo18MiniHomeLoadDiagnostic.CacheApplyReturned(account, current = true)
                )
                load.recordPublicationRead()
                load.record(Todo18MiniHomeLoadDiagnostic.Ready)
            }

            assertEquals(
                listOf(
                    "load-entered",
                    "remote-load-entered",
                    "remote-load-returned",
                    "cache-apply-entered",
                    "cache-transaction-call-entered",
                    "cache-transaction-body-entered",
                    "cache-layout-apply",
                    "cache-inventory-apply",
                    "cache-current-snapshot",
                    "cache-verified-inventory-decode",
                    "cache-transaction-returned",
                    "cache-apply-returned",
                    "publication-read-entered",
                    "terminal-ready",
                ),
                observations.map(Todo18MiniHomeLoadObservation::receiptStage),
            )
            assertTrue(observations.all { it.loadId == load.id })
            assertTrue(observations.all { it.readId == null || it.readId.loadId == load.id })
            assertTrue(
                recorder.snapshot().loads.single().reachedStages ==
                    observations.map(Todo18MiniHomeLoadObservation::receiptStage)
            )
            assertFalse(
                recorder.recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
            )
        }

    @Test
    fun `withLoad preserves exact delegate cancellation identity across context changes`() =
        runTest {
            val expected = CancellationException("delegate cancellation")
            val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
            val load = recorder.startLoad()

            val actual =
                try {
                    recorder.withLoad(load) {
                        throwSynchronously(expected)
                    }
                    throw AssertionError("Expected cancellation")
                } catch (failure: CancellationException) {
                    failure
                }

            assertSame(expected, actual)
        }

    @Test
    fun `withLoad preserves direct delegate cancellation with a nested cancellation cause`() =
        runTest {
            val nested = CancellationException("nested cancellation")
            val expected =
                CancellationException("delegate cancellation").apply { initCause(nested) }
            val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
            val load = recorder.startLoad()

            val actual =
                try {
                    recorder.withLoad(load) {
                        throwSynchronously(expected)
                    }
                    throw AssertionError("Expected cancellation")
                } catch (failure: CancellationException) {
                    failure
                }

            assertSame(expected, actual)
            assertSame(nested, actual.cause)
            assertFalse(recorder.recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.LoadEntered))
        }

    @Test
    fun `parent cancellation before block entry bypasses delegate transport`() = runTest {
        val expected = CancellationException("surrounding cancellation before entry")
        val parent = Job().also { it.cancel(expected) }
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        var entered = false

        val actual =
            try {
                withContext(parent) {
                    recorder.withLoad(load) {
                        entered = true
                    }
                }
                throw AssertionError("Expected cancellation")
            } catch (failure: CancellationException) {
                failure
            }

        assertFalse(entered)
        assertEquals(expected.message, actual.message)
        assertTrue(parent.isCancelled)
        assertFalse(recorder.recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.LoadEntered))
    }

    @Test
    fun `parent cancellation while block is suspended is not relabeled as delegate cancellation`() =
        runTest {
            val expected = CancellationException("surrounding cancellation while suspended")
            val parent = Job(coroutineContext[Job])
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
            val load = recorder.startLoad()
            val attempt =
                CoroutineScope(coroutineContext + parent).async {
                    recorder.withLoad(load) {
                        entered.complete(Unit)
                        release.await()
                    }
                }

            entered.await()
            parent.cancel(expected)
            val actual =
                try {
                    attempt.await()
                    throw AssertionError("Expected cancellation")
                } catch (failure: CancellationException) {
                    failure
                }

            assertEquals(expected.message, actual.message)
            assertTrue(attempt.isCancelled)
            assertFalse(attempt.isActive)
            assertFalse(recorder.recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.LoadEntered))
            release.cancel()
        }

    @Test
    fun `withLoad preserves non cancellation throwable identity`() = runTest {
        val expected = AssertionError("delegate failure")
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()

        val actual =
            try {
                recorder.withLoad(load) { throw expected }
                fail("Expected failure")
            } catch (failure: AssertionError) {
                failure
            }

        assertSame(expected, actual)
        assertFalse(recorder.recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.LoadEntered))
    }

    private fun throwSynchronously(failure: CancellationException): Nothing = throw failure

    @Test
    fun `optional current context observation accepts an inherited NonCancellable child`() =
        runTest {
            val observations = mutableListOf<Todo18MiniHomeLoadObservation>()
            val recorder = Todo18MiniHomeLoadDiagnosticRecorder(observations::add)
            val load = recorder.startLoad()
            load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)

            val accepted =
                recorder.withLoad(load) {
                    withContext(NonCancellable) {
                        recorder.recordCurrentIfActive(
                            Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered
                        )
                    }
                }

            assertTrue(accepted)
            assertEquals(
                listOf(
                    Todo18MiniHomeLoadDiagnostic.LoadEntered,
                    Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered,
                ),
                observations.map(Todo18MiniHomeLoadObservation::diagnostic),
            )
        }

    @Test
    fun `strict current context observation rejects only after withLoad and cleans up`() = runTest {
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val first = recorder.startLoad()
        first.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)

        recorder.withLoad(first) {
            withContext(NonCancellable) {
                recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
            }
        }

        assertFalse(recorder.recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered))
        try {
            recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
            fail("Expected strict current context lookup to fail")
        } catch (_: IllegalStateException) {}
        val second = recorder.startLoad()
        recorder.withLoad(second) {
            recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        }
        assertTrue(recorder.snapshot().loads.any { it.loadId == second.id })
    }

    @Test
    fun `nested loads shadow and restore without crossing recorder instances`() = runTest {
        val observations = mutableListOf<Todo18MiniHomeLoadObservation>()
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder(observations::add)
        val foreignObservations = mutableListOf<Todo18MiniHomeLoadObservation>()
        val foreignRecorder = Todo18MiniHomeLoadDiagnosticRecorder(foreignObservations::add)
        val outer = recorder.startLoad()
        val inner = recorder.startLoad()
        val foreign = foreignRecorder.startLoad()
        outer.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        inner.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        foreign.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)

        recorder.withLoad(outer) {
            recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
            recorder.withLoad(inner) {
                withContext(NonCancellable) {
                    recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
                    assertFalse(
                        foreignRecorder.recordCurrentIfActive(
                            Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered
                        )
                    )
                }
            }
            recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
        }

        assertEquals(
            listOf(outer.id, inner.id, outer.id),
            observations
                .filter { it.diagnostic != Todo18MiniHomeLoadDiagnostic.LoadEntered }
                .map(Todo18MiniHomeLoadObservation::loadId),
        )
        assertTrue(
            foreignObservations.single().diagnostic is Todo18MiniHomeLoadDiagnostic.LoadEntered
        )
        assertFalse(recorder.recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered))
    }
}
