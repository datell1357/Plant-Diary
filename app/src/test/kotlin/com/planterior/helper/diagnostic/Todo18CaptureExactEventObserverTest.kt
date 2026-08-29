package com.planterior.helper.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Test

class Todo18CaptureExactEventObserverTest {
    @Test
    fun `pre-arm replay and superseded false event stay out of classification pipeline`() {
        val capture = FakeCapture()
        val observer = Todo18CaptureExactEventObserver(capture) { 1 }
        observer.observe(received(1))
        observer.observe(predicate(false, 1))
        observer.observe(rejected(1))
        observer.observe(phase(Todo18ExactEventPhase.ARMED))
        observer.observe(received(2))
        observer.observe(predicate(false, 2))
        observer.observe(rejected(2))
        observer.observe(received(3))
        observer.observe(predicate(true, 3))
        observer.observe(accepted(3))

        assertEquals(
            listOf(
                Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE,
                Todo18PipelineEventKind.PREDICATE_TRUE,
                Todo18PipelineEventKind.EVENT_ACCEPTED,
            ),
            capture.pipeline.map(Todo18PipelineEvent::kind),
        )
        assertEquals(listOf(3L, 3L, 3L), capture.pipeline.map { it.sourceSequence })
        assertEquals(10, capture.exact.size)
    }

    @Test
    fun `await failure commits the last rejected predicate exactly once`() {
        val capture = FakeCapture()
        val observer = Todo18CaptureExactEventObserver(capture) { 0 }
        observer.observe(phase(Todo18ExactEventPhase.ARMED))
        observer.observe(received(7))
        observer.observe(predicate(false, 7))
        observer.observe(rejected(7))
        observer.observe(phase(Todo18ExactEventPhase.AWAIT_FAILURE))

        assertEquals(
            listOf(
                Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE,
                Todo18PipelineEventKind.PREDICATE_FALSE,
                Todo18PipelineEventKind.EVENT_REJECTED,
                Todo18PipelineEventKind.AWAIT_FAILURE,
            ),
            capture.pipeline.map(Todo18PipelineEvent::kind),
        )
    }

    private fun phase(value: Todo18ExactEventPhase) = Todo18ExactEventObservation(phase = value)

    private fun received(sequence: Long) =
        Todo18ExactEventObservation(
            phase = Todo18ExactEventPhase.EVENT_RECEIVED,
            sourceSequence = sequence,
        )

    private fun predicate(value: Boolean, sequence: Long) =
        Todo18ExactEventObservation(
            phase =
                if (value) {
                    Todo18ExactEventPhase.PREDICATE_TRUE
                } else {
                    Todo18ExactEventPhase.PREDICATE_FALSE
                },
            sourceSequence = sequence,
        )

    private fun accepted(sequence: Long) =
        Todo18ExactEventObservation(
            phase = Todo18ExactEventPhase.EVENT_ACCEPTED,
            sourceSequence = sequence,
        )

    private fun rejected(sequence: Long) =
        Todo18ExactEventObservation(
            phase = Todo18ExactEventPhase.EVENT_REJECTED,
            sourceSequence = sequence,
        )

    private class FakeCapture : Todo18DiagnosticCapture {
        val pipeline = mutableListOf<Todo18PipelineEvent>()
        val exact = mutableListOf<Todo18ExactEventObservation>()

        override fun recordPipeline(
            kind: Todo18PipelineEventKind,
            sourceSequence: Long?,
            controllerIdentity: Int?,
            requestedContentId: com.planterior.helper.core.model.PlantContentId?,
            beforeState: Todo18StateKind?,
            afterState: Todo18StateKind?,
        ) {
            pipeline +=
                Todo18PipelineEvent(
                    ordinal = pipeline.size + 1L,
                    kind = kind,
                    sourceSequence = sourceSequence,
                )
        }

        override fun recordExact(observation: Todo18ExactEventObservation) {
            exact += observation
        }

        override fun markFailure(failure: Todo18DiagnosticFailure) = Unit

        override fun snapshot(): Todo18DiagnosticCaptureSnapshot = error("not needed")

        override fun close() = Unit
    }
}
