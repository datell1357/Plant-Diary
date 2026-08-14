package com.planterior.helper.identify

import com.planterior.helper.core.model.IdentificationRequestId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.identify.IdentificationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugIdentificationGatewayTest {
    @Test
    fun `camera UUID deterministically reaches success fixture`() = runBlocking {
        val requestId = "550e8400-e29b-41d4-a716-446655440000"
        val gateway = checkNotNull(debugIdentificationGateway(requestId))

        val first = gateway.identify(IdentificationRequestId(requestId), OperationId("operation_12345678"))
        val second = gateway.identify(IdentificationRequestId(requestId), OperationId("operation_12345678"))

        assertTrue(first is IdentificationResult.Candidates)
        assertTrue(second is IdentificationResult.Candidates)
        assertTrue(first == second)
    }
}
