package com.planterior.helper.minihome

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniHomeSaveBoundarySourceContractTest {
    @Test
    fun `save boundary defines typed synchronous observation and finite serialization`() {
        val root = repositoryRoot()
        val diagnostic =
            source(
                root,
                "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/" +
                    "MiniHomeSaveBoundaryDiagnostics.kt",
            )

        assertCode(
            diagnostic,
            "data class MiniHomeSaveBoundaryObservation",
            "SAVE_SCOPE_ENTERED",
            "REMOTE_SAVE_RETURNED",
            "accountId",
            "operationId",
            "failureClass",
            "fun observe(observation: MiniHomeSaveBoundaryObservation)",
        )
        assertFalse(diagnostic.contains("failureMessage"))
        assertFalse(diagnostic.contains("Throwable.message"))
    }

    @Test
    fun `save boundary emits repository checkpoints in exact order`() {
        val root = repositoryRoot()
        val repository =
            source(
                root,
                "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/" +
                    "FirebaseMiniHomeRepository.kt",
            )

        assertOrdered(
            repository,
            "SAVE_SCOPE_ENTERED",
            "REMOTE_SAVE_ENTERED",
            "REMOTE_SAVE_RETURNED",
            "RECEIPT_RECORD_ENTERED",
            "RECEIPT_RECORD_RETURNED",
            "RECONCILE_APPLIED_ENTERED",
            "AUTHORITATIVE_LOAD_ENTERED",
            "AUTHORITATIVE_LOAD_RETURNED",
            "CACHE_ENTERED",
            "CACHE_RETURNED",
            "CONSUME_ENTERED",
            "CONSUME_RETURNED",
        )
        assertCode(repository, "SAVE_SCOPE_RETURNED")
    }

    @Test
    fun `save boundary preserves cancellation identity and observer fault isolation`() {
        val root = repositoryRoot()
        val diagnostic =
            source(
                root,
                "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/" +
                    "MiniHomeSaveBoundaryDiagnostics.kt",
            )
        val repository =
            source(
                root,
                "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/" +
                    "FirebaseMiniHomeRepository.kt",
            )

        assertCode(
            "$diagnostic\n$repository",
            "SAVE_SCOPE_CANCELLED",
            "REMOTE_SAVE_CANCELLED",
            "AUTHORITATIVE_LOAD_CANCELLED",
            "CACHE_CANCELLED",
            "CONSUME_CANCELLED",
            "failure === error",
            "throw error",
            "catch (",
            "observer",
            "swallow",
        )
    }

    @Test
    fun `save boundary captures supplemental retry diagnostics before the unchanged trigger`() {
        val root = repositoryRoot()
        val capture =
            source(
                root,
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18MiniHomeSaveBoundaryDiagnosticCapture.kt",
            )
        val journey =
            source(
                root,
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18MiniHomeJourneyAssertions.kt",
            )

        assertCode(
            capture,
            "Todo18MiniHomeSaveBoundaryDiagnosticCapture",
            "capture",
            "close",
            "REPOSITORY_SAVE_ENTRY",
            "trigger",
        )
        assertCode(
            journey,
            "Todo18MiniHomeSaveBoundaryDiagnosticCapture",
            "Todo18MiniHomeSaveBoundaryDiagnosticRecorder",
            "captureRetry",
            "MiniHomeTestTags.RETRY",
        )
        assertOrdered(
            journey.substringAfter("val saveBoundaryRecorder"),
            "Todo18MiniHomeSaveBoundaryDiagnosticCapture",
            "captureRetry",
            "MiniHomeTestTags.RETRY",
        )
    }

    @Test
    fun `save boundary keeps serialized data bounded and preserves existing retry receipt cardinality`() {
        val root = repositoryRoot()
        val recorder =
            source(
                root,
                "app/src/debug/kotlin/com/planterior/helper/minihome/" +
                    "Todo18MiniHomeSaveBoundaryDiagnosticRecorder.kt",
            )
        val appTest =
            source(
                root,
                "app/src/test/kotlin/com/planterior/helper/action/" +
                    "MiniHomeSaveActionEntryTraceTest.kt",
            )

        assertCode(
            "$recorder\n$appTest",
            "sequence",
            "stage",
            "accountId",
            "operationId",
            "outcome",
            "failureClass",
            "MiniHomeRetryStage.REPOSITORY_SAVE_ENTRY",
            "exactly 9",
        )
        assertFalse(recorder.contains("failureMessage"))
        assertFalse(recorder.contains("identityHashCode"))
    }

    private fun source(root: Path, relative: String): String =
        root.resolve(relative).takeIf(Files::exists)?.readText() ?: ""

    private fun assertCode(code: String, vararg tokens: String) {
        tokens.forEach { token ->
            assertTrue("Missing save-boundary source token: $token", code.contains(token))
        }
    }

    private fun assertOrdered(code: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val next = code.indexOf(token)
            assertTrue("Missing save-boundary source token: $token", next >= 0)
            assertTrue("Save-boundary token out of order: $token", next > previous)
            previous = next
        }
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}
