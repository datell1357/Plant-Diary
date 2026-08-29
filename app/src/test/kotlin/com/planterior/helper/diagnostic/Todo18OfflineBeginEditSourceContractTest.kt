package com.planterior.helper.diagnostic

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18OfflineBeginEditSourceContractTest {
    private val root = repositoryRoot()

    @Test
    fun `offline edit wait alone captures the exact begin-edit transition`() {
        val source =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18MiniHomeJourneyAssertions.kt"
                )
                .readText()
        val offline =
            source
                .substringAfter("assertOfflineMiniHomeReplayUsesPersistedOperation()")
                .substringBefore("assertMiniHomeConflictPreservesDraft()")
        val conflict = source.substringAfter("assertMiniHomeConflictPreservesDraft()")

        assertOrdered(
            offline,
            "Todo18WaitId.OFFLINE_INITIAL_VIEWING",
            "MiniHomeTestTags.EDIT).assertIsDisplayed()",
            "Todo18WaitId.OFFLINE_BEGIN_EDIT",
            ".run(",
            "matches = { it.state is MiniHomeUiState.Editing }",
            "trigger = { compose.onNodeWithTag(MiniHomeTestTags.EDIT).performClick() }",
            "observer = observer",
            "MiniHomeTestTags.SAVE).assertIsDisplayed()",
        )
        assertEquals(1, offline.countOccurrences("Todo18WaitId.OFFLINE_BEGIN_EDIT"))
        assertFalse(offline.contains("Todo18WaitId.CONFLICT_BEGIN_EDIT"))
        assertEquals(1, conflict.countOccurrences("Todo18WaitId.CONFLICT_BEGIN_EDIT"))
        assertFalse(conflict.contains("Todo18WaitId.OFFLINE_BEGIN_EDIT"))
    }

    @Test
    fun `exact transition capture finalizes after postcondition on every outcome`() {
        val capture =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18TransitionDiagnosticCapture.kt"
                )
                .readText()

        assertTrue(capture.contains("preserveTodo18PrimaryFailure"))
        assertOrdered(
            capture,
            "wait(observer)",
            "uiPostcondition()",
            "Todo18PipelineEventKind.UI_POSTCONDITION",
            "finish = { primary -> finalizeReceipt(primary) }",
        )
        assertOrdered(
            capture,
            "capture.close()",
            "val snapshot = capture.snapshot()",
            "val finalListenerCount = sink.primaryListenerCount()",
            ".finish(",
            "file.writeText(receipt.toCompactJson(snapshot))",
        )
    }

    private fun assertOrdered(code: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val position = code.indexOf(token, previous + 1)
            assertTrue("Missing ordered code token: $token", position >= 0)
            previous = position
        }
    }

    private fun String.countOccurrences(token: String): Int =
        windowed(token.length).count { it == token }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}
