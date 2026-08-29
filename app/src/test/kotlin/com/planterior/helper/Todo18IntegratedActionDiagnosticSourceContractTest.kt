package com.planterior.helper

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18IntegratedActionDiagnosticSourceContractTest {
    private val root = repositoryRoot()

    @Test
    fun `integrated rule installs both action sinks and closes them unconditionally`() {
        val rule = source("Todo18IntegratedRuntimeRule.kt")
        val source = source("Todo18IntegratedActionDiagnostics.kt")

        assertCode(rule, "actionDiagnostics.install()", "actionDiagnostics.close()")
        assertCode(
            source,
            "MiniHomeSaveActionDiagnostics.install(recorder::record)",
            "WateringConfirmActionDiagnostics.install(recorder::record)",
            "MiniHomeSaveActionDiagnostics.listenerCount() == 0",
            "WateringConfirmActionDiagnostics.listenerCount() == 0",
        )
    }

    @Test
    fun `three failing actions require exact semantics immediately before their clicks`() {
        val miniHome = source("Todo18MiniHomeJourneyAssertions.kt")
        val major = source("Todo18MajorJourneyAssertions.kt")

        assertTrue(
            "Both failing MiniHome clicks must inspect exact SAVE semantics",
            miniHome.split("assertMiniHomeSaveActionNode(").size - 1 == 2,
        )
        assertCode(major, "assertWateringConfirmActionNode(")
        assertCode(
            source("Todo18IntegratedActionSemantics.kt"),
            "assertCountEquals(1)",
            "assertIsDisplayed()",
            "assertIsEnabled()",
            "assert(hasClickAction())",
        )
    }

    @Test
    fun `all three actions unconditionally finalize source and APK bound ordered receipts`() {
        val miniHome = source("Todo18MiniHomeJourneyAssertions.kt")
        val major = source("Todo18MajorJourneyAssertions.kt")
        val capture =
            source("Todo18IntegratedActionDiagnosticCapture.kt") +
                debugSource("Todo18IntegratedActionReceipt.kt")

        assertCode(miniHome, "offline-mini-home-save", "conflict-mini-home-save")
        assertCode(major, "registration-watering-confirm")
        assertCode(
            capture,
            "preserveTodo18PrimaryFailure",
            "captureTodo18DiagnosticProvenance()",
            "expectedSourceSha256",
            "expectedAppApkSha256",
            "expectedAndroidTestApkSha256",
            "originalFailureClass",
            "originalFailureMessage",
            "captureClosed",
        )
    }

    @Test
    fun `host tests invoke the shared production action reducer without a copied reducer`() {
        val hostTest = hostSource("Todo18IntegratedActionReceiptReducerTest.kt")

        assertCode(hostTest, "Todo18IntegratedActionReducer.firstFailure(")
        assertTrue(
            "Host tests must not declare a copied action reducer",
            !hostTest.contains("object Todo18HostActionReceiptReducer"),
        )
    }

    @Test
    fun `action receipts retain exact semantic assertion facts`() {
        val receipt = debugSource("Todo18IntegratedActionReceipt.kt")

        assertCode(
            receipt,
            "nodeCount",
            "displayed",
            "enabled",
            "onClick",
        )
    }

    @Test
    fun `host tests exercise the integrated action finalization path for both throwable types`() {
        val hostTest = hostSource("Todo18IntegratedActionReceiptReducerTest.kt")

        assertCode(
            hostTest,
            "Todo18IntegratedActionReceiptFinalizer",
            "RuntimeException(\"runtime-primary\")",
            "AssertionError(\"assertion-primary\")",
            "receipt.readText()",
        )
    }

    @Test
    fun `production action reducer rejects provenance mismatch before complete`() {
        val receipt = debugSource("Todo18IntegratedActionReceipt.kt")

        assertCode(receipt, "bindingValidated", "provenance binding mismatch")
    }

    private fun source(name: String): String =
        root.resolve("app/src/androidTest/kotlin/com/planterior/helper/$name").readText()

    private fun debugSource(name: String): String =
        root.resolve("app/src/debug/kotlin/com/planterior/helper/$name").readText()

    private fun hostSource(name: String): String =
        root.resolve("app/src/test/kotlin/com/planterior/helper/$name").readText()

    private fun assertCode(code: String, vararg tokens: String) {
        tokens.forEach { assertTrue("Missing code token: $it", code.contains(it)) }
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}
