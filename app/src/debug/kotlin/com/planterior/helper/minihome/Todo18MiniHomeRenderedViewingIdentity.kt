package com.planterior.helper.minihome

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class Todo18MiniHomeRenderedState(
    val source: String,
    val state: String?,
    val owner: String?,
    val stateLoadId: Long?,
)

internal fun renderedViewingIdentityProblems(
    expectedAccountId: String,
    progress: Todo18MiniHomeLoadProgress,
    renderedStates: List<Todo18MiniHomeRenderedState>,
): List<String> {
    val terminalReadyLoads = progress.loads.filter { it.lastReachedStage == "terminal-ready" }
    if (terminalReadyLoads.size != 1) {
        return listOf("rendered-viewing-terminal-load-ambiguous")
    }
    val terminalLoadId = terminalReadyLoads.single().loadId.value
    return listOf("raw", "displayed").flatMap { source ->
        val viewings = renderedStates.filter { it.source == source && it.state == "Viewing" }
        when {
            viewings.isEmpty() -> listOf("rendered-viewing-$source-missing")
            viewings.any { it.stateLoadId == null } ->
                listOf("rendered-viewing-$source-load-identity-missing")
            viewings.any { it.stateLoadId != terminalLoadId } ->
                listOf("rendered-viewing-$source-load-identity-mismatch")
            viewings.any { it.owner != expectedAccountId } ->
                listOf("rendered-viewing-$source-account-mismatch")
            else -> emptyList()
        }
    }
}

internal fun JsonObjectBuilder.putTodo18MiniHomeRenderedState(
    renderedState: Todo18MiniHomeRenderedState
) {
    put("source", renderedState.source)
    put("state", renderedState.state)
    put("owner", renderedState.owner)
    put("stateLoadId", renderedState.stateLoadId)
}
