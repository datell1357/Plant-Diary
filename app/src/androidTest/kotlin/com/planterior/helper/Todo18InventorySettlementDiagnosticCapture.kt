package com.planterior.helper

import com.planterior.helper.inventory.Todo18InventorySettlementReceiptReducer
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class Todo18InventorySettlementDiagnosticCapture(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    fun <T> capture(block: () -> T): T = preserveTodo18PrimaryFailure(block, ::finish)

    private fun finish(primaryFailure: Throwable?): IllegalStateException? {
        val observations = runtime.inventorySettlementDiagnostics.snapshot()
        val problems = Todo18InventorySettlementReceiptReducer.problems(observations)
        val status = if (problems.isEmpty()) "complete" else problems.joinToString()
        return Todo18DiagnosticReceiptFinalizer(::receiptFile, "Todo18 Inventory settlement")
            .finish(primaryFailure != null, status) { file ->
                file.writeText(
                    buildJsonObject {
                        put("schema", "todo18-inventory-settlement-v1")
                        put("status", if (primaryFailure == null) status else "partial")
                        put("closed", true)
                        put(
                            "outcomeClass",
                            primaryFailure?.javaClass?.name?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put(
                            "outcomeMessage",
                            primaryFailure?.message?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        putJsonArray("problems") { problems.forEach(::add) }
                        putJsonArray("observations") {
                            observations.forEach { observation ->
                                add(
                                    buildJsonObject {
                                        put("stage", observation.stage.name)
                                        put("accountId", observation.settlement.accountId.value)
                                        put("itemId", observation.settlement.itemId.value)
                                        put("operationId", observation.settlement.operationId.value)
                                        put("loadKind", observation.loadKind)
                                        put("stale", observation.stale)
                                        put("eligible", observation.eligible)
                                        put("feedback", observation.feedback?.name)
                                        putJsonArray("ownedItemIds") {
                                            observation.ownedItemIds.orEmpty().forEach {
                                                add(it.value)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                        .toString()
                )
            }
    }

    private fun receiptFile(): File {
        val directory =
            requireNotNull(compose.activity.getExternalFilesDir("todo18-e2e-journeys")).also {
                check(it.exists() || it.mkdirs())
            }
        return File(directory, "inventory-settlement-diagnostic.json")
    }
}
