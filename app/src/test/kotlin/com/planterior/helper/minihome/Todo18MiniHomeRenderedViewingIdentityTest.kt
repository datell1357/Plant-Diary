package com.planterior.helper.minihome

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Todo18MiniHomeRenderedViewingIdentityTest {
    @Test
    fun uniqueTerminalReadyLoadAcceptsRawAndDisplayedViewingIdentity() {
        // Given
        val progress = terminalReadyProgress(LOAD_ID)

        // When
        val problems = renderedViewingIdentityProblems(ACCOUNT_ID, progress, validStates())

        // Then
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun missingLoadIdentityIsRejected() {
        // Given
        val states =
            validStates().map { state ->
                if (state.source == "raw") state.copy(stateLoadId = null) else state
            }

        // When / Then
        assertEquals(
            listOf("rendered-viewing-raw-load-identity-missing"),
            renderedViewingIdentityProblems(ACCOUNT_ID, terminalReadyProgress(LOAD_ID), states),
        )
    }

    @Test
    fun staleLoadIdentityIsRejected() {
        // Given
        val states =
            validStates().map { state ->
                if (state.source == "raw") state.copy(stateLoadId = LOAD_ID - 1L) else state
            }

        // When / Then
        assertEquals(
            listOf("rendered-viewing-raw-load-identity-mismatch"),
            renderedViewingIdentityProblems(ACCOUNT_ID, terminalReadyProgress(LOAD_ID), states),
        )
    }

    @Test
    fun wrongAccountIsRejected() {
        // Given
        val states =
            validStates().map { state ->
                if (state.source == "displayed") state.copy(owner = "account-b") else state
            }

        // When / Then
        assertEquals(
            listOf("rendered-viewing-displayed-account-mismatch"),
            renderedViewingIdentityProblems(ACCOUNT_ID, terminalReadyProgress(LOAD_ID), states),
        )
    }

    @Test
    fun noTerminalReadyLoadIsRejected() {
        // Given / When / Then
        assertEquals(
            listOf("rendered-viewing-terminal-load-ambiguous"),
            renderedViewingIdentityProblems(ACCOUNT_ID, terminalReadyProgress(), validStates()),
        )
    }

    @Test
    fun multipleTerminalReadyLoadsAreRejected() {
        // Given / When / Then
        assertEquals(
            listOf("rendered-viewing-terminal-load-ambiguous"),
            renderedViewingIdentityProblems(
                ACCOUNT_ID,
                terminalReadyProgress(LOAD_ID, LOAD_ID + 1L),
                validStates(),
            ),
        )
    }

    @Test
    fun missingRawAndDisplayedViewingAreRejected() {
        // Given / When / Then
        assertEquals(
            listOf("rendered-viewing-raw-missing", "rendered-viewing-displayed-missing"),
            renderedViewingIdentityProblems(
                ACCOUNT_ID,
                terminalReadyProgress(LOAD_ID),
                emptyList(),
            ),
        )
    }

    @Test
    fun stateLoadIdIsSerializedAsANumber() {
        // Given
        val state = validStates().first()

        // When
        val json = buildJsonObject { putTodo18MiniHomeRenderedState(state) }

        // Then
        assertEquals("raw", json.getValue("source").jsonPrimitive.content)
        assertEquals("Viewing", json.getValue("state").jsonPrimitive.content)
        assertEquals(ACCOUNT_ID, json.getValue("owner").jsonPrimitive.content)
        assertEquals(LOAD_ID, json.getValue("stateLoadId").jsonPrimitive.long)
        assertFalse(json.getValue("stateLoadId").jsonPrimitive.isString)
    }

    private fun validStates() =
        listOf(
            Todo18MiniHomeRenderedState("raw", "Viewing", ACCOUNT_ID, LOAD_ID),
            Todo18MiniHomeRenderedState("displayed", "Viewing", ACCOUNT_ID, LOAD_ID),
        )

    private fun terminalReadyProgress(vararg loadIds: Long) =
        Todo18MiniHomeLoadProgress(
            activeStage = null,
            lastReachedStage = loadIds.singleOrNull()?.let { "terminal-ready" },
            reachedStages = emptyList(),
            recorderFailures = emptyList(),
            loads =
                loadIds.map { loadId ->
                    Todo18MiniHomePerLoadProgress(
                        loadId = Todo18MiniHomeLoadId(loadId),
                        activeStage = null,
                        lastReachedStage = "terminal-ready",
                        reachedStages = listOf("load-entered", "terminal-ready"),
                        publicationReadIds = emptyList(),
                    )
                },
        )

    private companion object {
        const val ACCOUNT_ID = "account-a"
        const val LOAD_ID = 7L
    }
}
