package com.planterior.helper

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18RoomTransactionOwnerSourceContractTest {
    private val root = repositoryRoot()

    @Test
    fun `shared Room write submissions expose typed owner diagnostics`() {
        val accountSync =
            source(
                "feature/auth/src/main/kotlin/com/planterior/helper/feature/auth/" +
                    "AccountSyncAdapters.kt"
            )
        val analytics =
            listOf("AnalyticsRuntime.kt", "AnalyticsRecorder.kt", "AnalyticsWorker.kt")
                .joinToString("\n") {
                    source("app/src/main/kotlin/com/planterior/helper/analytics/$it")
                }

        assertCode(accountSync, "RoomTransactionOwnerDiagnostics", "ACCOUNT_SYNC_WRITE")
        assertCode(
            analytics,
            "ANALYTICS_ENQUEUE",
            "ANALYTICS_CONSENT_PURGE",
            "ANALYTICS_WORKER_DELIVERY",
        )
    }

    @Test
    fun `debug runtime attaches the process proxy before MainActivity and detaches it`() {
        val debug =
            source(
                "app/src/debug/kotlin/com/planterior/helper/diagnostic/" +
                    "Todo18RoomTransactionOwnerProxy.kt"
            )
        val release =
            source(
                "app/src/release/kotlin/com/planterior/helper/diagnostic/" +
                    "Todo18RoomTransactionOwnerProxy.kt"
            )
        val runtime =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18IntegratedRuntimeRule.kt"
            )

        assertCode(debug, "AtomicReference", "attach", "compareAndSet")
        assertCode(release, "RoomTransactionOwnerDiagnostics()")
        assertCode(runtime, "attachTodo18RoomTransactionOwnerListener", "close()")
    }

    private fun source(relative: String): String = root.resolve(relative).readText()

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
