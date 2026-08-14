package com.planterior.helper.identify

import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.identify.IdentificationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugIdentificationGatewayTest {
    @Test
    fun `camera UUID deterministically reaches success fixture`() = runBlocking {
        val requestId = "550e8400-e29b-41d4-a716-446655440000"
        val gateway = checkNotNull(debugIdentificationGateway(requestId))

        val first =
            gateway.identify(IdentificationRequestId(requestId), OperationId("operation_12345678"))
        val second =
            gateway.identify(IdentificationRequestId(requestId), OperationId("operation_12345678"))

        assertTrue(first is IdentificationResult.Candidates)
        assertTrue(second is IdentificationResult.Candidates)
        assertTrue(first == second)
    }

    @Test
    fun `success fixture returns confidence-descending top three`() = runBlocking {
        // Given
        val gateway = checkNotNull(debugIdentificationGateway("fixture-success"))

        // When
        val result =
            gateway.identify(
                IdentificationRequestId("fixture-success"),
                OperationId("operation_12345678"),
            )

        // Then
        val candidates = (result as IdentificationResult.Candidates).candidates
        assertEquals(3, candidates.size)
        assertTrue(
            candidates.zipWithNext().all { (first, second) ->
                first.confidence >= second.confidence
            }
        )
    }
}
