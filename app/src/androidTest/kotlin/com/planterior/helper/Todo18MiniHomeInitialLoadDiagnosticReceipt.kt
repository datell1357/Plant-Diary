package com.planterior.helper

import com.planterior.helper.minihome.Todo18MiniHomeLoadProgress
import com.planterior.helper.minihome.putTodo18MiniHomeLoadProgress
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal data class Todo18MiniHomeInitialLoadReceipt(
    val api: Int,
    val expectedAccountId: String,
    val timeline: List<TimelineEntry>,
    val progress: Todo18MiniHomeLoadProgress,
    val primaryFailure: Throwable?,
    val problems: List<String>,
)

internal fun writeTodo18MiniHomeInitialLoadReceipt(
    file: File,
    input: Todo18MiniHomeInitialLoadReceipt,
) {
    val entered = input.timeline.filter { it.kind == "load-entered" }
    val terminals = input.timeline.filter { it.kind == "load-terminal" }
    val receipt = buildJsonObject {
        put("schema", "todo18-mini-home-load-diagnostic-v4")
        put("scenario", "mini-home-conflict-initial-load")
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
                        put("sinkSequence", entry.sinkSequence)
                        put("state", entry.state)
                        put("owner", entry.owner)
                    }
                )
            }
        }
    }
    file.writeText(receipt.toString())
}
