package com.planterior.helper

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18RenderedStateSinkSourceContractTest {
    @Test
    fun `direct rendered state sink follows the installed override object by identity`() {
        // Given
        val root = repositoryRoot()
        val contract =
            root.source(
                "app/src/main/kotlin/com/planterior/helper/auth/AuthRuntimeDependencyOverrides.kt"
            )
        val runtime = root.source("app/src/main/kotlin/com/planterior/helper/auth/AuthRuntime.kt")
        val releaseOverrides =
            root.source(
                "app/src/release/kotlin/com/planterior/helper/auth/Todo18DebugRuntimeDependencies.kt"
            )
        val activity = root.source("app/src/main/kotlin/com/planterior/helper/MainActivity.kt")
        val navHost =
            root.source("app/src/main/kotlin/com/planterior/helper/navigation/PlanteriorNavHost.kt")
        val miniHomeRoute =
            root.source(
                "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/MiniHomeRoute.kt"
            )
        val registrationRoute =
            root.source(
                "feature/registration/src/main/kotlin/com/planterior/helper/feature/registration/RegistrationRoute.kt"
            )
        val runtimeRule =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18IntegratedRuntimeRule.kt"
            )
        val renderedSink =
            root.source("app/src/debug/kotlin/com/planterior/helper/Todo18RenderedStateSink.kt")
        val renderedProbe =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18RenderedStateProbe.kt"
            )

        // When / Then
        assertCode(
            contract,
            "interface RenderedStateSink",
            "onMiniHomeRawState",
            "onMiniHomeDisplayedState",
            "onRegistrationState",
        )
        assertCode(contract, "val renderedStateSink: RenderedStateSink? = null")
        assertCode(runtime, "val renderedStateSink", "overrides.renderedStateSink")
        assertCode(releaseOverrides, "todo18DebugRuntimeDependencyOverrides()", "= null")
        assertFalse(releaseOverrides.contains("Todo18RenderedStateSink"))
        assertCode(activity, "renderedStateSink = authRuntime?.renderedStateSink")
        assertCode(
            navHost,
            "miniHomeDiagnosticsEnabled = renderedStateSink != null",
            "if (miniHomeDiagnosticsEnabled) MiniHomeDiagnosticGenerations() else null",
        )
        assertCode(
            navHost,
            "renderedStateSink?.onMiniHomeRawState",
            "renderedStateSink?.onMiniHomeDisplayedState",
            "renderedStateSink?.onRegistrationState",
        )
        assertCode(
            miniHomeRoute,
            "currentRawStateObserved(observed)",
            "onStateObserved(displayedState)",
        )
        assertOrdered(
            miniHomeRoute,
            "publishMiniHomeRouteState(",
            "currentRawStateObserved(observed)",
        )
        assertCode(registrationRoute, "currentStateObserved(state)")
        assertOrdered(
            registrationRoute,
            "publishRegistrationRouteState(",
            "currentStateObserved(state)",
        )
        assertCode(
            runtimeRule,
            "val renderedStateSink = Todo18RenderedStateSink()",
            "renderedStateSink = renderedStateSink",
        )
        assertCode(
            renderedSink,
            "class Todo18RenderedStateSink : RenderedStateSink",
            "publishMiniHome(Todo18StateChannel.MINI_HOME_RAW, rawMiniHomeStates, event)",
            "publishMiniHome(Todo18StateChannel.MINI_HOME_DISPLAYED, displayedMiniHomeStates, event)",
            "registrationStates.publish(event)",
        )
        assertCode(
            renderedProbe,
            "runtime.renderedStateSink",
            "sink::subscribeToDisplayedMiniHomeStates",
            "sink::subscribeToRegistrationStates",
        )
        assertFalse(renderedProbe.contains("DebugMiniHomeStateEvents"))
        assertFalse(renderedProbe.contains("DebugRegistrationStateEvents"))
        assertFalse(renderedProbe.contains("currentDebugMiniHomeState"))
        assertFalse(renderedProbe.contains("currentDebugRegistrationState"))
    }

    @Test
    fun `share Ready uses a fresh typed rendered event before link creation`() {
        val root = repositoryRoot()
        val contract =
            root.source(
                "app/src/main/kotlin/com/planterior/helper/auth/AuthRuntimeDependencyOverrides.kt"
            )
        val route =
            root.source(
                "feature/share/src/main/kotlin/com/planterior/helper/feature/share/" +
                    "MiniHomeShareRoute.kt"
            )
        val navHost =
            root.source("app/src/main/kotlin/com/planterior/helper/navigation/PlanteriorNavHost.kt")
        val snapshots =
            root.source(
                "app/src/debug/kotlin/com/planterior/helper/Todo18RenderedStateSnapshots.kt"
            )
        val sink =
            root.source("app/src/debug/kotlin/com/planterior/helper/Todo18RenderedStateSink.kt")
        val probe =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18RenderedStateProbe.kt"
            )
        val shareJourney =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18ShareJourneyAssertions.kt"
            )
        val majorJourney =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MajorJourneyAssertions.kt"
            )
        val controller =
            root.source(
                "feature/share/src/main/kotlin/com/planterior/helper/feature/share/MiniHomeShareController.kt"
            )
        val screen =
            root.source(
                "feature/share/src/main/kotlin/com/planterior/helper/feature/share/MiniHomeShareScreen.kt"
            )
        val readyWait =
            probe
                .substringAfter("fun awaitMiniHomeShareReady(")
                .substringBefore("fun awaitRegistration(")

        assertTrue(
            "Missing default no-op share rendered-state callback",
            Regex(
                    """fun\s+onMiniHomeShareState\s*\(\s*state:\s*MiniHomeShareUiState\s*\)\s*""" +
                        """(?:=\s*Unit|\{\s*\})"""
                )
                .containsMatchIn(contract),
        )
        assertTrue(
            "Missing optional share route callback",
            Regex("""onStateObserved\s*:\s*\(MiniHomeShareUiState\)\s*->\s*Unit\s*=\s*\{\s*\}""")
                .containsMatchIn(route),
        )
        assertCode(route, "SideEffect", "onStateObserved(state)")
        assertCode(navHost, "MiniHomeShareRoute(", "onStateObserved =", "onMiniHomeShareState")
        assertCode(
            snapshots,
            "data class Todo18MiniHomeShareStateEvent",
            "val sequence: Long",
            "val state: MiniHomeShareUiState",
        )
        assertCode(
            sink,
            "Todo18PrimaryEventStream<Todo18MiniHomeShareStateEvent>",
            "override fun onMiniHomeShareState",
            "Todo18MiniHomeShareStateEvent",
            "shareStates.publish",
            "currentMiniHomeShareState",
            "subscribeToMiniHomeShareStates",
        )
        assertCode(
            readyWait,
            "ExactEventSubscription",
            "sink::subscribeToMiniHomeShareStates",
            "sink::currentMiniHomeShareState",
            "acceptRegistrationReplay = true",
        )
        assertTrue(
            "Share wait must require a strictly newer Ready plus Idle event",
            Regex(
                    """event\.sequence\s*>\s*floor[\s\S]*""" +
                        """event\.state\s+is\s+MiniHomeShareUiState\.Ready[\s\S]*""" +
                        """event\.state\.link\s+is\s+MiniHomeShareLinkState\.Idle"""
                )
                .containsMatchIn(readyWait),
        )
        assertCode(controller, "data class Ready(", "MiniHomeShareLinkState.Idle")
        assertOrdered(screen, "MiniHomeShareLinkState.Idle", "MiniHomeShareTestTags.LINK_CREATE")
        listOf(shareJourney, majorJourney).forEach { journey ->
            val shareBlock = journey.substringAfter("currentMiniHomeShareState()?.sequence")
            assertOrdered(
                shareBlock,
                " ?: 0L",
                "events.navigateAndAwaitBoundary(",
                "awaitMiniHomeShareReady(floor)",
                "onNodeWithTag(MiniHomeShareTestTags.LINK_CREATE)",
            )
        }
    }

    @Test
    fun `registration wait matches the post selection controller state`() {
        // Given
        val harness =
            repositoryRoot()
                .source(
                    "app/src/androidTest/kotlin/com/planterior/helper/Todo18MainActivityJourneyHarness.kt"
                )

        // When / Then
        assertTrue(
            "Missing selected-content post-selection predicate",
            Regex(
                    """\(event\.state\s+as\?\s+RegistrationUiState\.Editing\)\s*""" +
                        """\?\.draft\s*\?\.selectedContent\s*\?\.id\s*\?\.value\s*==\s*contentId"""
                )
                .containsMatchIn(harness),
        )
        assertFalse(
            "Post-selection wait cannot require search results",
            harness.contains("RegistrationSearchState.Results"),
        )
        assertCode(
            harness,
            "RegistrationTestTags.content(contentId)",
            ".performScrollTo()",
            ".performClick()",
        )
    }

    private fun Path.source(relative: String): String = resolve(relative).readText()

    private fun assertCode(code: String, vararg tokens: String) {
        tokens.forEach { assertTrue("Missing code token: $it", code.contains(it)) }
    }

    private fun assertOrdered(code: String, vararg tokens: String) {
        val positions = tokens.map { token -> code.indexOf(token) }
        tokens.zip(positions).forEach { (token, position) ->
            assertTrue("Missing ordered code token: $token", position >= 0)
        }
        assertTrue(
            "Code tokens are out of order: ${tokens.toList()}",
            positions == positions.sorted(),
        )
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}
