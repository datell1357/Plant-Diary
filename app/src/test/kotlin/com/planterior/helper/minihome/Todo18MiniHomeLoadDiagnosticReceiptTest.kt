package com.planterior.helper.minihome

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18MiniHomeLoadDiagnosticReceiptTest {
    @Test
    fun `unfinished receipt serializes its active and last reached stage`() {
        // Given
        val progress =
            Todo18MiniHomeLoadProgress(
                activeStage = "remote-load-entered",
                lastReachedStage = "remote-load-entered",
                reachedStages = listOf("load-entered", "remote-load-entered"),
                recorderFailures = emptyList(),
            )

        // When
        val receipt = buildJsonObject { putTodo18MiniHomeLoadProgress(progress) }

        // Then
        assertTrue(receipt.getValue("valid").jsonPrimitive.boolean)
        assertEquals("remote-load-entered", receipt.getValue("activeStage").jsonPrimitive.content)
        assertEquals(
            "remote-load-entered",
            receipt.getValue("lastReachedStage").jsonPrimitive.content,
        )
        assertEquals(
            listOf("load-entered", "remote-load-entered"),
            receipt.getValue("reachedStages").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `invalid receipt serializes a machine decidable progression violation`() {
        // Given
        val progress =
            Todo18MiniHomeLoadProgress(
                activeStage = "publication-read-entered",
                lastReachedStage = "publication-read-entered",
                reachedStages =
                    listOf(
                        "load-entered",
                        "remote-load-entered",
                        "publication-read-entered",
                    ),
                recorderFailures = emptyList(),
                progressionViolations =
                    listOf(
                        Todo18MiniHomeLoadProgressionViolation(
                            kind = Todo18MiniHomeLoadViolationKind.OUT_OF_ORDER_STAGE,
                            loadId = Todo18MiniHomeLoadId(7L),
                            readId = Todo18MiniHomePublicationReadId(Todo18MiniHomeLoadId(7L), 1L),
                            observedStage = "publication-read-entered",
                            previousStage = "remote-load-entered",
                        )
                    ),
            )

        // When
        val receipt = buildJsonObject { putTodo18MiniHomeLoadProgress(progress) }

        // Then
        assertFalse(receipt.getValue("valid").jsonPrimitive.boolean)
        val violation = receipt.getValue("progressionViolations").jsonArray.single().jsonObject
        assertEquals("out-of-order-stage", violation.getValue("kind").jsonPrimitive.content)
        assertEquals(
            "publication-read-entered",
            violation.getValue("observedStage").jsonPrimitive.content,
        )
        assertEquals(
            "remote-load-entered",
            violation.getValue("previousStage").jsonPrimitive.content,
        )
        assertEquals(7L, violation.getValue("loadId").jsonPrimitive.content.toLong())
        assertEquals(1L, violation.getValue("readId").jsonPrimitive.content.toLong())
    }

    @Test
    fun `receipt serializes ordered load and publication read identities`() {
        // Given
        val recorder = Todo18MiniHomeLoadDiagnosticRecorder {}
        val load = recorder.startLoad()
        load.record(Todo18MiniHomeLoadDiagnostic.LoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)
        load.record(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)
        load.recordPublicationRead()
        load.recordPublicationRead()

        // When
        val receipt = buildJsonObject { putTodo18MiniHomeLoadProgress(recorder.snapshot()) }

        // Then
        val observations = receipt.getValue("observations").jsonArray.map { it.jsonObject }
        assertEquals(
            (1L..5L).toList(),
            observations.map { it.getValue("order").jsonPrimitive.content.toLong() },
        )
        assertEquals(
            listOf(1L, 2L),
            observations.mapNotNull { observation ->
                observation.getValue("readId").jsonPrimitive.content.toLongOrNull()
            },
        )
        val serializedLoad = receipt.getValue("loads").jsonArray.single().jsonObject
        assertEquals(
            load.id.value,
            serializedLoad.getValue("loadId").jsonPrimitive.content.toLong(),
        )
        assertEquals(
            listOf(1L, 2L),
            serializedLoad.getValue("publicationReadIds").jsonArray.map {
                it.jsonPrimitive.content.toLong()
            },
        )
    }

    @Test
    fun `terminal receipt clears active stage and retains terminal as last reached`() {
        // Given
        val progress =
            Todo18MiniHomeLoadProgress(
                activeStage = null,
                lastReachedStage = "terminal-cancelled",
                reachedStages = listOf("load-entered", "terminal-cancelled"),
                recorderFailures = emptyList(),
            )

        // When
        val receipt = buildJsonObject { putTodo18MiniHomeLoadProgress(progress) }

        // Then
        assertEquals(JsonNull, receipt.getValue("activeStage"))
        assertEquals(
            "terminal-cancelled",
            receipt.getValue("lastReachedStage").jsonPrimitive.content,
        )
    }
}
