package com.planterior.helper

import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.share.MiniHomeShareDiagnosticObservation
import com.planterior.helper.feature.share.MiniHomeShareDiagnosticStage
import com.planterior.helper.feature.share.MiniHomeShareUiState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18MiniHomeShareDiagnosticRecorderTest {
    @Test
    fun `receipt is ordered bounded and includes thread binding`() {
        val recorder = Todo18MiniHomeShareDiagnosticRecorder()
        repeat(257) { index ->
            recorder.record(
                MiniHomeShareDiagnosticObservation(
                    stage = MiniHomeShareDiagnosticStage.LOAD_ENTERED,
                    owner = AccountId("account"),
                    generation = index.toLong(),
                    stateKind = "loading",
                )
            )
        }

        val root = Json.parseToJsonElement(recorder.toJson()).jsonObject
        assertEquals(1, root["droppedCount"]?.toString()?.toInt())
        val events = root["events"]!!.jsonArray
        assertEquals(256, events.size)
        assertEquals("1", events.first().jsonObject["order"].toString())
        assertEquals("256", events.last().jsonObject["order"].toString())
        assertTrue(events.first().jsonObject.containsKey("thread"))
        assertEquals("\"load_entered\"", events.first().jsonObject["stage"].toString())
        assertEquals("0", events.first().jsonObject["generation"].toString())
    }

    @Test
    fun `display uses observed state without inventing a generation and instances are isolated`() {
        val recorder = Todo18MiniHomeShareDiagnosticRecorder()
        recorder.recordDisplayed(MiniHomeShareUiState.Loading(AccountId("observed-owner")))
        val event =
            Json.parseToJsonElement(recorder.toJson())
                .jsonObject["events"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals("\"Loading\"", event["stateKind"].toString())
        assertEquals("\"observed-owner\"", event["owner"].toString())
        assertEquals(JsonNull, event["generation"])
        val fresh =
            Json.parseToJsonElement(Todo18MiniHomeShareDiagnosticRecorder().toJson()).jsonObject
        assertEquals(0, fresh["events"]!!.jsonArray.size)
        assertEquals("0", fresh["droppedCount"].toString())
    }
}
