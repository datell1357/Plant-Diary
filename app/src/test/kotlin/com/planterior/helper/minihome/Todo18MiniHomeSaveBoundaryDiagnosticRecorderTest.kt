package com.planterior.helper.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.OperationId
import com.planterior.helper.feature.minihome.MiniHomeSaveBoundaryObservation
import com.planterior.helper.feature.minihome.MiniHomeSaveBoundaryOutcome
import com.planterior.helper.feature.minihome.MiniHomeSaveBoundaryStage
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18MiniHomeSaveBoundaryDiagnosticRecorderTest {
    private val account = AccountId("recorder-account")
    private val operation = OperationId("recorder-operation")

    @Test
    fun `recorder receives the emitted save-boundary sequence`() {
        val recorder = Todo18MiniHomeSaveBoundaryDiagnosticRecorder()
        emitWithoutInstallingRecorder(recorder)
        assertEquals(1, recorder.snapshot().size)
    }

    @Test
    fun `recorder preserves exact account and operation identity`() {
        val recorder = Todo18MiniHomeSaveBoundaryDiagnosticRecorder()
        emitWithoutInstallingRecorder(recorder)
        val observation = recorder.snapshot().single()
        assertEquals(account, observation.accountId)
        assertEquals(operation, observation.operationId)
    }

    @Test
    fun `recorder preserves stage order and cardinality`() {
        val recorder = Todo18MiniHomeSaveBoundaryDiagnosticRecorder()
        emitWithoutInstallingRecorder(recorder)
        assertEquals(
            listOf(MiniHomeSaveBoundaryStage.SAVE_SCOPE_ENTERED),
            recorder.snapshot().map { it.stage },
        )
    }

    @Test
    fun `recorder preserves cancellation identity`() {
        val recorder = Todo18MiniHomeSaveBoundaryDiagnosticRecorder()
        val cancellation = CancellationException("expected cancellation")
        emitWithoutInstallingRecorder(recorder, cancellation)
        assertTrue(recorder.snapshot().single().failure === cancellation)
    }

    @Test
    fun `recorder remains isolated from observer faults`() {
        val recorder = Todo18MiniHomeSaveBoundaryDiagnosticRecorder { error("writer fault") }
        emitWithoutInstallingRecorder(recorder)
        assertFalse(recorder.snapshot().isEmpty())
    }

    @Test
    fun `serialized recorder data contains only bounded fields`() {
        val recorder = Todo18MiniHomeSaveBoundaryDiagnosticRecorder()
        val failure =
            IllegalStateException(
                "https://example.invalid Bearer secret token=abc password=pw " +
                    "${account.value} payload-fragment"
            )
        emitWithoutInstallingRecorder(recorder, failure)
        val json = recorder.serializedSnapshot().single()
        assertEquals(
            setOf("sequence", "stage", "accountId", "operationId", "outcome", "failureClass"),
            Json.parseToJsonElement(json).jsonObject.keys,
        )
        listOf(
                "message",
                "identityHashCode",
                "https://",
                "Bearer",
                "token=",
                "password=",
                "payload-fragment",
            )
            .forEach { forbidden ->
                assertFalse("forbidden=$forbidden json=$json", json.contains(forbidden))
            }
        assertTrue(requireNotNull(recorder.snapshot().single().failureClass).length <= 256)
    }

    private fun emitWithoutInstallingRecorder(
        recorder: Todo18MiniHomeSaveBoundaryDiagnosticRecorder,
        failure: Throwable? = null,
    ) {
        recorder.observe(
            MiniHomeSaveBoundaryObservation(
                sequence = 1,
                stage = MiniHomeSaveBoundaryStage.SAVE_SCOPE_ENTERED,
                accountId = account,
                operationId = operation,
                outcome = MiniHomeSaveBoundaryOutcome.ENTERED,
                failureClass = failure?.javaClass?.name,
                failure = failure,
            )
        )
    }
}
