package com.planterior.helper.diagnostic

import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.EXPECTED_TRANSITION_OBSERVED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.INVALID_CAPTURE
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.OFFLINE_ACTIVITY_NAVHOST_MISMATCH
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.OFFLINE_CALLBACK_MISSING
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.OFFLINE_CALLBACK_SINK_BINDING_MISMATCH
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.OFFLINE_CALLBACK_STALE
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.OFFLINE_PRIMARY_DISPATCH_MISSING
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.OFFLINE_SINK_ENTRY_MISSING
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.OFFLINE_SINK_RETURN_MISSING
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.OFFLINE_TASK1_PUBLICATION_MISSING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18OfflineRuntimeReducerTest {
    private val valid = Todo18DiagnosticReceiptFixtures.valid(Todo18WaitId.OFFLINE_BEGIN_EDIT)

    @Test
    fun `complete offline runtime receipt is valid`() {
        assertTrue(Todo18DiagnosticReceiptValidator.isValid(valid))
    }

    @Test
    fun `missing callback entry has first offline precedence`() {
        val receipt =
            valid.without(
                Todo18PipelineEventKind.DISPLAYED_CALLBACK_ENTRY,
                Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY,
            )

        assertEquals(OFFLINE_CALLBACK_MISSING, Todo18DiagnosticReducer.classify(receipt))
        assertEquals(
            OFFLINE_CALLBACK_MISSING,
            Todo18DiagnosticReducer.classify(
                valid.without(Todo18PipelineEventKind.DISPLAYED_CALLBACK_RETURN)
            ),
        )
    }

    @Test
    fun `generation mismatch classifies stale callback`() {
        val receipt =
            valid
                .mapRuntimeBindings { binding ->
                    binding.copy(callbackGeneration = binding.callbackGeneration + 1L)
                }
                .restoreRouteBinding()

        assertEquals(OFFLINE_CALLBACK_STALE, Todo18DiagnosticReducer.classify(receipt))
        assertEquals(
            OFFLINE_CALLBACK_STALE,
            Todo18DiagnosticReducer.classify(
                valid.mapRuntimeBindings {
                    it.copy(disposeGeneration = it.attachGeneration)
                }
            ),
        )
        assertEquals(
            OFFLINE_CALLBACK_STALE,
            Todo18DiagnosticReducer.classify(
                valid.mapRuntimeBindings { it.copy(lifecycleState = "STARTED") }
            ),
        )
    }

    @Test
    fun `each event controller identity must be present numeric and equal to its binding`() {
        val missing = valid.mapSinkEntry { it.copy(controllerIdentity = null) }
        val nonNumeric =
            valid.copy(
                pipeline =
                    valid.pipeline.map { event ->
                        event.copy(
                            runtimeBinding =
                                event.runtimeBinding?.copy(controllerIdentity = "controller")
                        )
                    }
            )
        val mismatch = valid.mapSinkEntry {
            it.copy(controllerIdentity = requireNotNull(it.controllerIdentity) + 1)
        }

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(missing))
        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(nonNumeric))
        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(mismatch))
    }

    @Test
    fun `equal or lower callback generations are stale while current generations are valid`() {
        val lowerCollector = valid.mapRuntimeBindings { binding ->
            binding.copy(collectorGeneration = binding.attachGeneration - 1L)
        }
        val equalCollector = valid.mapRuntimeBindings { binding ->
            binding.copy(collectorGeneration = binding.attachGeneration)
        }
        val lowerCallback = valid.mapRuntimeBindings { binding ->
            binding.copy(callbackGeneration = binding.attachGeneration - 1L)
        }
        val equalCallback = valid.mapRuntimeBindings { binding ->
            binding.copy(callbackGeneration = binding.attachGeneration)
        }

        assertEquals(OFFLINE_CALLBACK_STALE, Todo18DiagnosticReducer.classify(lowerCollector))
        assertEquals(OFFLINE_CALLBACK_STALE, Todo18DiagnosticReducer.classify(equalCollector))
        assertEquals(OFFLINE_CALLBACK_STALE, Todo18DiagnosticReducer.classify(lowerCallback))
        assertEquals(OFFLINE_CALLBACK_STALE, Todo18DiagnosticReducer.classify(equalCallback))
        assertEquals(EXPECTED_TRANSITION_OBSERVED, Todo18DiagnosticReducer.classify(valid))
    }

    @Test
    fun `activity or NavHost mismatch is distinct from callback staleness`() {
        val receipt = valid.mapRuntimeBindings { binding ->
            binding.copy(activityIdentity = "activity-2")
        }

        assertEquals(
            OFFLINE_ACTIVITY_NAVHOST_MISMATCH,
            Todo18DiagnosticReducer.classify(receipt),
        )
    }

    @Test
    fun `callback sink mismatch is distinct from runtime envelope mismatch`() {
        val receipt = valid.mapRuntimeBindings { binding ->
            binding.copy(callbackSinkIdentity = "sink-2")
        }

        assertEquals(
            OFFLINE_CALLBACK_SINK_BINDING_MISMATCH,
            Todo18DiagnosticReducer.classify(receipt),
        )
    }

    @Test
    fun `sink entry and return have independent classifications`() {
        assertEquals(
            OFFLINE_SINK_ENTRY_MISSING,
            Todo18DiagnosticReducer.classify(
                valid.without(Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY)
            ),
        )
        assertEquals(
            OFFLINE_SINK_RETURN_MISSING,
            Todo18DiagnosticReducer.classify(
                valid.without(Todo18PipelineEventKind.DISPLAYED_SINK_RETURN)
            ),
        )
    }

    @Test
    fun `Task1 and primary dispatch have independent classifications`() {
        assertEquals(
            OFFLINE_TASK1_PUBLICATION_MISSING,
            Todo18DiagnosticReducer.classify(
                valid.without(Todo18PipelineEventKind.TASK1_PUBLICATION)
            ),
        )
        assertEquals(
            OFFLINE_PRIMARY_DISPATCH_MISSING,
            Todo18DiagnosticReducer.classify(
                valid.replace(
                    Todo18PipelineEventKind.PRIMARY_DISPATCH_RETURN,
                    Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE,
                )
            ),
        )
    }

    @Test
    fun `offline runtime precedence is stable across simultaneous failures`() {
        val stale =
            valid
                .mapRuntimeBindings {
                    it.copy(
                        callbackGeneration = it.callbackGeneration + 1L,
                        activityIdentity = "activity-2",
                        callbackSinkIdentity = "sink-2",
                    )
                }
                .restoreRouteBinding()
        val activityAndSink = valid.mapRuntimeBindings {
            it.copy(activityIdentity = "activity-2", callbackSinkIdentity = "sink-2")
        }
        val sinkAndEntry =
            valid
                .mapRuntimeBindings { it.copy(callbackSinkIdentity = "sink-2") }
                .without(Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY)

        assertEquals(OFFLINE_CALLBACK_STALE, Todo18DiagnosticReducer.classify(stale))
        assertEquals(
            OFFLINE_ACTIVITY_NAVHOST_MISMATCH,
            Todo18DiagnosticReducer.classify(activityAndSink),
        )
        assertEquals(
            OFFLINE_CALLBACK_SINK_BINDING_MISMATCH,
            Todo18DiagnosticReducer.classify(sinkAndEntry),
        )
    }

    @Test
    fun `offline schema rejects missing bindings identities and stage disorder`() {
        val missingBinding =
            valid.copy(
                pipeline =
                    valid.pipeline.map { event ->
                        if (event.kind == Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY) {
                            event.copy(runtimeBinding = null)
                        } else {
                            event
                        }
                    }
            )
        val missingHost = valid.copy(envelope = valid.envelope.copy(navHostInstanceIdentity = null))
        val reversed =
            valid.copy(
                pipeline =
                    valid.pipeline.reversed().mapIndexed { index, event ->
                        event.copy(ordinal = index + 1L)
                    }
            )

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(missingBinding))
        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(missingHost))
        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(reversed))
    }

    @Test
    fun `malformed runtime metadata remains invalid`() {
        val receipt = valid.mapRuntimeBindings { binding ->
            binding.copy(lifecycleOwnerIdentity = "")
        }

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(receipt))
    }

    private fun Todo18DiagnosticReceipt.without(
        vararg removed: Todo18PipelineEventKind
    ): Todo18DiagnosticReceipt =
        copy(
            pipeline =
                pipeline
                    .filterNot { it.kind in removed }
                    .mapIndexed { index, event ->
                        event.copy(ordinal = index + 1L)
                    }
        )

    private fun Todo18DiagnosticReceipt.replace(
        old: Todo18PipelineEventKind,
        new: Todo18PipelineEventKind,
    ): Todo18DiagnosticReceipt =
        copy(pipeline = pipeline.map { if (it.kind == old) it.copy(kind = new) else it })

    private fun Todo18DiagnosticReceipt.mapSinkEntry(
        transform: (Todo18PipelineEvent) -> Todo18PipelineEvent
    ): Todo18DiagnosticReceipt =
        copy(
            pipeline =
                pipeline.map { event ->
                    if (event.kind == Todo18PipelineEventKind.DISPLAYED_SINK_ENTRY) {
                        transform(event)
                    } else {
                        event
                    }
                }
        )

    private fun Todo18DiagnosticReceipt.mapRuntimeBindings(
        transform: (Todo18RuntimeBinding) -> Todo18RuntimeBinding
    ): Todo18DiagnosticReceipt =
        copy(
            pipeline =
                pipeline.map { event ->
                    event.copy(runtimeBinding = event.runtimeBinding?.let(transform))
                }
        )

    private fun Todo18DiagnosticReceipt.restoreRouteBinding(): Todo18DiagnosticReceipt {
        val routeBinding =
            Todo18DiagnosticReceiptFixtures.valid(Todo18WaitId.OFFLINE_BEGIN_EDIT)
                .pipeline
                .single { it.kind == Todo18PipelineEventKind.ROUTE_STATE_OBSERVED }
                .runtimeBinding
        return copy(
            pipeline =
                pipeline.map { event ->
                    if (event.kind == Todo18PipelineEventKind.ROUTE_STATE_OBSERVED) {
                        event.copy(runtimeBinding = routeBinding)
                    } else {
                        event
                    }
                }
        )
    }
}
