package com.planterior.helper.diagnostic

import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.CROSS_TEST_LEAK
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.EXPECTED_TRANSITION_OBSERVED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.INVALID_CAPTURE
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.TEST_INTERACTION_REJECTED
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.valid
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.withKinds
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.AWAIT_FAILURE
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.DETACH
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.DRAIN
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.FRAMEWORK_ACTION_BEGIN
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.FRAMEWORK_ACTION_FAILURE
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN
import org.junit.Assert.assertEquals
import org.junit.Test

class Todo18DiagnosticReducerPrecedenceTest {
    @Test
    fun `first invalid row wins every precedence collision`() {
        val baseline = valid()
        val receipt =
            baseline.copy(
                envelope =
                    baseline.envelope.copy(
                        bindingValidated = false,
                        freshSink = false,
                        activitySinkIdentity = "mismatch",
                    ),
                pipeline =
                    baseline.withKinds(FRAMEWORK_ACTION_BEGIN, FRAMEWORK_ACTION_FAILURE).pipeline,
            )

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(receipt))
    }

    @Test
    fun `cross-test leak wins identity and pipeline collisions`() {
        val baseline = valid()
        val receipt =
            baseline.copy(
                envelope =
                    baseline.envelope.copy(
                        freshSink = false,
                        activitySinkIdentity = "mismatch",
                    ),
                pipeline =
                    baseline
                        .withKinds(
                            FRAMEWORK_ACTION_BEGIN,
                            FRAMEWORK_ACTION_FAILURE,
                            AWAIT_FAILURE,
                            DETACH,
                            DRAIN,
                        )
                        .pipeline,
            )

        assertEquals(CROSS_TEST_LEAK, Todo18DiagnosticReducer.classify(receipt))
    }

    @Test
    fun `product-stage absence falls through instead of invalidating capture`() {
        val receipt =
            valid()
                .withKinds(
                    FRAMEWORK_ACTION_BEGIN,
                    FRAMEWORK_ACTION_RETURN,
                    AWAIT_FAILURE,
                    DETACH,
                    DRAIN,
                )

        assertEquals(TEST_INTERACTION_REJECTED, Todo18DiagnosticReducer.classify(receipt))
    }

    @Test
    fun `offline initial wait skips non-applicable action-controller stages`() {
        assertEquals(
            EXPECTED_TRANSITION_OBSERVED,
            Todo18DiagnosticReducer.classify(valid(Todo18WaitId.OFFLINE_INITIAL_VIEWING)),
        )
    }

    @Test
    fun `offline begin-edit uses the complete transition contract without conflict leakage`() {
        val conflict = valid(Todo18WaitId.CONFLICT_BEGIN_EDIT)
        val offline = valid(Todo18WaitId.OFFLINE_BEGIN_EDIT)

        val offlineOnly =
            setOf(
                Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY,
                Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY,
                Todo18PipelineEventKind.DISPLAYED_SINK_RETURN,
                Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN,
            )
        assertEquals(
            conflict.pipeline.map(Todo18PipelineEvent::kind),
            offline.pipeline.map(Todo18PipelineEvent::kind).filterNot(offlineOnly::contains),
        )
        assertEquals(EXPECTED_TRANSITION_OBSERVED, Todo18DiagnosticReducer.classify(conflict))
        assertEquals(EXPECTED_TRANSITION_OBSERVED, Todo18DiagnosticReducer.classify(offline))
    }
}
