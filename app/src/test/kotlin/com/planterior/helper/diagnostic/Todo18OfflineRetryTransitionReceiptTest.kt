package com.planterior.helper.diagnostic

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

    private fun receipt() = Todo18OfflineRetryTransitionReceipt(observations(), true)

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

    private companion object {
        const val OPERATION = "offline-operation"
        const val REVISION = 2L
    }
}
