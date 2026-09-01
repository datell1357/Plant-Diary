package com.planterior.helper.minihome

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18MiniHomeLoadDiagnosticSourceContractTest {
    @Test
    fun `pre-armed conflict receipt captures exact staged and rendered timelines on failure`() {
        // Given
        val root = repositoryRoot()
        val capturePath =
            root.resolve(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18MiniHomeInitialLoadDiagnosticCapture.kt"
            )
        assertTrue("Missing pre-armed MiniHome diagnostic", Files.exists(capturePath))
        val capture = capturePath.readText()
        val receipt =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18MiniHomeInitialLoadDiagnosticReceipt.kt"
            )
        val progressReceipt =
            root.source(
                "app/src/debug/kotlin/com/planterior/helper/minihome/" +
                    "Todo18MiniHomeLoadDiagnosticReceipt.kt"
            )
        val finalization =
            root.source(
                "app/src/debug/kotlin/com/planterior/helper/Todo18DiagnosticReceiptFinalizer.kt"
            )
        val reducer =
            root.source(
                "app/src/debug/kotlin/com/planterior/helper/minihome/" +
                    "Todo18MiniHomeLoadReceiptReducer.kt"
            )
        val renderedViewingIdentity =
            root.source(
                "app/src/debug/kotlin/com/planterior/helper/minihome/" +
                    "Todo18MiniHomeRenderedViewingIdentity.kt"
            )
        val diagnosticCode =
            "$capture\n$receipt\n$progressReceipt\n$finalization\n$reducer\n" +
                renderedViewingIdentity
        val assertions =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18MiniHomeJourneyAssertions.kt"
            )
        val majorJourney =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18MajorJourneyAssertions.kt"
            )
        val exactEventTests =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/ExactEventSubscriptionTest.kt"
            )

        // When / Then
        assertCode(
            diagnosticCode,
            "runtime.boundary.subscribe",
            "runtime.renderedStateSink",
            "LOAD_DIAGNOSTIC_KINDS",
            "subscribeToRawMiniHomeStates",
            "subscribeToDisplayedMiniHomeStates",
            "finally",
            "todo18-e2e-journeys",
            "captureInitialLoad(scenarioName",
            "File(directory, \"\$scenarioName-diagnostic.json\")",
            "todo18-mini-home-load-diagnostic-v5",
            "put(\"api\", input.api)",
            "remote-load-entered",
            "remote-load-returned",
            "publication-read-entered",
            "cache-transaction-call-entered",
            "cache-transaction-body-entered",
            "cache-transaction-returned",
            "publication-read-terminal-returned",
            "put(\"valid\", progress.valid)",
            "activeStage",
            "lastReachedStage",
            "reachedStages",
            "observations",
            "loads",
            "loadId",
            "readId",
            "diagnosticOrder",
            "progressionViolations",
            "progress.progressionProblems()",
            "loadEntered",
            "loadTerminal",
            "timeline",
            "stateLoadId",
            "renderedViewingIdentityProblems",
            "rendered-viewing-",
            "load-identity-missing",
            "load-identity-mismatch",
            "catch (failure: AssertionError)",
            "catch (failure: Exception)",
            "primaryFailure.addSuppressed(summary)",
            "Todo18MiniHomeLoadReceiptReducer.problems",
        )
        assertOrdered(
            capture,
            "val armed = arm(scenarioName)",
            "preserveTodo18PrimaryFailure(block",
            "armed::finish",
        )
        assertOrdered(finalization, "return block()", "finally", "finish(primaryFailure)")
        assertOrdered(
            capture,
            "runtime.boundary.subscribe",
            "subscribeToRawMiniHomeStates",
            "subscribeToDisplayedMiniHomeStates",
        )
        assertCode(
            assertions,
            "captureInitialLoad(",
            "\"offline-mini-home-initial-load\"",
            "Todo18TransitionDiagnosticCapture(",
            "navigateDirectly(PlanteriorRoute.MiniHome)",
        )
        assertOrdered(
            assertions,
            "Todo18MiniHomeInitialLoadDiagnosticCapture(runtime, compose).captureInitialLoad(",
            "\"offline-mini-home-initial-load\"",
            "Todo18TransitionDiagnosticCapture(",
            "navigateDirectly(PlanteriorRoute.MiniHome)",
        )
        assertCode(
            assertions,
            "captureInitialLoad(",
            "\"mini-home-conflict-initial-load\"",
            "rendered.awaitMiniHome",
            "navigateDirectly(PlanteriorRoute.MiniHome)",
        )
        assertOrdered(
            majorJourney,
            "captureInitialLoad(",
            "\"registration-mini-home-initial-load\"",
            "events.navigateAndAwaitMiniHomeLoaded()",
            "rendered.awaitMiniHomeViewingAfterLoad(miniHomeBarrier)",
            "onNodeWithTag(MiniHomeTestTags.EDIT)",
        )
        assertCode(
            exactEventTests,
            "fun firstEmitAndAwaitLinearizationsSucceed()",
            "fixture.subscription.arm()",
            "fun cancellationSchedulesHaveOneCancelledTerminal()",
            "fixture.subscription.close()",
            "assertClean(fixture)",
        )
        assertOrdered(
            finalization,
            "try {",
            "receipt = receiptFile()",
            "writeReceipt(receipt)",
            "catch (failure: AssertionError)",
            "catch (failure: Exception)",
        )
        assertFalse(diagnosticCode.contains("catch (failure: Throwable)"))
        assertFalse(diagnosticCode.contains("Thread.sleep"))
        assertFalse(diagnosticCode.contains("poll"))
    }

    @Test
    fun `load stages use the actual Firebase delegate and deterministic seams`() {
        // Given
        val root = repositoryRoot()
        val runtimeRule =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18IntegratedRuntimeRule.kt"
            ) +
                root.source(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18MiniHomeRuntimeRepository.kt"
                )
        val releaseOverrides =
            root.source(
                "app/src/release/kotlin/com/planterior/helper/auth/Todo18DebugRuntimeDependencies.kt"
            )
        val remoteFixture =
            root.source(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18MiniHomeRepositoryFixture.kt"
            )
        val recorder =
            root.source(
                "app/src/debug/kotlin/com/planterior/helper/minihome/" +
                    "Todo18MiniHomeLoadDiagnosticRecorder.kt"
            )
        val repository =
            root.source(
                "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/" +
                    "FirebaseMiniHomeRepository.kt"
            )

        // When / Then
        assertCode(
            runtimeRule,
            "todo18MiniHomeRuntimeRepository(",
            "FirebaseMiniHomeRepository(",
            "Todo18MiniHomeLoadDiagnosticRecorder(boundary::emitMiniHomeLoadDiagnostic)",
            "Todo18MiniHomeRepositoryFixture(boundary, diagnostics)",
            "beforeCacheApply =",
            "Todo18MiniHomeLoadDiagnostic.CacheApplyEntered",
            "afterCacheApply =",
            "Todo18MiniHomeLoadDiagnostic.CacheApplyReturned",
            "beforePublicationRead =",
            "diagnostics.recordCurrentPublicationRead(",
            "Todo18MiniHomeLoadDiagnostic.PublicationReadEntered",
            "afterPublicationRead =",
            "Todo18MiniHomeLoadDiagnostic.PublicationReadReturned",
            "onCacheTransactionDiagnostic =",
            "diagnostics.recordCurrentCacheTransactionIfActive(",
            "onPublicationReadTerminal =",
            "diagnostics.recordCurrentPublicationReadTerminalIfActive(",
            "Todo18MiniHomeLoadDiagnosticRepository(",
            "diagnostics = diagnostics",
        )
        assertCode(
            repository,
            "beforeCacheApply: suspend (AccountId) -> Unit = {}",
            "afterCacheApply: suspend (AccountId, Boolean) -> Unit = { _, _ -> }",
            "observeCacheDiagnostic",
            "notifyCacheTransactionDiagnostic",
            "notifyPublicationReadTerminal",
            "private suspend fun currentSnapshotConflict(",
            "private suspend fun verifiedInventoryConflict(",
            "catch (error: CancellationException)",
            "catch (_: AssertionError)",
            "catch (_: Exception)",
        )
        assertOrdered(
            repository,
            "notifyCacheApplyEntered(account)",
            "val applied = cache(account, snapshot)",
            "notifyCacheApplyReturned(account, applied)",
            "publishCurrentRoomWinner(",
        )
        assertOrdered(
            remoteFixture,
            "recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.RemoteLoadEntered)",
            "scenario.emit(\"mini-home-loaded\"",
            "recordCurrentIfActive(Todo18MiniHomeLoadDiagnostic.RemoteLoadReturned)",
        )
        assertCode(
            recorder,
            "suspend fun recordCurrent(diagnostic",
            "withContext(LoadContext(this, load))",
            "context[LoadContext]?.takeIf { it.owner === this }?.load",
            "suspend fun recordCurrentIfActive(diagnostic",
            "currentLoad(coroutineContext) ?: return false",
            "return true",
        )
        assertFalse(recorder.contains("activeLoads"))
        assertFalse(recorder.contains("Job"))
        assertFalse(recorder.contains("unwrapCoroutineRecoveredCancellation"))
        assertFalse(recorder.contains("_COROUTINE."))
        assertFalse(recorder.contains("stackTrace"))
        assertFalse(recorder.contains("cause as? CancellationException"))
        assertFalse(recorder.contains("Job.isActive"))
        assertFalse(releaseOverrides.contains("Todo18MiniHomeLoadDiagnosticRepository"))
        assertFalse(runtimeRule.contains("InMemoryMiniHomeRepository"))
    }

    private fun Path.source(relative: String): String = resolve(relative).readText()

    private fun assertCode(code: String, vararg tokens: String) {
        tokens.forEach { assertTrue("Missing code token: $it", code.contains(it)) }
    }

    private fun assertOrdered(code: String, vararg tokens: String) {
        val positions = tokens.map(code::indexOf)
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
