package com.planterior.helper.diagnostic

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18ExactEventObserverSourceContractTest {
    @Test
    fun `exact subscription exposes one safe per-instance diagnostic observer`() {
        val root = repositoryRoot()
        val exact =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/ExactEventSubscription.kt"
                )
                .readText()
        val probe =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/Todo18RenderedStateProbe.kt"
                )
                .readText()

        assertCode(
            exact,
            "diagnosticObserver: Todo18ExactEventObserver? = null",
            "Todo18ExactEventPhase.SUBSCRIBED",
            "Todo18ExactEventPhase.ARMED",
            "Todo18ExactEventPhase.TRIGGER_BEGIN",
            "Todo18ExactEventPhase.TRIGGER_RETURN",
            "Todo18ExactEventPhase.TRIGGER_FAILURE",
            "Todo18ExactEventPhase.EVENT_RECEIVED",
            "Todo18ExactEventPhase.PREDICATE_TRUE",
            "Todo18ExactEventPhase.PREDICATE_FALSE",
            "Todo18ExactEventPhase.EVENT_ACCEPTED",
            "Todo18ExactEventPhase.EVENT_REJECTED",
            "Todo18ExactEventPhase.AWAIT_SUCCESS",
            "Todo18ExactEventPhase.AWAIT_FAILURE",
            "Todo18ExactEventPhase.DETACH",
            "Todo18ExactEventPhase.DRAIN",
            "safeObserveExactEvent",
        )
        assertCode(probe, "diagnosticObserver = observer", "subscription.trigger(trigger)")
        assertFalse(probe.contains("ExactEventBehaviorTrace"))
        assertTrue(exact.contains("private val behavior = newBehaviorHandle"))
        assertFalse(exact.contains("catch (diagnosticFailure: Throwable)"))
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
