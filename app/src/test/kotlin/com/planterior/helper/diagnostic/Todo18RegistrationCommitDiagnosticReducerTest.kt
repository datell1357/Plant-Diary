package com.planterior.helper.diagnostic

import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_COMPLETED_PUBLICATION_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_CONTROLLER_ENTRY_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_DUPLICATE_LOOKUP_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_NAVIGATION_DESTINATION_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_NAVIGATION_DISPATCH_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_NAVIGATION_ENQUEUE_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_REMOTE_COMMIT_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_REPOSITORY_ENTRY_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_SUBMIT_CALLBACK_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_VALIDATION_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.COMMIT_VALIDATION_REJECTED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.EXPECTED_TRANSITION_OBSERVED
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.INVALID_CAPTURE
import org.junit.Assert.assertEquals
import org.junit.Test

class Todo18RegistrationCommitDiagnosticReducerTest {
    @Test
    fun `registration commit reducer selects each missing boundary in order`() {
        // Given
        val receipt = validCommitReceipt()
        val cases =
            listOf(
                Todo18PipelineEventKind.SUBMIT_CALLBACK to COMMIT_SUBMIT_CALLBACK_MISSED,
                Todo18PipelineEventKind.REGISTRATION_CONTROLLER_ENTRY to
                    COMMIT_CONTROLLER_ENTRY_MISSED,
                Todo18PipelineEventKind.REGISTRATION_VALIDATION_ACCEPTED to
                    COMMIT_VALIDATION_MISSED,
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_BEGIN to COMMIT_DUPLICATE_LOOKUP_MISSED,
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY to
                    COMMIT_REPOSITORY_ENTRY_MISSED,
                Todo18PipelineEventKind.REMOTE_COMMIT to COMMIT_REMOTE_COMMIT_MISSED,
                Todo18PipelineEventKind.REGISTRATION_COMPLETED_PUBLICATION to
                    COMMIT_COMPLETED_PUBLICATION_MISSED,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED to
                    COMMIT_NAVIGATION_ENQUEUE_MISSED,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED to
                    COMMIT_NAVIGATION_DISPATCH_MISSED,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DESTINATION to
                    COMMIT_NAVIGATION_DESTINATION_MISSED,
            )

        // When / Then
        cases.forEach { (missing, expected) ->
            assertEquals(expected, Todo18DiagnosticReducer.classify(receipt.without(missing)))
        }
    }

    @Test
    fun `registration commit reducer selects a rejected validation before later boundaries`() {
        // Given
        val receipt =
            validCommitReceipt()
                .replace(
                    Todo18PipelineEventKind.REGISTRATION_VALIDATION_ACCEPTED,
                    Todo18PipelineEventKind.REGISTRATION_VALIDATION_REJECTED,
                )

        // When
        val actual = Todo18DiagnosticReducer.classify(receipt)

        // Then
        assertEquals(COMMIT_VALIDATION_REJECTED, actual)
    }

