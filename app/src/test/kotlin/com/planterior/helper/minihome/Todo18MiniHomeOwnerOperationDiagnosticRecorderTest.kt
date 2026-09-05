package com.planterior.helper.minihome

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.minihome.MiniHomeOwnerOperationKind
import com.planterior.helper.feature.minihome.MiniHomeOwnerOperationObservation
import com.planterior.helper.feature.minihome.MiniHomeOwnerOperationStage
import com.planterior.helper.feature.minihome.MiniHomePublicationReadIdentity
import com.planterior.helper.feature.minihome.MiniHomePublicationTransactionObservation
import com.planterior.helper.feature.minihome.MiniHomePublicationTransactionStage
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18MiniHomeOwnerOperationDiagnosticRecorderTest {
    private val account = AccountId("owner")

    private fun owner(token: Long) =
        MiniHomeOwnerOperationObservation(
            MiniHomeOwnerOperationKind.SAVE,
            MiniHomeOwnerOperationStage.ENTERED,
            account,
            token,
        )

    @Test
    fun `save and transaction observations share order without a load context`() {
        val recorder = Todo18MiniHomeOwnerOperationDiagnosticRecorder()
        recorder.recordOwnerOperation(owner(7))
        recorder.recordPublicationTransaction(
            MiniHomePublicationTransactionObservation(
                MiniHomePublicationTransactionStage.BODY_ENTERED,
                account,
                MiniHomePublicationReadIdentity(9),
            )
        )
        val snapshot = recorder.snapshot()
        assertEquals(listOf(1L, 2L), snapshot.events.map { it.order })
        assertEquals(7L, snapshot.events[0].ownerOperation?.token)
        assertEquals(9L, snapshot.events[1].publicationTransaction?.readIdentity?.value)
        assertTrue(snapshot.events.all { it.thread.isNotEmpty() })
        assertEquals(0, snapshot.droppedEvents)
    }

    @Test
    fun `snapshot is bounded immutable and isolated between runtime instances`() {
        val recorder = Todo18MiniHomeOwnerOperationDiagnosticRecorder()
        recorder.recordOwnerOperation(owner(1))
        val previous = recorder.snapshot()
        repeat(Todo18MiniHomeOwnerOperationDiagnosticRecorder.MAX_EVENTS + 3) {
            recorder.recordOwnerOperation(owner(it.toLong() + 2))
        }
        assertEquals(1, previous.events.size)
        assertEquals(256, recorder.snapshot().events.size)
        assertEquals(4, recorder.snapshot().droppedEvents)
        assertTrue(Todo18MiniHomeOwnerOperationDiagnosticRecorder().snapshot().events.isEmpty())
    }

    @Test
    fun `json contains identity but no exception message`() {
        val recorder = Todo18MiniHomeOwnerOperationDiagnosticRecorder()
        recorder.recordOwnerOperation(
            owner(7).copy(failure = IllegalStateException("private-value"))
        )
        val json = buildJsonObject {
            putTodo18MiniHomeBoundaryDiagnosticEvents(recorder)
        }
            .toString()
        assertTrue(json.contains("\"token\":7"))
        assertTrue(json.contains("\"accountId\":\"owner\""))
        assertTrue(json.contains("java.lang.IllegalStateException"))
        assertFalse(json.contains("private-value"))
    }
}
