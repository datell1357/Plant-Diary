package com.planterior.helper

import com.planterior.helper.diagnostic.Todo18RoomTransactionOwnerClassification
import com.planterior.helper.minihome.Todo18MiniHomeLoadProgress
import com.planterior.helper.minihome.putTodo18MiniHomeLoadProgress
import com.planterior.helper.minihome.putTodo18MiniHomeRenderedState
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal data class Todo18MiniHomeInitialLoadReceipt(
    val scenarioName: String = "mini-home-conflict-initial-load",
    val api: Int,
    val expectedAccountId: String,
    val timeline: List<TimelineEntry>,
    val progress: Todo18MiniHomeLoadProgress,
    val primaryFailure: Throwable?,
    val problems: List<String>,
    val transactionOwner: Todo18RoomTransactionOwnerClassification,
)

internal fun writeTodo18MiniHomeInitialLoadReceipt(
    file: File,
    input: Todo18MiniHomeInitialLoadReceipt,
) {
    val entered = input.timeline.filter { it.kind == "load-entered" }
    val terminals = input.timeline.filter { it.kind == "load-terminal" }
    val receipt = buildJsonObject {
        put("schema", "todo18-mini-home-load-diagnostic-v5")
        put("scenario", input.scenarioName)
        put("api", input.api)
        put("expectedAccountId", input.expectedAccountId)
        put("status", if (input.problems.isEmpty()) "complete" else "failed")
        putTodo18MiniHomeLoadProgress(input.progress)
        putJsonObject("loadEntered") {
            put("count", entered.size)
            putJsonArray("identities") { entered.forEach { add(it.identity) } }
            putJsonArray("loadIds") { entered.forEach { add(it.loadId) } }
        }
        putJsonObject("loadTerminal") {
            put("count", terminals.size)
            putJsonArray("identities") { terminals.forEach { add(it.identity) } }
        }
        putJsonArray("diagnosticFailures") { input.problems.forEach(::add) }
        put(
            "sharedRoomTransactionOwner",
            when (val classification = input.transactionOwner) {
                is Todo18RoomTransactionOwnerClassification.Exact ->
                    buildJsonObject {
                        put("classification", classification.owner.name)
                        put("token", classification.token)
                    }
                Todo18RoomTransactionOwnerClassification.Unknown ->
                    buildJsonObject { put("classification", "UNKNOWN") }
            },
        )
        put(
            "journeyFailure",
            input.primaryFailure?.let { failure ->
                buildJsonObject {
                    put("class", failure.javaClass.name)
                    put("message", failure.message)
                }
            } ?: JsonNull,
        )
        putJsonArray("timeline") {
            input.timeline.forEach { entry ->
                add(
                    buildJsonObject {
                        put("order", entry.order)
                        put("source", entry.source)
                        put("kind", entry.kind)
                        put("identity", entry.identity)
                        put("loadId", entry.loadId)
                        put("readId", entry.readId)
                        put("diagnosticOrder", entry.diagnosticOrder)
                        put("cacheOutcome", entry.cacheOutcome)
                        put("pendingReadLoadId", entry.pendingReadLoadId)
                        put("pendingReadId", entry.pendingReadId)
                        put("pendingReadOutcome", entry.pendingReadOutcome)
                        put("pendingReadFailureClass", entry.pendingReadFailureClass)
                        put("pendingReadFailureMessage", entry.pendingReadFailureMessage)
                        put("operationId", entry.operationId)
                        put("cacheTransactionResult", entry.cacheTransactionResult)
                        put("cacheTransactionFailureClass", entry.cacheTransactionFailureClass)
                        put("cacheTransactionFailureMessage", entry.cacheTransactionFailureMessage)
                        put("publicationReadTerminalOutcome", entry.publicationReadTerminalOutcome)
                        put(
                            "publicationReadTerminalFailureClass",
                            entry.publicationReadTerminalFailureClass,
                        )
                        put(
                            "publicationReadTerminalFailureMessage",
                            entry.publicationReadTerminalFailureMessage,
                        )
                        put("sinkSequence", entry.sinkSequence)
                        putTodo18MiniHomeRenderedState(entry.renderedState())
                        put("transactionOwner", entry.transactionOwner)
                        put("transactionToken", entry.transactionToken)
                        put("transactionFailureClass", entry.transactionFailureClass)
                        put("transactionFailureMessage", entry.transactionFailureMessage)
                    }
                )
            }
        }
    }
    file.writeText(receipt.toString())
}