    @Test
    fun `registration commit rejects duplicate stages and recorder faults as invalid capture`() {
        // Given
        val receipt = validCommitReceipt()
        val duplicate = receipt.pipeline.first { it.kind == Todo18PipelineEventKind.REMOTE_COMMIT }
        val duplicated =
            receipt.copy(
                pipeline = receipt.pipeline + duplicate.copy(ordinal = receipt.pipeline.size + 1L)
            )
        val faulted =
            receipt.copy(
                envelope =
                    receipt.envelope.copy(
                        diagnosticFailures =
                            listOf(Todo18DiagnosticFailure.RECORDER_CALLBACK_FAILED)
                    )
            )

        // When / Then
        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(duplicated))
        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(faulted))
    }

    @Test
    fun `registration commit rejects a repository plant that differs from the navigation plant`() {
        // Given
        val receipt = validCommitReceipt()
        val mismatched =
            receipt.copy(
                pipeline =
                    receipt.pipeline.map { event ->
                        if (event.kind == Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY) {
                            event.copy(
                                registrationPlantId =
                                    com.planterior.helper.core.model.PersonalPlantId(
                                        "different-plant"
                                    )
                            )
                        } else event
                    }
            )

        // When
        val actual = Todo18DiagnosticReducer.classify(mismatched)

        // Then
        assertEquals(INVALID_CAPTURE, actual)
    }

    @Test
    fun `registration commit accepts the complete exact receipt`() {
        // Given
        val receipt = validCommitReceipt()

        // When
        val actual = Todo18DiagnosticReducer.classify(receipt)

        // Then
        assertEquals(EXPECTED_TRANSITION_OBSERVED, actual)
    }

    @Test
    fun `registration persistence sequence accepts exact identities and elapsed order`() {
        val receipt = validCommitReceipt().withPersistenceSuccess()

        assertEquals(EXPECTED_TRANSITION_OBSERVED, Todo18DiagnosticReducer.classify(receipt))
        assertEquals(
            listOf(
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_ENTERED,
                Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_RETURNED,
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_ENTERED,
                Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED,
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_ENTERED,
                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_RETURNED,
                Todo18PipelineEventKind.REGISTRATION_COMPLETED_RETURNED,
            ),
            receipt.pipeline
                .filter { it.registrationAccountId != null }
                .map(Todo18PipelineEvent::kind),
        )
    }

    @Test
    fun `registration persistence rejects missing duplicate misordered and wrong identity`() {
        val receipt = validCommitReceipt().withPersistenceSuccess()
        val missing =
            receipt.copy(
                pipeline =
                    receipt.pipeline.filterNot {
                        it.kind == Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED
                    }
            )
        val duplicate =
            receipt.copy(
                pipeline =
                    receipt.pipeline +
                        receipt.pipeline.first {
                            it.kind == Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_RETURNED
                        }
            )
        val misordered =
            receipt.copy(
                pipeline =
                    receipt.pipeline.map { event ->
                        when (event.kind) {
                            Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_ENTERED ->
                                event.copy(
                                    kind =
                                        Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED
                                )
                            Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED ->
                                event.copy(
                                    kind = Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_ENTERED
                                )
                            else -> event
                        }
                    }
            )
        val wrongIdentity =
            receipt.copy(
                pipeline =
                    receipt.pipeline.map { event ->
                        if (
                            event.kind ==
                                Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_RETURNED
                        ) {
                            event.copy(
                                registrationAccountId =
                                    com.planterior.helper.core.model.AccountId("other")
                            )
                        } else event
                    }
            )

        listOf(missing, duplicate, misordered, wrongIdentity).forEach {
            assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(it))
        }
    }

    @Test
    fun `registration persistence rejects orphan completed and entered without terminal`() {
        val complete = validCommitReceipt().withPersistenceSuccess()
        val orphanCompleted =
            complete.copy(
                pipeline =
                    complete.pipeline
                        .filterNot { event ->
                            event.registrationAccountId != null &&
                                event.kind !=
                                    Todo18PipelineEventKind.REGISTRATION_COMPLETED_RETURNED
                        }
                        .mapIndexed { index, event -> event.copy(ordinal = index + 1L) }
            )
        val enteredWithoutTerminal =
            complete.copy(
                pipeline =
                    complete.pipeline
                        .filterNot { event ->
                            event.kind in
                                setOf(
                                    Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED,
                                    Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_ENTERED,
                                    Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_RETURNED,
                                    Todo18PipelineEventKind.REGISTRATION_COMPLETED_RETURNED,
                                )
                        }
                        .mapIndexed { index, event -> event.copy(ordinal = index + 1L) }
            )

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(orphanCompleted))
        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(enteredWithoutTerminal))
    }

    private fun validCommitReceipt(): Todo18DiagnosticReceipt =
        Todo18DiagnosticReceiptFixtures.valid(Todo18WaitId.REGISTRATION_COMMIT)
            .copy(
                pipeline =
                    listOf(
                            Todo18PipelineEventKind.FRAMEWORK_ACTION_BEGIN,
                            Todo18PipelineEventKind.SUBMIT_CALLBACK,
                            Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN,
                            Todo18PipelineEventKind.REGISTRATION_CONTROLLER_ENTRY,
                            Todo18PipelineEventKind.REGISTRATION_VALIDATION_ACCEPTED,
                            Todo18PipelineEventKind.DUPLICATE_LOOKUP_BEGIN,
                            Todo18PipelineEventKind.DUPLICATE_LOOKUP_EMPTY,
                            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY,
                            Todo18PipelineEventKind.REMOTE_COMMIT,
                            Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED,
                            Todo18PipelineEventKind.REGISTRATION_COMPLETED_PUBLICATION,
                            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED,
                            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED,
                            Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DESTINATION,
                            Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE,
                            Todo18PipelineEventKind.PREDICATE_TRUE,
                            Todo18PipelineEventKind.EVENT_ACCEPTED,
                            Todo18PipelineEventKind.AWAIT_SUCCESS,
                            Todo18PipelineEventKind.DETACH,
                            Todo18PipelineEventKind.DRAIN,
                        )
                        .mapIndexed { index, kind ->
                            Todo18PipelineEvent(
                                ordinal = index + 1L,
                                kind = kind,
                                controllerIdentity = if (kind in CONTROLLER_EVENTS) 71 else null,
                                registrationPlantId =
                                    if (kind in PLANT_EVENTS) {
                                        com.planterior.helper.core.model.PersonalPlantId("plant")
                                    } else {
                                        null
                                    },
                                registrationOperationId =
                                    if (kind in REPOSITORY_EVENTS) {
                                        com.planterior.helper.core.model.OperationId("operation")
                                    } else {
                                        null
                                    },
                                repositoryIdentity = if (kind in REPOSITORY_EVENTS) 17 else null,
                                navigationIdentity =
                                    if (kind in NAVIGATION_EVENTS) "navigation" else null,
                            )
                        }
            )

    private fun Todo18DiagnosticReceipt.without(kind: Todo18PipelineEventKind) =
        copy(
            pipeline =
                pipeline
                    .filterNot { it.kind == kind }
                    .mapIndexed { index, event ->
                        event.copy(ordinal = index + 1L)
                    }
        )

    private fun Todo18DiagnosticReceipt.withPersistenceSuccess(): Todo18DiagnosticReceipt {
        val account = com.planterior.helper.core.model.AccountId("account")
        val operation = com.planterior.helper.core.model.OperationId("operation")
        val plant = com.planterior.helper.core.model.PersonalPlantId("plant")
        val persistence =
            listOf(
                    Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_ENTERED,
                    Todo18PipelineEventKind.REGISTRATION_COMMITTED_READ_RETURNED,
                    Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_ENTERED,
                    Todo18PipelineEventKind.REGISTRATION_CACHE_UPSERT_RETURNED,
                    Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_ENTERED,
                    Todo18PipelineEventKind.REGISTRATION_OUTBOX_REMOVE_RETURNED,
                    Todo18PipelineEventKind.REGISTRATION_COMPLETED_RETURNED,
                )
                .mapIndexed { index, kind ->
                    Todo18PipelineEvent(
                        ordinal = 0,
                        kind = kind,
                        registrationAccountId = account,
                        registrationOperationId = operation,
                        registrationPlantId = plant,
                        elapsedNanos = index.toLong(),
                    )
                }
        return copy(
            pipeline =
                pipeline
                    .flatMap { event ->
                        if (
                            event.kind == Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED
                        ) {
                            persistence + event
                        } else {
                            listOf(event)
                        }
                    }
                    .mapIndexed { index, event -> event.copy(ordinal = index + 1L) }
        )
    }

    private fun Todo18DiagnosticReceipt.replace(
        original: Todo18PipelineEventKind,
        replacement: Todo18PipelineEventKind,
    ) =
        copy(
            pipeline =
                pipeline.map { event ->
                    if (event.kind == original) event.copy(kind = replacement) else event
                }
        )

    private companion object {
        val CONTROLLER_EVENTS =
            setOf(
                Todo18PipelineEventKind.SUBMIT_CALLBACK,
                Todo18PipelineEventKind.REGISTRATION_CONTROLLER_ENTRY,
                Todo18PipelineEventKind.REGISTRATION_VALIDATION_ACCEPTED,
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_BEGIN,
                Todo18PipelineEventKind.DUPLICATE_LOOKUP_EMPTY,
                Todo18PipelineEventKind.REGISTRATION_COMPLETED_PUBLICATION,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED,
            )
        val REPOSITORY_EVENTS =
            setOf(
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY,
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED,
            )
        val PLANT_EVENTS =
            setOf(
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_ENTRY,
                Todo18PipelineEventKind.REGISTRATION_REPOSITORY_COMPLETED,
                Todo18PipelineEventKind.REMOTE_COMMIT,
                Todo18PipelineEventKind.REGISTRATION_COMPLETED_PUBLICATION,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DESTINATION,
            )
        val NAVIGATION_EVENTS =
            setOf(
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_ENQUEUED,
                Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DISPATCHED,
            )
    }
}
