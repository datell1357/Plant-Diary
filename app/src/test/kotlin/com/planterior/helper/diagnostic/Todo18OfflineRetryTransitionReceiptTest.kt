package com.planterior.helper.diagnostic

import org.junit.Assert.assertThrows
import org.junit.Test

class Todo18OfflineRetryTransitionReceiptTest {
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
