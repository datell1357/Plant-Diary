package com.planterior.helper.diagnostic

import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.EXPECTED_TRANSITION_OBSERVED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.INVALID_CAPTURE
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.valid
import org.junit.Assert.assertEquals
import org.junit.Test

class Todo18DiagnosticReceiptValidationTest {
    @Test
    fun `mandatory envelope rejects missing schema binding identity and lifecycle metadata`() {
        val baseline = valid(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val invalidEnvelopes =
            listOf<(Todo18DiagnosticEnvelope) -> Todo18DiagnosticEnvelope>(
                { it.copy(schema = null) },
                { it.copy(schema = "wrong-schema") },
                { it.copy(waitId = null) },
                { it.copy(expectedSourceSha256 = null) },
                { it.copy(expectedSourceSha256 = "not-a-sha") },
                { it.copy(embeddedSourceSha256 = null) },
                { it.copy(expectedAppApkSha256 = null) },
                { it.copy(observedAppApkSha256 = null) },
                { it.copy(expectedAndroidTestApkSha256 = null) },
                { it.copy(observedAndroidTestApkSha256 = null) },
                { it.copy(bindingValidated = null) },
                { it.copy(installedSinkIdentity = null) },
                { it.copy(runtimeSinkIdentity = null) },
                { it.copy(activitySinkIdentity = null) },
                { it.copy(freshSink = null) },
                { it.copy(initialSequence = null) },
                { it.copy(initialCurrentsEmpty = null) },
                { it.copy(initialListenerCount = null) },
                { it.copy(priorActivityCount = null) },
                { it.copy(priorOverridePresent = null) },
                { it.copy(overrideInstalledAtCapture = null) },
                { it.copy(activityCreateCount = null) },
                { it.copy(activityDestroyCount = null) },
                { it.copy(activityActiveCount = null) },
                { it.copy(previousTeardownComplete = null) },
                { it.copy(captureFinalized = null) },
                { it.copy(detached = null) },
                { it.copy(drained = null) },
                { it.copy(finalListenerCount = null) },
            )

        invalidEnvelopes.forEachIndexed { index, invalid ->
            assertEquals(
                "mandatory-$index",
                INVALID_CAPTURE,
                Todo18DiagnosticReducer.classify(
                    baseline.copy(envelope = invalid(baseline.envelope))
                ),
            )
        }
    }

    @Test
    fun `synchronous action return after product dispatch preserves causal receipt validity`() {
        val baseline = valid()
        val actionReturn =
            baseline.pipeline.single {
                it.kind == Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN
            }
        val causal =
            baseline.pipeline
                .filterNot { it === actionReturn }
                .toMutableList()
                .also { events ->
                    val dispatchReturn = events.indexOfFirst {
                        it.kind == Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN
                    }
                    events.add(dispatchReturn + 1, actionReturn)
                }
                .mapIndexed { index, event -> event.copy(ordinal = index + 1L) }

        assertEquals(
            EXPECTED_TRANSITION_OBSERVED,
            Todo18DiagnosticReducer.classify(baseline.copy(pipeline = causal)),
        )
    }

    @Test
    fun `duplicate unique pipeline event invalidates receipt`() {
        val baseline = valid(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val duplicate =
            baseline.pipeline.first { it.kind == Todo18PipelineEventKind.SCREEN_CALLBACK }
        val receipt =
            baseline.copy(
                pipeline = baseline.pipeline + duplicate.copy(ordinal = baseline.pipeline.size + 1L)
            )

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(receipt))
    }

    @Test
    fun `ordinal or source sequence regression invalidates receipt`() {
        val baseline = valid(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val ordinalRegression =
            baseline.copy(
                pipeline =
                    baseline.pipeline.mapIndexed { index, event ->
                        if (index == 4) event.copy(ordinal = 2L) else event
                    }
            )
        val sourceRegression =
            baseline.copy(
                pipeline =
                    baseline.pipeline.map { event ->
                        if (event.kind == Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE) {
                            event.copy(sourceSequence = 0L)
                        } else {
                            event
                        }
                    }
            )

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(ordinalRegression))
        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(sourceRegression))
    }

    @Test
    fun `diagnostic recorder failure invalidates receipt`() {
        val baseline = valid(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val receipt =
            baseline.copy(
                envelope =
                    baseline.envelope.copy(
                        diagnosticFailures =
                            listOf(Todo18DiagnosticFailure.RECORDER_CALLBACK_FAILED)
                    )
            )

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(receipt))
    }

    @Test
    fun `contradictory predicate acceptance phases invalidate receipt`() {
        val baseline = valid()
        val receipt =
            baseline.copy(
                pipeline =
                    baseline.pipeline.map { event ->
                        if (event.kind == Todo18PipelineEventKind.PREDICATE_TRUE) {
                            event.copy(kind = Todo18PipelineEventKind.PREDICATE_FALSE)
                        } else {
                            event
                        }
                    }
            )

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(receipt))
    }

    @Test
    fun `dispatch return and dispatch failure together are contradictory`() {
        val baseline = valid()
        val receipt =
            baseline.copy(
                pipeline =
                    baseline.pipeline +
                        Todo18PipelineEvent(
                            ordinal = baseline.pipeline.size + 1L,
                            kind = Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE,
                        )
            )

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(receipt))
    }

    @Test
    fun `incomplete finalization detach drain or listener cleanup invalidates receipt`() {
        val baseline = valid(Todo18WaitId.OFFLINE_BEGIN_EDIT)
        val invalidEnvelopes =
            listOf(
                baseline.envelope.copy(captureFinalized = false),
                baseline.envelope.copy(detached = false),
                baseline.envelope.copy(drained = false),
                baseline.envelope.copy(finalListenerCount = 1),
            )

        invalidEnvelopes.forEach { envelope ->
            assertEquals(
                INVALID_CAPTURE,
                Todo18DiagnosticReducer.classify(baseline.copy(envelope = envelope)),
            )
        }
    }

    @Test
    fun `complete mandatory envelope reaches expected transition`() {
        assertEquals(
            EXPECTED_TRANSITION_OBSERVED,
            Todo18DiagnosticReducer.classify(valid(Todo18WaitId.OFFLINE_BEGIN_EDIT)),
        )
        assertEquals(
            EXPECTED_TRANSITION_OBSERVED,
            Todo18DiagnosticReducer.classify(valid(Todo18WaitId.CONFLICT_BEGIN_EDIT)),
        )
    }
}
