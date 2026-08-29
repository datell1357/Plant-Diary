package com.planterior.helper.diagnostic

import java.util.concurrent.atomic.AtomicBoolean

class Todo18CaptureExactEventObserver(
    private val capture: Todo18DiagnosticCapture,
    private val listenerCount: () -> Int,
) : Todo18ExactEventObserver {
    private val armed = AtomicBoolean()
    private val lock = Any()
    private var pending: PendingEvent? = null
    private var accepted = false

    override fun observe(observation: Todo18ExactEventObservation) {
        val enriched = observation.copy(listenerCount = listenerCount())
        capture.recordExact(enriched)
        if (enriched.phase == Todo18ExactEventPhase.ARMED) armed.set(true)
        if (!armed.get()) return

        when (enriched.phase) {
            Todo18ExactEventPhase.EVENT_RECEIVED ->
                synchronized(lock) { pending = PendingEvent(enriched.sourceSequence) }
            Todo18ExactEventPhase.PREDICATE_TRUE ->
                synchronized(lock) { pending = pending?.copy(predicate = true) }
            Todo18ExactEventPhase.PREDICATE_FALSE ->
                synchronized(lock) { pending = pending?.copy(predicate = false) }
            Todo18ExactEventPhase.EVENT_REJECTED ->
                synchronized(lock) { pending = pending?.copy(rejected = true) }
            Todo18ExactEventPhase.EVENT_ACCEPTED -> commitAcceptedEvent()
            Todo18ExactEventPhase.AWAIT_FAILURE -> {
                commitRejectedEvent()
                record(enriched)
            }
            else -> enriched.phase.pipelineKind()?.let { record(enriched, it) }
        }
    }

    override fun diagnosticFailure() {
        capture.markFailure(Todo18DiagnosticFailure.OBSERVER_CALLBACK_FAILED)
    }

    private fun commitAcceptedEvent() {
        val event =
            synchronized(lock) {
                if (accepted) return
                accepted = true
                pending
            } ?: return
        capture.recordPipeline(
            Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE,
            event.sourceSequence,
        )
        event.predicate?.let {
            capture.recordPipeline(
                if (it) {
                    Todo18PipelineEventKind.PREDICATE_TRUE
                } else {
                    Todo18PipelineEventKind.PREDICATE_FALSE
                },
                event.sourceSequence,
            )
        }
        capture.recordPipeline(Todo18PipelineEventKind.EVENT_ACCEPTED, event.sourceSequence)
    }

    private fun commitRejectedEvent() {
        val event = synchronized(lock) { pending?.takeIf { !accepted && it.rejected } } ?: return
        capture.recordPipeline(
            Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE,
            event.sourceSequence,
        )
        event.predicate?.let {
            capture.recordPipeline(
                if (it) {
                    Todo18PipelineEventKind.PREDICATE_TRUE
                } else {
                    Todo18PipelineEventKind.PREDICATE_FALSE
                },
                event.sourceSequence,
            )
        }
        capture.recordPipeline(Todo18PipelineEventKind.EVENT_REJECTED, event.sourceSequence)
    }

    private fun record(
        observation: Todo18ExactEventObservation,
        kind: Todo18PipelineEventKind = checkNotNull(observation.phase.pipelineKind()),
    ) {
        capture.recordPipeline(kind, observation.sourceSequence)
    }

    private fun Todo18ExactEventPhase.pipelineKind(): Todo18PipelineEventKind? =
        when (this) {
            Todo18ExactEventPhase.SUBSCRIBED,
            Todo18ExactEventPhase.ARMED,
            Todo18ExactEventPhase.EVENT_RECEIVED,
            Todo18ExactEventPhase.PREDICATE_TRUE,
            Todo18ExactEventPhase.PREDICATE_FALSE,
            Todo18ExactEventPhase.EVENT_ACCEPTED,
            Todo18ExactEventPhase.EVENT_REJECTED,
            Todo18ExactEventPhase.FINAL_LISTENER_COUNT -> null
            Todo18ExactEventPhase.TRIGGER_BEGIN -> Todo18PipelineEventKind.FRAMEWORK_ACTION_BEGIN
            Todo18ExactEventPhase.TRIGGER_RETURN -> Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN
            Todo18ExactEventPhase.TRIGGER_FAILURE ->
                Todo18PipelineEventKind.FRAMEWORK_ACTION_FAILURE
            Todo18ExactEventPhase.AWAIT_SUCCESS -> Todo18PipelineEventKind.AWAIT_SUCCESS
            Todo18ExactEventPhase.AWAIT_FAILURE -> Todo18PipelineEventKind.AWAIT_FAILURE
            Todo18ExactEventPhase.DETACH -> Todo18PipelineEventKind.DETACH
            Todo18ExactEventPhase.DRAIN -> Todo18PipelineEventKind.DRAIN
        }

    private data class PendingEvent(
        val sourceSequence: Long?,
        val predicate: Boolean? = null,
        val rejected: Boolean = false,
    )
}
