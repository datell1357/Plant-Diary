package com.planterior.helper.registration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18RegistrationCommitDiagnosticSourceContractTest {
    @Test
    fun `registration commit diagnostic defines every product boundary`() {
        // Given
        val root = repositoryRoot()
        val schema =
            root.resolve(
                "app/src/debug/kotlin/com/planterior/helper/diagnostic/Todo18DiagnosticSchema.kt"
            )
        val reducer =
            root.resolve(
                "app/src/debug/kotlin/com/planterior/helper/diagnostic/Todo18DiagnosticReducer.kt"
            )
        val capture =
            root.resolve(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18RegistrationCommitDiagnosticCapture.kt"
            )
        val repository =
            root.resolve(
                "app/src/debug/kotlin/com/planterior/helper/registration/" +
                    "Todo18RegistrationCommitDiagnosticRepository.kt"
            )
        val receiptJson =
            root.resolve(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18TransitionDiagnosticReceiptJson.kt"
            )

        // When / Then
        assertTrue(
            "Missing REGISTRATION_COMMIT schema",
            schema.readText().contains("REGISTRATION_COMMIT"),
        )
        assertTrue("Missing registration commit capture", Files.exists(capture))
        assertTrue("Missing registration commit repository hook", Files.exists(repository))
        val captureSource = capture.readText()
        val reducerSource = reducer.readText()
        val productPipeline =
            root
                .resolve(
                    "app/src/debug/kotlin/com/planterior/helper/diagnostic/" +
                        "Todo18ProductPipelineDiagnostic.kt"
                )
                .readText()
        val sink =
            root
                .resolve("app/src/debug/kotlin/com/planterior/helper/Todo18RenderedStateSink.kt")
                .readText()
        assertCode(
            "$captureSource\n$productPipeline\n$sink\n${repository.readText()}",
            "SUBMIT_CALLBACK",
            "REGISTRATION_CONTROLLER_ENTRY",
            "REGISTRATION_VALIDATION_ACCEPTED",
            "DUPLICATE_LOOKUP_BEGIN",
            "REGISTRATION_REPOSITORY_ENTRY",
            "REMOTE_COMMIT",
            "REGISTRATION_COMPLETED_PUBLICATION",
            "REGISTRATION_NAVIGATION_ENQUEUED",
            "REGISTRATION_NAVIGATION_DISPATCHED",
            "REGISTRATION_NAVIGATION_DESTINATION",
            "onRegistrationPersistenceDiagnostic",
            "REGISTRATION_COMMITTED_READ_ENTERED",
            "REGISTRATION_CACHE_UPSERT_ENTERED",
            "REGISTRATION_OUTBOX_REMOVE_ENTERED",
            "REGISTRATION_COMPLETED_RETURNED",
            "detach",
            "drain",
        )
        assertCode(
            reducerSource,
            "REGISTRATION_COMMIT",
            "COMMIT_SUBMIT_CALLBACK_MISSED",
            "COMMIT_VALIDATION_REJECTED",
            "COMMIT_REMOTE_COMMIT_MISSED",
            "COMMIT_NAVIGATION_DESTINATION_MISSED",
        )
        assertCode(
            schema.readText(),
            "registrationAccountId: AccountId? = null",
            "elapsedNanos: Long? = null",
            "REGISTRATION_COMMITTED_READ_ENTERED",
            "REGISTRATION_COMPLETED_RETURNED",
        )
        assertCode(
            receiptJson.readText(),
            "registrationAccountId?.value.jsonValue()",
            "elapsedNanos.jsonValue()",
        )
    }

    private fun assertCode(code: String, vararg tokens: String) {
        tokens.forEach { token -> assertTrue("Missing code token: $token", code.contains(token)) }
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}
