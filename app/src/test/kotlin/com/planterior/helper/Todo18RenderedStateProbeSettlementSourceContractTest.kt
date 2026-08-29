package com.planterior.helper

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18RenderedStateProbeSettlementSourceContractTest {
    private val root = repositoryRoot()

    @Test
    fun `MiniHome and Registration probes settle Compose between trigger and exact await`() {
        // Given
        val probe =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18RenderedStateProbe.kt"
                )
                .readText()
        val miniHome =
            probe.substringAfter("fun awaitMiniHome(").substringBefore("fun awaitRegistration(")
        val registration = probe.substringAfter("fun awaitRegistration(")

        // When / Then
        listOf(miniHome, registration).forEach { method ->
            assertOrdered(
                method,
                "subscription.arm()",
                "triggerSettleAndAwait(",
                "trigger = { subscription.trigger(trigger) }",
                "settle = compose::waitForIdle",
                "await = {",
                "subscription.await(",
            )
        }
        assertEquals(2, probe.countOccurrences("subscription.arm()"))
        assertEquals(2, probe.countOccurrences("triggerSettleAndAwait("))
        assertEquals(2, probe.countOccurrences("settle = compose::waitForIdle"))
        assertTrue(probe.contains("EVENT_TIMEOUT_MILLIS = 10_000L"))
        assertFalse(probe.contains("compose.waitForIdle()"))
    }

    @Test
    fun `AndroidTest orchestration invokes trigger settlement and await exactly once`() {
        // Given
        val orchestration =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18ProbeOrchestration.kt"
                )
                .readText()

        // When / Then
        assertOrdered(orchestration, "trigger()", "settle()", "return await()")
        assertEquals(1, orchestration.countOccurrences("trigger()"))
        assertEquals(1, orchestration.countOccurrences("settle()"))
        assertEquals(1, orchestration.countOccurrences("await()"))
        listOf("Thread.sleep", "delay(", "poll", "retry", "timeout").forEach {
            assertFalse("Forbidden orchestration token: $it", orchestration.contains(it))
        }
    }

    @Test
    fun `host test compiles the AndroidTest orchestration source by identity`() {
        // Given
        val androidTestSource =
            root.resolve(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18ProbeOrchestration.kt"
            )
        val hostSource =
            root.resolve("app/src/test/kotlin/com/planterior/helper/Todo18ProbeOrchestration.kt")

        // When / Then
        assertTrue(Files.isSymbolicLink(hostSource))
        assertTrue(Files.isSameFile(androidTestSource, hostSource))
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
