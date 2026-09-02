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
    fun `rendered probes settle Compose between trigger and exact await`() {
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
        val registration =
            probe
                .substringAfter("fun awaitRegistration(")
                .substringBefore("fun awaitInventoryFeedback(")
        val inventory = probe.substringAfter("fun awaitInventoryFeedback(")

        // When / Then
        listOf(miniHome, registration, inventory).forEach { method ->
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
        assertEquals(5, probe.countOccurrences("subscription.arm()"))
        assertEquals(3, probe.countOccurrences("triggerSettleAndAwait("))
        assertEquals(3, probe.countOccurrences("settle = compose::waitForIdle"))
        assertTrue(miniHome.contains("event.sequence > floor"))
        assertFalse(miniHome.contains("event.sequence >= floor"))
        assertTrue(probe.contains("EVENT_TIMEOUT_MILLIS = 10_000L"))
        assertFalse(probe.contains("compose.waitForIdle()"))
    }

    @Test
    fun `share Ready wait uses a pre-navigation floor and exact event predicate`() {
        val probe =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18RenderedStateProbe.kt"
                )
                .readText()
        assertTrue(probe.contains("fun awaitMiniHomeShareReady("))
        val share =
            probe
                .substringAfter("fun awaitMiniHomeShareReady(")
                .substringBefore("fun awaitRegistration(")

        assertTrue(share.contains("sink::subscribeToMiniHomeShareStates"))
        assertTrue(share.contains("sink::currentMiniHomeShareState"))
        assertTrue(share.contains("acceptRegistrationReplay = true"))
        assertTrue(share.contains("event.sequence > floor"))
        assertFalse(share.contains("event.sequence >= floor"))
        assertTrue(share.contains("MiniHomeShareUiState.Ready"))
        assertTrue(share.contains("MiniHomeShareLinkState.Idle"))
        assertOrdered(share, "subscription.arm()", "subscription.await(")
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
    fun `MiniHome edit waits for the exact post-load displayed barrier`() {
        val probe =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/Todo18JourneyEventProbe.kt"
                )
                .readText()
        val renderedProbe =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/Todo18RenderedStateProbe.kt"
                )
                .readText()
        val journey =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18MajorJourneyAssertions.kt"
                )
                .readText()
        val barrier =
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18MiniHomeDisplayedStateBarrier.kt"
                )
                .readText()
        val eventStream =
            root
                .resolve(
                    "app/src/debug/kotlin/com/planterior/helper/" + "Todo18PrimaryEventStream.kt"
                )
                .readText()

        assertOrdered(
            probe.substringAfter("fun navigateAndAwaitMiniHomeLoaded()"),
            "currentDisplayedMiniHomeState()?.sequence",
            "snapshot()",
            "compose.activity.navigationController.navigate(PlanteriorRoute.MiniHome)",
            "boundaryEvent.await(",
            "singleOrNull {",
            "it.loadId.value > loadIdFloor",
            "Todo18MiniHomeDisplayedStateBarrier(",
        )
        assertOrdered(
            journey,
            "events.navigateAndAwaitMiniHomeLoaded()",
            "rendered.awaitMiniHomeViewingAfterLoad(miniHomeBarrier)",
            "onNodeWithTag(MiniHomeTestTags.EDIT)",
        )
        assertOrdered(
            barrier,
            "event.state as? MiniHomeUiState.Viewing",
            "singleOrNull { it.loadId.value == loadIdentity.loadId }",
            "progress.valid",
            "event.sequence > sequenceFloor",
            "event.loadIdentity?.value == loadIdentity.loadId",
            "viewing.owner == loadIdentity.accountId",
        )
        assertTrue(eventStream.contains("val loadIdentity: MiniHomeLoadIdentity?"))
        assertTrue(eventStream.contains("(state as? MiniHomeUiState.Viewing)?.loadIdentity"))
        assertTrue(renderedProbe.contains("acceptRegistrationReplay = true"))
        assertTrue(
            renderedProbe
                .substringAfter("fun awaitMiniHomeViewingAfterLoad(")
                .substringBefore("fun awaitRegistration(")
                .contains("acceptRegistrationReplay = true")
        )
        assertTrue(
            root
                .resolve(
                    "app/src/androidTest/kotlin/com/planterior/helper/ExactEventSubscription.kt"
                )
                .readText()
                .contains("private val acceptRegistrationReplay: Boolean = false")
        )
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
