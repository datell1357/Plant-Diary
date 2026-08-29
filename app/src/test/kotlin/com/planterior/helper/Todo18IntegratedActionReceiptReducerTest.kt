package com.planterior.helper

import com.planterior.helper.diagnostic.Todo18DiagnosticProvenance
import com.planterior.helper.feature.minihome.MiniHomeSaveActionDiagnostics
import com.planterior.helper.feature.minihome.MiniHomeSaveActionObservation
import com.planterior.helper.feature.minihome.MiniHomeSaveActionStage
import com.planterior.helper.feature.watering.WateringConfirmActionDiagnostics
import com.planterior.helper.feature.watering.WateringConfirmActionObservation
import com.planterior.helper.feature.watering.WateringConfirmActionStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Todo18IntegratedActionReceiptReducerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `valid MiniHome and watering receipts reduce cleanly`() {
        assertNull(Todo18IntegratedActionReducer.firstFailure(valid(MINI_HOME_STAGES)))
        assertNull(
            Todo18IntegratedActionReducer.firstFailure(valid(WATERING_STAGES, watering = true))
        )
    }

    @Test
    fun `reducer identifies first missing out of order and duplicate stage`() {
        assertEquals(
            "missing CONTROLLER_ENTRY",
            Todo18IntegratedActionReducer.firstFailure(
                valid(MINI_HOME_STAGES)
                    .copy(observations = observations(MINI_HOME_STAGES - "CONTROLLER_ENTRY"))
            ),
        )
        assertEquals(
            "missing SCREEN_CALLBACK",
            Todo18IntegratedActionReducer.firstFailure(
                valid(MINI_HOME_STAGES)
                    .copy(
                        observations =
                            observations(
                                MINI_HOME_STAGES.toMutableList().apply {
                                    val stage = this[4]
                                    this[4] = this[5]
                                    this[5] = stage
                                }
                            )
                    )
            ),
        )
        assertEquals(
            "out-of-order or duplicate SAVE_NODE_COUNT",
            Todo18IntegratedActionReducer.firstFailure(
                valid(MINI_HOME_STAGES)
                    .copy(observations = observations(listOf("SAVE_NODE_COUNT") + MINI_HOME_STAGES))
            ),
        )
    }

    @Test
    fun `reducer rejects identity semantic unclosed and malformed captures`() {
        val validMiniHome = valid(MINI_HOME_STAGES)
        assertEquals(
            "operation identity mismatch",
            Todo18IntegratedActionReducer.firstFailure(
                validMiniHome.copy(
                    observations =
                        validMiniHome.observations.mapIndexed { index, observation ->
                            if (index == 1) observation.copy(operationId = "operation-b")
                            else observation
                        }
                )
            ),
        )
        val validWatering = valid(WATERING_STAGES, watering = true)
        assertEquals(
            "plant identity mismatch",
            Todo18IntegratedActionReducer.firstFailure(
                validWatering.copy(
                    observations =
                        validWatering.observations.mapIndexed { index, observation ->
                            if (index == 1) observation.copy(plantId = "plant-b") else observation
                        }
                )
            ),
        )
        assertEquals(
            "semantic displayed mismatch",
            Todo18IntegratedActionReducer.firstFailure(
                validMiniHome.copy(semanticFacts = VALID_SEMANTICS.copy(displayed = false))
            ),
        )
        assertEquals(
            "unclosed capture",
            Todo18IntegratedActionReducer.firstFailure(validMiniHome.copy(captureClosed = false)),
        )
        assertEquals(
            "malformed scenario",
            Todo18IntegratedActionReducer.firstFailure(validMiniHome.copy(scenario = "")),
        )
    }

    @Test
    fun `integrated finalization preserves both primary throwable types and writes receipt facts`() {
        listOf(RuntimeException("runtime-primary"), AssertionError("assertion-primary"))
            .forEachIndexed { index, primary ->
                val receipt = temporaryFolder.newFile("integrated-action-primary-$index.json")
                val finalizer =
                    Todo18IntegratedActionReceiptFinalizer(
                        receiptFile = { receipt },
                        diagnosticName = "integrated-action-test",
                        provenance = VALID_PROVENANCE,
                    )
                val actual =
                    assertThrows(primary.javaClass) {
                        preserveTodo18PrimaryFailure(
                            block = { throw primary },
                            finish = { failure ->
                                finalizer.finish(valid(MINI_HOME_STAGES), failure)
                            },
                        )
                    }
                assertSame(primary, actual)
                val json = receipt.readText()
                assertEquals(
                    true,
                    json.contains("\"originalFailureClass\":\"${primary.javaClass.name}\""),
                )
                assertEquals(
                    true,
                    json.contains("\"originalFailureMessage\":\"${primary.message}\""),
                )
                assertEquals(true, json.contains("\"finalizationState\":\"complete\""))
                assertEquals(true, json.contains("\"nodeCount\":1"))
                assertEquals(true, json.contains("\"displayed\":true"))
                assertEquals(true, json.contains("\"enabled\":true"))
                assertEquals(true, json.contains("\"onClick\":true"))
            }
    }

    @Test
    fun `provenance mismatch is rejected by production validation and cannot finalize complete`() {
        val receipt = temporaryFolder.newFile("integrated-action-binding-mismatch.json")
        val snapshot = valid(MINI_HOME_STAGES).copy(bindingValidated = false)
        val finalizer =
            Todo18IntegratedActionReceiptFinalizer(
                receiptFile = { receipt },
                diagnosticName = "integrated-action-test",
                provenance = MISMATCHED_PROVENANCE,
            )

        assertEquals(
            "provenance binding mismatch",
            Todo18IntegratedActionReducer.firstFailure(snapshot),
        )
        val failure =
            assertThrows(IllegalStateException::class.java) {
                preserveTodo18PrimaryFailure(
                    block = {},
                    finish = { primary -> finalizer.finish(snapshot, primary) },
                )
            }
        val json = receipt.readText()
        assertEquals(true, json.contains("\"bindingValidated\":false"))
        assertEquals(true, json.contains("\"reductionFailure\":\"provenance binding mismatch\""))
        assertEquals(true, json.contains("\"finalizationState\":\"invalid-action-capture\""))
        assertEquals(true, failure.message!!.contains("status=invalid-action-capture"))
    }

    @Test
    fun `both action diagnostics have one listener only while installed`() {
        assertEquals(0, MiniHomeSaveActionDiagnostics.listenerCount())
        assertEquals(0, WateringConfirmActionDiagnostics.listenerCount())
        val miniHome = MiniHomeSaveActionDiagnostics.install {}
        val watering = WateringConfirmActionDiagnostics.install {}
        try {
            assertEquals(1, MiniHomeSaveActionDiagnostics.listenerCount())
            assertEquals(1, WateringConfirmActionDiagnostics.listenerCount())
        } finally {
            watering.close()
            miniHome.close()
        }
        assertEquals(0, MiniHomeSaveActionDiagnostics.listenerCount())
        assertEquals(0, WateringConfirmActionDiagnostics.listenerCount())
    }

    @Test
    fun `single installation rejects duplicates and isolates both throwable categories`() {
        val miniHome = MiniHomeSaveActionDiagnostics.install { throw RuntimeException("ignored") }
        try {
            assertThrows(IllegalStateException::class.java) {
                MiniHomeSaveActionDiagnostics.install {}
            }
            assertNull(
                runCatching {
                    MiniHomeSaveActionDiagnostics.observe(
                        MiniHomeSaveActionObservation(MiniHomeSaveActionStage.SCREEN_CALLBACK)
                    )
                }
                    .exceptionOrNull()
            )
        } finally {
            miniHome.close()
        }
        val watering = WateringConfirmActionDiagnostics.install { throw AssertionError("ignored") }
        try {
            assertThrows(IllegalStateException::class.java) {
                WateringConfirmActionDiagnostics.install {}
            }
            assertNull(
                runCatching {
                    WateringConfirmActionDiagnostics.observe(
                        WateringConfirmActionObservation(WateringConfirmActionStage.SCREEN_CALLBACK)
                    )
                }
                    .exceptionOrNull()
            )
        } finally {
            watering.close()
        }
        assertEquals(0, MiniHomeSaveActionDiagnostics.listenerCount())
        assertEquals(0, WateringConfirmActionDiagnostics.listenerCount())
    }

    private fun valid(
        stages: List<String>,
        watering: Boolean = false,
    ) =
        Todo18IntegratedActionSnapshot(
            scenario = "scenario",
            kind =
                if (watering) Todo18IntegratedActionKind.WATERING_CONFIRM
                else Todo18IntegratedActionKind.MINI_HOME_SAVE,
            observations = observations(stages, watering),
            semanticFacts = VALID_SEMANTICS,
            bindingValidated = true,
            boundaryDelivered = true,
            initialListenerCount = 2,
            finalListenerCount = 2,
            captureClosed = true,
        )

    private fun observations(
        stages: List<String>,
        watering: Boolean = false,
    ) = stages.mapIndexed { index, stage ->
        Todo18IntegratedActionObservation(
            ordinal = index + 1,
            stage = stage,
            operationId = "operation-a",
            plantId = if (watering) "plant-a" else null,
        )
    }

    private companion object {
        val MINI_HOME_STAGES = MiniHomeSaveActionStage.entries.map(Enum<*>::name)
        val WATERING_STAGES = WateringConfirmActionStage.entries.map(Enum<*>::name)
        val VALID_SEMANTICS =
            Todo18IntegratedSemanticFacts(
                nodeCount = 1,
                displayed = true,
                enabled = true,
                onClick = true,
            )
        const val SOURCE_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val APP_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val TEST_HASH = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        val VALID_PROVENANCE =
            Todo18DiagnosticProvenance(
                expectedSourceSha256 = SOURCE_HASH,
                embeddedSourceSha256 = SOURCE_HASH,
                expectedAppApkSha256 = APP_HASH,
                observedAppApkSha256 = APP_HASH,
                expectedAndroidTestApkSha256 = TEST_HASH,
                observedAndroidTestApkSha256 = TEST_HASH,
            )
        val MISMATCHED_PROVENANCE = VALID_PROVENANCE.copy(observedAndroidTestApkSha256 = APP_HASH)
    }
}
