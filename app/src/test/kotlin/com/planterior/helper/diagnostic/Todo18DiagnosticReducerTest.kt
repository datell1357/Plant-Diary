package com.planterior.helper.diagnostic

import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.CONTROLLER_REJECTED_ACTION
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.CROSS_TEST_LEAK
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.EXPECTED_TRANSITION_OBSERVED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.INVALID_CAPTURE
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.PREDICATE_MISMATCH
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.PROBE_REGISTRATION_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.PROBE_REJECTED_MATCH
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.ROUTE_ACTION_NOT_FORWARDED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.ROUTE_STATE_NOT_OBSERVED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.RUNTIME_SINK_IDENTITY_MISMATCH
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.STALE_UI_CONTROLLER_BINDING
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.STREAM_DISPATCH_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.TASK1_PUBLICATION_MISSING
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.TEST_INTERACTION_REJECTED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.UI_POSTCONDITION_MISSING
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.valid
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.withKinds
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.AWAIT_FAILURE
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.CONTROLLER_ENTRY
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.CONTROLLER_TARGET_STATE
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.DETACH
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.DRAIN
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.EVENT_REJECTED
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.FRAMEWORK_ACTION_BEGIN
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.FRAMEWORK_ACTION_FAILURE
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.PREDICATE_FALSE
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.ROUTE_STATE_OBSERVED
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.SCREEN_CALLBACK
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.TASK1_PUBLICATION
import com.planterior.helper.diagnostic.Todo18PipelineEventKind.UI_POSTCONDITION
import org.junit.Assert.assertEquals
import org.junit.Test

class Todo18DiagnosticReducerTest {
    @Test
    fun `ordered reducer classifies all fifteen first-match rows`() {
        assertAllRows(valid(Todo18WaitId.CONFLICT_BEGIN_EDIT))
    }

