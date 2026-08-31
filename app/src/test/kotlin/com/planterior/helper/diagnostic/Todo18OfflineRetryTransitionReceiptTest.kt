package com.planterior.helper.diagnostic

import com.planterior.helper.feature.minihome.MiniHomeRetryStage
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Todo18OfflineRetryTransitionReceiptTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `complete receipt requires exact ordered operation and revision identity`() {
        receipt().requireComplete(OPERATION, OPERATION, REVISION)
    }

    @Test
    fun `missing duplicate and out of order receipts are rejected`() {
        val valid = observations()
        listOf(
                valid.dropLast(1),
                valid + valid.last(),
                valid.toMutableList().apply { add(2, removeAt(3)) },
            )
            .forEach { observations ->
                assertThrows(IllegalArgumentException::class.java) {
                    Todo18OfflineRetryTransitionReceipt(observations, true)
                        .requireComplete(OPERATION, OPERATION, REVISION)
                }
            }
    }

    @Test
    fun `operation revision boundary and closure mismatches are rejected`() {
        val wrongOperation = observations().toMutableList()
        wrongOperation[2] = wrongOperation[2].copy(operationId = "wrong")
        val wrongRevision = observations().toMutableList()
        wrongRevision[3] = wrongRevision[3].copy(revision = REVISION + 1)
        listOf(
                Todo18OfflineRetryTransitionReceipt(wrongOperation, true) to OPERATION,
                Todo18OfflineRetryTransitionReceipt(wrongRevision, true) to OPERATION,
                Todo18OfflineRetryTransitionReceipt(observations(), true) to "wrong-boundary",
                Todo18OfflineRetryTransitionReceipt(observations(), false) to OPERATION,
            )
            .forEach { (receipt, boundary) ->
                assertThrows(IllegalArgumentException::class.java) {
                    receipt.requireComplete(OPERATION, boundary, REVISION)
                }
            }
    }

    @Test
    fun `failure closes and serializes exact observed prefix and primary identity`() {
        val recorder = Todo18OfflineRetryTransitionRecorder()
        observations().take(3).forEach(recorder::record)
        val primary = AssertionError("displayed wait timed out")
        val receipt = recorder.close(primary)
        val file = temporaryFolder.newFile("offline-partial.json")

        writeTodo18OfflineRetryTransitionReceipt(file, receipt)

        val json = file.readText()
        assertTrue(json.contains("\"status\":\"partial\""))
        assertTrue(json.contains(AssertionError::class.java.name))
        assertTrue(json.contains("displayed wait timed out"))
        assertTrue(json.contains("RAW_CONTROLLER_STATE"))
        assertTrue(!json.contains("ROUTE_DISPLAYED_CALLBACK"))
    }

    @Test
    fun `validation failure after close is rebound as the primary partial outcome`() {
        val recorder = Todo18OfflineRetryTransitionRecorder()
        observations().dropLast(1).forEach(recorder::record)
        val closed = recorder.close()
        val primary =
            assertThrows(IllegalArgumentException::class.java) {
                closed.requireComplete(OPERATION, OPERATION, REVISION)
            }
        val receipt = closed.withPrimaryFailure(primary)
        val file = temporaryFolder.newFile("offline-close-then-validation-failure.json")

        writeTodo18OfflineRetryTransitionReceipt(file, receipt)

        val json = file.readText()
        assertTrue(json.contains("\"status\":\"partial\""))
        assertTrue(json.contains(primary.javaClass.name))
        assertTrue(json.contains(requireNotNull(primary.message)))
        assertTrue(json.contains("ROUTE_DISPLAYED_CALLBACK"))
        assertTrue(!json.contains("RENDERED_SINK_DELIVERY"))
    }

    @Test
    fun `retry boundary identities and generations are serialized`() {
        val receipt =
            Todo18OfflineRetryTransitionReceipt(
                observations = emptyList(),
                closed = true,
                retryObservations =
                    listOf(
                        Todo18OfflineRetryBoundaryObservation(
                            stage = MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED,
                            operationId = OPERATION,
                            controllerEpoch = 3L,
                            controllerGeneration = 7L,
                            saveGeneration = 2L,
                            guardDraftIdentity = true,
                            revision = REVISION,
                            outcome = "returned",
                            resultIdentity = 99,
                            failureIdentity = null,
                            failureClass = null,
                            failureMessage = null,
                        )
                    ),
            )
        val file = temporaryFolder.newFile("offline-boundary.json")

        writeTodo18OfflineRetryTransitionReceipt(file, receipt)

        val json = file.readText()
        assertTrue(json.contains("todo18-offline-retry-transition-v3"))
        assertTrue(json.contains("\"retryObservations\""))
        assertTrue(json.contains("\"controllerGeneration\":7"))
        assertTrue(json.contains("\"resultIdentity\":99"))
    }

    @Test
    fun `complete receipt requires every retry boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            Todo18OfflineRetryTransitionReceipt(observations(), true)
                .requireComplete(OPERATION, OPERATION, REVISION)
        }
    }

    @Test
    fun `retry boundary missing duplicate and out of order observations are rejected`() {
        val valid = retryObservations()
        val missing = valid.dropLast(1)
        val duplicate = valid.dropLast(1) + valid[1]
        val reordered =
            valid.toMutableList().apply {
                val savedApply = this[5]
                this[5] = this[6]
                this[6] = savedApply
            }
        listOf(missing, duplicate, reordered).forEach { retry ->
            assertThrows(IllegalArgumentException::class.java) {
                receipt(retry).requireComplete(OPERATION, OPERATION, REVISION)
            }
        }
    }

    @Test
    fun `retry boundary malformed outcome revision token generation and result identity are rejected`() {
        val valid = retryObservations()
        val malformed =
            listOf(
                valid.mapIndexed { index, observation ->
                    if (index == 0) observation.copy(operationId = "wrong") else observation
                },
                valid.mapIndexed { index, observation ->
                    if (index == 5) observation.copy(revision = REVISION + 1) else observation
                },
                valid.mapIndexed { index, observation ->
                    if (index == 3) observation.copy(controllerGeneration = 8L) else observation
                },
                valid.mapIndexed { index, observation ->
                    if (index == 4) observation.copy(outcome = "exception") else observation
                },
                valid.mapIndexed { index, observation ->
                    if (index == 5) observation.copy(resultIdentity = 100) else observation
                },
            )
        malformed.forEach { retry ->
            assertThrows(IllegalArgumentException::class.java) {
                receipt(retry).requireComplete(OPERATION, OPERATION, REVISION)
            }
        }
    }

    @Test
    fun `retry boundary rejects a non returned terminal`() {
        val retry =
            retryObservations().map { observation ->
                if (observation.stage == MiniHomeRetryStage.COROUTINE_RETURNED) {
                    observation.copy(outcome = "exception")
                } else {
                    observation
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            receipt(retry).requireComplete(OPERATION, OPERATION, REVISION)
        }
    }

    private fun receipt(retry: List<Todo18OfflineRetryBoundaryObservation> = retryObservations()) =
        Todo18OfflineRetryTransitionReceipt(observations(), true, retryObservations = retry)

    private fun observations() =
        Todo18OfflineRetryTransitionStage.entries.map { stage ->
            Todo18OfflineRetryTransitionObservation(
                stage,
                OPERATION,
                REVISION.takeIf {
                    stage >= Todo18OfflineRetryTransitionStage.RAW_CONTROLLER_STATE
                },
            )
        }

    private fun retryObservations() =
        listOf(
            retryObservation(MiniHomeRetryStage.CALLBACK_ENTRY),
            retryObservation(MiniHomeRetryStage.COROUTINE_ENTRY),
            retryObservation(
                MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY,
                controllerEpoch = 3L,
                controllerGeneration = 7L,
                saveGeneration = 2L,
                guardDraftIdentity = true,
            ),
            retryObservation(
                MiniHomeRetryStage.REPOSITORY_SAVE_RETURNED,
                controllerEpoch = 3L,
                controllerGeneration = 7L,
                saveGeneration = 2L,
                guardDraftIdentity = true,
                revision = REVISION,
                outcome = "returned",
                resultIdentity = 99,
            ),
            retryObservation(
                MiniHomeRetryStage.SAVED_APPLY_ENTRY,
                controllerEpoch = 3L,
                controllerGeneration = 7L,
                saveGeneration = 2L,
                guardDraftIdentity = true,
                revision = REVISION,
                resultIdentity = 99,
            ),
            retryObservation(
                MiniHomeRetryStage.SET_STATE_ATTEMPTED,
                controllerEpoch = 3L,
                controllerGeneration = 7L,
                guardDraftIdentity = false,
            ),
            retryObservation(
                MiniHomeRetryStage.SET_STATE_APPLIED,
                controllerEpoch = 3L,
                controllerGeneration = 7L,
                guardDraftIdentity = false,
                revision = REVISION,
            ),
            retryObservation(
                MiniHomeRetryStage.RAW_STATE_PUBLICATION,
                revision = REVISION,
                guardDraftIdentity = false,
            ),
            retryObservation(
                MiniHomeRetryStage.COROUTINE_RETURNED,
                outcome = "returned",
            ),
        )

    private fun retryObservation(
        stage: MiniHomeRetryStage,
        controllerEpoch: Long? = null,
        controllerGeneration: Long? = null,
        saveGeneration: Long? = null,
        guardDraftIdentity: Boolean? = null,
        revision: Long? = null,
        outcome: String? = null,
        resultIdentity: Int? = null,
    ) =
        Todo18OfflineRetryBoundaryObservation(
            stage = stage,
            operationId = OPERATION,
            controllerEpoch = controllerEpoch,
            controllerGeneration = controllerGeneration,
            saveGeneration = saveGeneration,
            guardDraftIdentity = guardDraftIdentity,
            revision = revision,
            outcome = outcome,
            resultIdentity = resultIdentity,
            failureIdentity = null,
            failureClass = null,
            failureMessage = null,
        )

    private companion object {
        const val OPERATION = "offline-operation"
        const val REVISION = 2L
    }
}
