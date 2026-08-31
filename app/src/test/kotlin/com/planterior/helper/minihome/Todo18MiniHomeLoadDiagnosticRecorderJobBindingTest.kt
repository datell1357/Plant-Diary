package com.planterior.helper.minihome

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Todo18MiniHomeLoadDiagnosticRecorderJobBindingTest {
    @Test
    fun `optional current Job observation declines an unregistered NonCancellable child`() =
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

            assertFalse(accepted)
            assertEquals(
                listOf(Todo18MiniHomeLoadDiagnostic.LoadEntered),
                observations.map(Todo18MiniHomeLoadObservation::diagnostic),
            )
        }

    @Test
    fun `strict current Job observation still fails for an unregistered NonCancellable child and cleans up`() =
        runTest {
            val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
            val first = recorder.startLoad()
            first.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)

            try {
                recorder.withLoad(first) {
                    withContext(NonCancellable) {
                        recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
                    }
                }
                fail("Expected strict current Job lookup to fail")
            } catch (_: IllegalStateException) {}

            assertFalse(
                recorder.recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
            )
            val second = recorder.startLoad()
            recorder.withLoad(second) {
                recorder.recordCurrent(Todo18MiniHomeLoadDiagnostic.LoadEntered)
            }
            assertTrue(recorder.snapshot().loads.any { it.loadId == second.id })
        }
}