    private fun assertAllRows(baseline: Todo18DiagnosticReceipt) {
        val cases =
            listOf(
                INVALID_CAPTURE to
                    baseline.copy(envelope = baseline.envelope.copy(bindingValidated = false)),
                CROSS_TEST_LEAK to
                    baseline.copy(envelope = baseline.envelope.copy(freshSink = false)),
                RUNTIME_SINK_IDENTITY_MISMATCH to
                    baseline.copy(
                        envelope = baseline.envelope.copy(activitySinkIdentity = "sink-2")
                    ),
                TEST_INTERACTION_REJECTED to
                    baseline.withKinds(
                        FRAMEWORK_ACTION_BEGIN,
                        FRAMEWORK_ACTION_FAILURE,
                        AWAIT_FAILURE,
                        DETACH,
                        DRAIN,
                    ),
                ROUTE_ACTION_NOT_FORWARDED to
                    baseline.withKinds(
                        FRAMEWORK_ACTION_BEGIN,
                        FRAMEWORK_ACTION_RETURN,
                        SCREEN_CALLBACK,
                        AWAIT_FAILURE,
                        DETACH,
                        DRAIN,
                    ),
                CONTROLLER_REJECTED_ACTION to
                    baseline.withKinds(
                        FRAMEWORK_ACTION_BEGIN,
                        FRAMEWORK_ACTION_RETURN,
                        SCREEN_CALLBACK,
                        CONTROLLER_ENTRY,
                        AWAIT_FAILURE,
                        DETACH,
                        DRAIN,
                    ),
                STALE_UI_CONTROLLER_BINDING to
                    baseline
                        .withKinds(
                            FRAMEWORK_ACTION_BEGIN,
                            FRAMEWORK_ACTION_RETURN,
                            SCREEN_CALLBACK,
                            CONTROLLER_ENTRY,
                            CONTROLLER_TARGET_STATE,
                            ROUTE_STATE_OBSERVED,
                            AWAIT_FAILURE,
                            DETACH,
                            DRAIN,
                        )
                        .withControllerIdentity(99),
                ROUTE_STATE_NOT_OBSERVED to
                    baseline.withKinds(
                        FRAMEWORK_ACTION_BEGIN,
                        FRAMEWORK_ACTION_RETURN,
                        SCREEN_CALLBACK,
                        CONTROLLER_ENTRY,
                        CONTROLLER_TARGET_STATE,
                        AWAIT_FAILURE,
                        DETACH,
                        DRAIN,
                    ),
                TASK1_PUBLICATION_MISSING to
                    baseline.withKinds(
                        FRAMEWORK_ACTION_BEGIN,
                        FRAMEWORK_ACTION_RETURN,
                        SCREEN_CALLBACK,
                        CONTROLLER_ENTRY,
                        CONTROLLER_TARGET_STATE,
                        ROUTE_STATE_OBSERVED,
                        AWAIT_FAILURE,
                        DETACH,
                        DRAIN,
                    ),
                STREAM_DISPATCH_MISSED to
                    baseline.withKinds(
                        FRAMEWORK_ACTION_BEGIN,
                        FRAMEWORK_ACTION_RETURN,
                        SCREEN_CALLBACK,
                        CONTROLLER_ENTRY,
                        CONTROLLER_TARGET_STATE,
                        ROUTE_STATE_OBSERVED,
                        TASK1_PUBLICATION,
                        AWAIT_FAILURE,
                        DETACH,
                        DRAIN,
                    ),
                PROBE_REGISTRATION_MISSED to
                    baseline.withKinds(
                        FRAMEWORK_ACTION_BEGIN,
                        FRAMEWORK_ACTION_RETURN,
                        SCREEN_CALLBACK,
                        CONTROLLER_ENTRY,
                        CONTROLLER_TARGET_STATE,
                        ROUTE_STATE_OBSERVED,
                        TASK1_PUBLICATION,
                        PRIMARY_DISPATCH_BEGIN,
                        PRIMARY_DISPATCH_RETURN,
                        AWAIT_FAILURE,
                        DETACH,
                        DRAIN,
                    ),
                PROBE_REJECTED_MATCH to
                    baseline.withKinds(
                        FRAMEWORK_ACTION_BEGIN,
                        FRAMEWORK_ACTION_RETURN,
                        SCREEN_CALLBACK,
                        CONTROLLER_ENTRY,
                        CONTROLLER_TARGET_STATE,
                        ROUTE_STATE_OBSERVED,
                        TASK1_PUBLICATION,
                        PRIMARY_DISPATCH_BEGIN,
                        PRIMARY_DISPATCH_RETURN,
                        SUBSCRIPTION_RECEIVE,
                        EVENT_REJECTED,
                        AWAIT_FAILURE,
                        DETACH,
                        DRAIN,
                    ),
                PREDICATE_MISMATCH to
                    baseline.withKinds(
                        FRAMEWORK_ACTION_BEGIN,
                        FRAMEWORK_ACTION_RETURN,
                        SCREEN_CALLBACK,
                        CONTROLLER_ENTRY,
                        CONTROLLER_TARGET_STATE,
                        ROUTE_STATE_OBSERVED,
                        TASK1_PUBLICATION,
                        PRIMARY_DISPATCH_BEGIN,
                        PRIMARY_DISPATCH_RETURN,
                        SUBSCRIPTION_RECEIVE,
                        PREDICATE_FALSE,
                        EVENT_REJECTED,
                        AWAIT_FAILURE,
                        DETACH,
                        DRAIN,
                    ),
                UI_POSTCONDITION_MISSING to
                    baseline.copy(
                        pipeline = baseline.pipeline.filterNot { it.kind == UI_POSTCONDITION }
                    ),
                EXPECTED_TRANSITION_OBSERVED to baseline,
            )

        cases.forEach { (expected, receipt) ->
            assertEquals(expected.name, expected, Todo18DiagnosticReducer.classify(receipt))
        }
    }

    private fun Todo18DiagnosticReceipt.withControllerIdentity(
        routeIdentity: Int
    ): Todo18DiagnosticReceipt =
        copy(
            pipeline =
                pipeline.map { event ->
                    if (event.kind == ROUTE_STATE_OBSERVED) {
                        event.copy(controllerIdentity = routeIdentity)
                    } else {
                        event
                    }
                }
        )
}
