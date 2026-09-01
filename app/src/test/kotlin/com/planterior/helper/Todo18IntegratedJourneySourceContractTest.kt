package com.planterior.helper

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18IntegratedJourneySourceContractTest {
    private val root = repositoryRoot()
    private val androidTestRoot = root.resolve("app/src/androidTest/kotlin/com/planterior/helper")

    @Test
    fun `failure preserving captures bind late validation and semantic reducer verdicts`() {
        val offline =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18OfflineRetryTransitionDiagnosticCapture.kt"
            )
        val inventory =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/" +
                    "Todo18InventorySettlementDiagnosticCapture.kt"
            )

        assertCode(
            offline,
            "receipt = requireNotNull(receipt).withPrimaryFailure(primaryFailure)",
        )
        assertCode(
            inventory,
            "Todo18InventorySettlementReceiptReducer.problems(observations)",
            ".finish(primaryFailure != null, status)",
        )
    }

    @Test
    fun `release dependency and camera seams are immutable pass throughs`() {
        val runtime = source(RELEASE_RUNTIME_OVERRIDE)
        val camera = source(RELEASE_CAMERA_OVERRIDE)
        val registrationState = source(RELEASE_REGISTRATION_STATE)

        assertCode(runtime, "todo18DebugRuntimeDependencyOverrides()", "= null")
        assertFalse(runtime.contains("object Todo18DebugRuntimeDependencies"))
        assertCode(camera, "todo18DebugCameraPermission(actual: CameraPermission)", "= actual")
        assertCode(camera, "todo18DebugPhotoPickerUri(): String? = null")
        assertFalse(camera.contains("@Volatile"))
        assertCode(registrationState, "observeDebugRegistrationState", "= Unit")
        assertFalse(registrationState.contains("AtomicLong"))
    }

    @Test
    fun `debug graph overrides only typed product dependencies`() {
        val contract = source(MAIN_OVERRIDE_CONTRACT)
        val runtime = source("app/src/main/kotlin/com/planterior/helper/auth/AuthRuntime.kt")
        val activity = source("app/src/main/kotlin/com/planterior/helper/MainActivity.kt")
        val registrationState = source(DEBUG_REGISTRATION_STATE)

        listOf(
                "RegistrationRepository",
                "CollectionRepository",
                "MiniHomeRepository",
                "InventoryRepository",
                "WateringRepository",
                "WeatherRepository",
                "AccountDeletionDependencies",
            )
            .forEach { assertCode(contract, it) }
        assertCode(runtime, "withTodo18DebugOverrides()")
        assertCode(runtime, "todo18DebugRuntimeDependencyOverrides() ?: return this")
        assertCode(
            activity,
            "accountDeletionDependencies = authRuntime?.accountDeletionDependencies",
        )
        assertCode(
            registrationState,
            "AtomicLong",
            "currentDebugRegistrationState",
            "subscribeToDebugRegistrationStates",
            "observeDebugRegistrationState",
        )
    }

    @Test
    fun `API37 Todo18 startup grants local network permission before MainActivity`() {
        val expectedRules =
            listOf(
                "Api37LocalNetworkPermissionRule()",
                "DebugHomeSessionRule(",
                "Todo18IntegratedRuntimeRule()",
                "createAndroidComposeRule<MainActivity>()",
            )
        val expectedIdentificationRules =
            listOf(
                "Api37LocalNetworkPermissionRule()",
                "DebugHomeSessionRule(",
                "createAndroidComposeRule<MainActivity>()",
            )

        assertTodo18StartupRuleOrder(
            "Todo18IntegratedJourneyMainActivityTest.kt",
            expectedRules,
        )
        assertTodo18StartupRuleOrder(
            "Todo18IdentificationFailureJourneyTest.kt",
            expectedIdentificationRules,
        )
    }

    @Test
    fun `malformedPhotoDebugSeamIsTypedAndDeterministic`() {
        val cameraDebug = source(DEBUG_CAMERA_OVERRIDE)
        val cameraRelease = source(RELEASE_CAMERA_OVERRIDE)
        val route =
            source(
                "feature/camera/src/main/kotlin/com/planterior/helper/feature/camera/CameraRoute.kt"
            )
        val probe =
            source("app/src/androidTest/kotlin/com/planterior/helper/Todo18JourneyEventProbe.kt")
        val assertion =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18CameraJourneyAssertions.kt"
            )
        val runtime =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18IntegratedRuntimeRule.kt"
            )

        assertCode(
            cameraDebug,
            "sealed interface Todo18DebugPhotoPreparationTerminal",
            "data class Returned(val accepted: Boolean)",
            "data class Thrown(val failure: Throwable)",
            "data class Cancelled(val cancellation: java.util.concurrent.CancellationException)",
            "data class Todo18DebugPhotoPreparationEvent(",
            "val uri: String,",
            "val terminal: Todo18DebugPhotoPreparationTerminal,",
            "internal suspend fun todo18DebugObservePhotoPreparation(",
            "Todo18DebugCameraBoundary.observePhotoPreparation(",
        )
        assertCode(
            cameraRelease,
            "internal suspend fun todo18DebugObservePhotoPreparation(",
            "prepareAndApply()",
        )

        val debugBranch =
            route
                .substringAfter("CameraCommand.LaunchPhotoPicker")
                .substringBefore("CameraCommand.OpenAppSettings")
        val preparePosition = debugBranch.indexOf("withContext(Dispatchers.IO)")
        val foldPosition = debugBranch.indexOf("result.fold(")
        val seamPosition = debugBranch.indexOf("todo18DebugObservePhotoPreparation(")
        assertTrue("Missing real IO preparation", preparePosition >= 0)
        assertTrue("Missing unchanged result fold", foldPosition >= 0)
        assertTrue("Missing typed debug seam", seamPosition >= 0)
        assertTrue(
            "Typed seam must enclose the existing preparation and fold",
            seamPosition < preparePosition && preparePosition < foldPosition,
        )
        assertCode(
            debugBranch,
            "preparer.prepare(debugUri, PhotoSource.Picker)",
            "result.isSuccess",
            "controller::photoPrepared",
            "controller.photoRejected(it.photoError())",
        )

        assertCode(
            probe,
            "when (val terminal = event.terminal)",
            "Todo18DebugPhotoPreparationTerminal.Returned -> !terminal.accepted",
            "Todo18DebugPhotoPreparationTerminal.Thrown -> true",
            "Todo18DebugPhotoPreparationTerminal.Cancelled -> true",
        )
        assertCode(
            assertion,
            "event.terminal",
            "Todo18DebugPhotoPreparationTerminal.Returned",
            "accepted == false",
        )
        assertCode(runtime, "Uri.fromFile", "exists().not()")
        assertFalse(runtime.contains("content://todo18.invalid/missing-photo"))
    }

    @Test
    fun `split harness preserves journey behavior and exact event synchronization`() {
        val sources = todo18AndroidTestSources()
        val allCode = sources.values.joinToString("\n")
        val entryPoint = sources.getValue("Todo18IntegratedJourneyMainActivityTest.kt")
        val runtimeRule =
            sources.getValue("Todo18IntegratedRuntimeRule.kt") +
                sources.getValue("Todo18MiniHomeRuntimeRepository.kt")
        val eventProbe = sources.getValue("Todo18JourneyEventProbe.kt")
        val renderedProbe = sources.getValue("Todo18RenderedStateProbe.kt")

        EXPECTED_JOURNEY_TESTS.forEach { assertFunction(entryPoint, it) }
        EXPECTED_ASSERTION_ROLES.forEach { assertFunction(allCode, it) }
        EXPECTED_PRODUCTION_REPOSITORIES.forEach { assertCode(runtimeRule, it) }
        assertFalse(runtimeRule.contains("MiniHomeRepository by"))
        listOf("OFFLINE_ONCE", "REVISION_CONFLICT", "EXPIRED", "DELETED").forEach {
            assertCode(allCode, it)
        }
        listOf(
                "fixture-rate-limited",
                "fixture-provider-unavailable",
                "fixture-no-candidates",
            )
            .forEach { assertCode(allCode, it) }
        assertCode(
            sources.getValue("Todo18MajorJourneyAssertions.kt"),
            "cacheDao()",
            ".plant(",
            "ownedItems(",
            "miniHomePlacements(",
            "WateringTestTags.RESULT",
            "InventoryFeedback.ACQUIRED",
            "MiniHomeShareTestTags.LINK_URL",
            "WeatherTestTags.STALE",
            "account-deletion.remaining.AUTH_ACCOUNT",
        )
        assertCode(
            sources.getValue("Todo18MiniHomeJourneyAssertions.kt"),
            "syncDao()",
            ".operation(",
            "listOf(frozen, frozen)",
            "MiniHomeTestTags.SAVE_FAILURE",
            "MiniHomeTestTags.RECONCILE",
            "MiniHomeTestTags.RETRY",
        )
        assertCode(
            sources.getValue("Todo18CameraJourneyAssertions.kt"),
            "denyCameraPermission()",
            "awaitRejectedPhoto",
            "event.terminal is Todo18DebugPhotoPreparationTerminal.Returned",
            "val terminal = event.terminal as Todo18DebugPhotoPreparationTerminal.Returned",
            "terminal.accepted == false",
        )
        assertCode(
            sources.getValue("Todo18ShareJourneyAssertions.kt"),
            "MiniHomeShareTestTags.LINK_FAILURE",
            "MiniHomeShareTestTags.NO_TARGET",
        )
        assertCode(
            eventProbe,
            "ExactEventSubscription",
            "LeasedExactEventRegistration",
            "EVENT_TIMEOUT_MILLIS = 10_000L",
        )
        assertCode(
            renderedProbe,
            "runtime.renderedStateSink",
            "subscribeToDisplayedMiniHomeStates",
            "subscribeToRegistrationStates",
            "event.sequence >= floor",
            "current()?.let(dispatch)",
            "subscription.arm()",
        )
        assertCode(sources.getValue("Todo18MainActivityJourneyHarness.kt"), "captureToImage")
        assertSizeContract()

        FORBIDDEN_SYNCHRONIZATION.forEach { forbidden ->
            sources.forEach { (file, code) ->
                assertFalse("$file contains $forbidden", code.contains(forbidden))
            }
        }
    }

    @Test
    fun `registration harness uses the canonical fixture display name`() {
        val fixture =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18PlantRepositoryFixture.kt"
            )
        val harness =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MainActivityJourneyHarness.kt"
            )

        assertCode(fixture, "RegistrationContent(scenario.contentId, \"몬스테라\")")
        assertCode(harness, "assertTextContains(\"몬스테라\")")
        assertFalse(harness.contains("assertTextContains(\"Monstera\")"))
    }

    @Test
    fun `ordinal-6 journey enters fixed watering date through stable real field`() {
        val registrationScreen =
            source(
                "feature/registration/src/main/kotlin/com/planterior/helper/feature/registration/RegistrationScreen.kt"
            )
        val registrationRoute =
            source(
                "feature/registration/src/main/kotlin/com/planterior/helper/feature/registration/RegistrationRoute.kt"
            )
        val harness =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MainActivityJourneyHarness.kt"
            )

        assertCode(
            registrationScreen,
            "const val LAST_WATERED = \"registration.last-watered\"",
            "onValueChange = { onDate(it) }",
            "testTag(RegistrationTestTags.LAST_WATERED)",
        )
        assertCode(registrationRoute, "onDate = controller::changeLastWateredDate")
        assertCode(
            harness,
            "onNodeWithTag(RegistrationTestTags.LAST_WATERED)",
            "performTextReplacement(\"2026-08-20\")",
        )
    }

    @Test
    fun `ordinal-6 boundary uses one compose settlement before exact await`() {
        val eventProbe =
            source("app/src/androidTest/kotlin/com/planterior/helper/Todo18JourneyEventProbe.kt")
        val miniHome =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MiniHomeJourneyAssertions.kt"
            )

        assertCode(
            eventProbe,
            "subscription.arm()",
            "triggerSettleAndAwait(",
            "trigger = { subscription.trigger(trigger) }",
            "settle = compose::waitForIdle",
            "subscription.await(",
        )
        assertCode(
            miniHome,
            "events.awaitBoundary(\"mini-home-save-attempt\")",
            "assertEquals(frozen.value, saveAttempt.identity)",
            "assertEquals(operationId.value, saveAttempt.identity)",
        )
    }

    @Test
    fun `Offline Retry scrolls the exact selected node before displayed assertion`() {
        val miniHome =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MiniHomeJourneyAssertions.kt"
            )
        val tokens =
            listOf(
                "val retry = compose.onNodeWithTag(MiniHomeTestTags.RETRY)",
                "retry.performScrollTo()",
                "retry.assertIsDisplayed()",
            )
        val positions = tokens.map(miniHome::indexOf)

        tokens.zip(positions).forEach { (token, position) ->
            assertTrue("Missing ordered code token: $token", position >= 0)
        }
        assertTrue("Retry interaction tokens are out of order", positions == positions.sorted())
    }

    @Test
    fun `Conflict awaits reconciliation and scrolls the exact recovery action`() {
        val miniHome =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MiniHomeJourneyAssertions.kt"
            )
        val conflict =
            miniHome
                .substringAfter(
                    "internal fun Todo18MainActivityJourneyHarness.assertMiniHomeConflictPreservesDraft()"
                )
                .substringBefore("\ninternal fun ")
        val tokens =
            listOf(
                "val expectedDraft = editing.draft",
                "val expectedOperationId = editing.operationId",
                "MiniHomeSaveState.ReconciliationRequired(",
                "MiniHomeSaveFailure.REVISION_CONFLICT",
                "editing.draft == expectedDraft",
                "editing.operationId == expectedOperationId",
                "val reconcile = compose.onNodeWithTag(MiniHomeTestTags.RECONCILE)",
                "reconcile.performScrollTo()",
                "reconcile.assertIsDisplayed()",
            )
        val positions = tokens.map(conflict::indexOf)

        tokens.zip(positions).forEach { (token, position) ->
            assertTrue("Missing ordered Conflict code token: $token", position >= 0)
        }
        assertTrue(
            "Conflict reconciliation tokens are out of order",
            positions == positions.sorted(),
        )
        assertFalse(conflict.contains("MiniHomeSaveState.Conflict"))
        assertFalse(conflict.contains("MiniHomeTestTags.CONFLICT"))
        assertFalse(conflict.contains("MiniHomeTestTags.RETRY"))
    }

    @Test
    fun `Registration link expiry assertion scrolls into the viewport before requiring display`() {
        val journey =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MajorJourneyAssertions.kt"
            )
        val expiry =
            journey
                .substringAfter(
                    "compose.onNodeWithTag(MiniHomeShareTestTags.LINK_URL).assertIsDisplayed()"
                )
                .substringBefore("events.navigateAndAwaitBoundary(")
        val tokens =
            listOf(
                "MiniHomeShareTestTags.LINK_EXPIRY",
                ".performScrollTo()",
                ".assertIsDisplayed()",
            )
        val positions = tokens.map(expiry::indexOf)

        tokens.zip(positions).forEach { (token, position) ->
            assertTrue("Missing ordered expiry code token: $token", position >= 0)
        }
        assertTrue(
            "Expiry assertion must scroll before its unchanged displayed assertion",
            positions == positions.sorted(),
        )
        assertTrue(
            "Expiry assertion must keep the exact node chain",
            expiry.contains(
                "compose.onNodeWithTag(MiniHomeShareTestTags.LINK_EXPIRY)" +
                    ".performScrollTo().assertIsDisplayed()"
            ),
        )
    }

    @Test
    fun `inventory acquire waits for exact authoritative cache settlement`() {
        val runtime =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18IntegratedRuntimeRule.kt"
            ) +
                source(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18MiniHomeRuntimeRepository.kt"
                )
        val journey =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MajorJourneyAssertions.kt"
            )
        val inventoryJourney =
            journey
                .substringAfter("boundaryKind = \"inventory-loaded\"")
                .substringBefore("boundaryKind = \"mini-home-loaded\"")
        val inventoryRuntime =
            runtime.substringAfter("val inventory =").substringBefore("val watering =")

        assertCode(
            inventoryRuntime,
            "Todo18InventoryCacheSettlementRepository(",
            "delegate =",
            "FirebaseInventoryRepository(",
            "onSettled = {",
            "boundary.emit(\"inventory-cache-settled\", it.operationId.value)",
        )
        val tokens =
            listOf(
                "lateinit var acquired: Todo18BoundaryEvent",
                "rendered.awaitInventoryFeedback(",
                "events.awaitBoundary(\"inventory-cache-settled\")",
                "events.awaitBoundary(\"inventory-acquired\")",
                "InventoryTestTags.acquire(ItemId(\"todo18-planter\"))",
                "assertEquals(acquired.identity, settled.identity)",
                "assertEquals(acquired.identity, feedback.settlement.operationId.value)",
                "runtime.database.cacheDao().ownedItems(Todo18IntegratedRuntimeRule.ACCOUNT_UID).size",
            )
        val positions = tokens.map(inventoryJourney::indexOf)
        val clickPosition = inventoryJourney.indexOf(".performClick()", positions[4])

        tokens.zip(positions).forEach { (token, position) ->
            assertTrue("Missing ordered Inventory settlement token: $token", position >= 0)
        }
        assertTrue("Missing exact Inventory acquire click", clickPosition >= 0)
        val orderedPositions = positions.take(5) + clickPosition + positions.drop(5)
        assertTrue(
            "Inventory settlement tokens are out of order",
            orderedPositions == orderedPositions.sorted(),
        )
    }

    @Test
    fun `runtime initial freshness matches every rendered sink current`() {
        val runtime =
            source(
                    "app/src/androidTest/kotlin/com/planterior/helper/Todo18IntegratedRuntimeRule.kt"
                )
                .substringAfter("internal val initialSinkFreshness")
                .substringBefore("internal var priorActivityCount")
        val snapshots =
            source("app/src/debug/kotlin/com/planterior/helper/Todo18RenderedStateSnapshots.kt")
                .substringAfter("initialCurrentsEmpty =")
                .substringBefore("initialListenerCount")
        val currents =
            listOf(
                "currentRawMiniHomeState() == null",
                "currentRouteMiniHomeState() == null",
                "currentDisplayedMiniHomeState() == null",
                "currentRegistrationState() == null",
                "currentInventoryFeedback() == null",
            )

        currents.forEach { current ->
            assertCode(runtime, current)
            assertCode(snapshots, current)
        }
    }

    @Test
    fun `Offline final Retry closes an exact five-boundary transition receipt`() {
        val navigation =
            source("app/src/main/kotlin/com/planterior/helper/navigation/PlanteriorNavHost.kt")
        val journey =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MiniHomeJourneyAssertions.kt"
            )
        val retry = journey.substringAfter("lateinit var committed: Todo18BoundaryEvent")

        assertCode(
            retry,
            "Todo18OfflineRetryTransitionDiagnosticCapture(",
            "recordTriggerReturned()",
            "events.awaitBoundary(\"mini-home-committed\")",
            "requireComplete(frozen, committed)",
        )
        assertCode(navigation, "onMiniHomeRouteDisplayedState(it)")
    }

    @Test
    fun `Conflict publication reads record exact matched return identities`() {
        val repository =
            source(
                "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/" +
                    "FirebaseMiniHomeRepository.kt"
            )
        val runtime =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18IntegratedRuntimeRule.kt"
            ) +
                source(
                    "app/src/androidTest/kotlin/com/planterior/helper/" +
                        "Todo18MiniHomeRuntimeRepository.kt"
                )

        assertCode(
            repository,
            "afterPublicationRead:",
            "notifyPublicationReadReturned(account, readIdentity)",
        )
        assertCode(
            runtime,
            "afterPublicationRead =",
            "Todo18MiniHomeLoadDiagnostic.PublicationReadReturned",
        )
    }

    @Test
    fun `Inventory route forwards exact rendered acquired feedback before the real click`() {
        val contract = source(MAIN_OVERRIDE_CONTRACT)
        val navigation =
            source("app/src/main/kotlin/com/planterior/helper/navigation/PlanteriorNavHost.kt")
        val journey =
            source(
                "app/src/androidTest/kotlin/com/planterior/helper/Todo18MajorJourneyAssertions.kt"
            )
        val inventory =
            journey
                .substringAfter("boundaryKind = \"inventory-loaded\"")
                .substringBefore("boundaryKind = \"mini-home-loaded\"")

        assertCode(contract, "fun onInventoryState(state: InventoryUiState) = Unit")
        assertCode(navigation, "onStateObserved = { renderedStateSink?.onInventoryState(it) }")
        assertCode(
            inventory,
            "rendered.awaitInventoryFeedback(",
            "InventoryFeedback.ACQUIRED",
            "InventoryTestTags.acquire(ItemId(\"todo18-planter\"))",
            "assertEquals(acquired.identity, settled.identity)",
            "ownedItems(Todo18IntegratedRuntimeRule.ACCOUNT_UID).size",
        )
        assertFalse(inventory.contains("InventoryTestTags.FEEDBACK).assertIsDisplayed()"))
    }

    @Test
    fun `invocation-2 waits finalize source and APK bound receipts through exact observers`() {
        val sources = todo18AndroidTestSources()
        val capture = sources.getValue("Todo18TransitionDiagnosticCapture.kt")
        val receipt = sources.getValue("Todo18TransitionDiagnosticReceiptJson.kt")
        val miniHome = sources.getValue("Todo18MiniHomeJourneyAssertions.kt")
        val harness = sources.getValue("Todo18MainActivityJourneyHarness.kt")

        assertCode(
            miniHome,
            "Todo18WaitId.OFFLINE_INITIAL_VIEWING",
            "Todo18WaitId.CONFLICT_BEGIN_EDIT",
            "observer = observer",
            "MiniHomeTestTags.EDIT).assertIsDisplayed()",
            "MiniHomeTestTags.SAVE).assertIsDisplayed()",
        )
        assertCode(
            harness,
            "Todo18WaitId.REGISTRATION_SELECT_CONTENT",
            "assertTextContains(\"몬스테라\")",
        )
        assertCode(
            capture,
            "preserveTodo18PrimaryFailure",
            "Todo18CaptureExactEventObserver",
            "capture.close()",
            "initialSinkFreshness",
            "activityCreateCount == 1",
            "Todo18DebugRuntimeDependencies.current()",
            "runtime.renderedStateSink",
            "compose.activity.todo18RenderedStateSink",
            "finalListenerCount",
        )
        assertCode(
            receipt,
            "todo18ExpectedSourceSha256",
            "todo18ExpectedAppApkSha256",
            "todo18ExpectedAndroidTestApkSha256",
            "stateDispatches",
            "exactEvents",
            "requestedContentId",
            "beforeState",
            "afterState",
        )
    }

    private fun assertSizeContract() {
        val files =
            todo18AndroidTestPaths() +
                listOf(
                    root.resolve(MAIN_OVERRIDE_CONTRACT),
                    root.resolve(DEBUG_RUNTIME_OVERRIDE),
                    root.resolve(RELEASE_RUNTIME_OVERRIDE),
                    root.resolve(DEBUG_CAMERA_OVERRIDE),
                    root.resolve(RELEASE_CAMERA_OVERRIDE),
                    root.resolve(DEBUG_REGISTRATION_STATE),
                    root.resolve(RELEASE_REGISTRATION_STATE),
                )
        files.forEach { path ->
            val code = path.readText()
            val physical = code.lineSequence().count()
            val pure = pureLoc(code)
            assertTrue("$path has $physical physical LOC", physical <= MAX_LOC)
            assertTrue("$path has $pure pure LOC", pure <= MAX_LOC)
        }
    }

    private fun todo18AndroidTestSources(): Map<String, String> =
        todo18AndroidTestPaths().associate { it.name to it.readText() }

    private fun todo18AndroidTestPaths(): List<Path> =
        Files.list(androidTestRoot).use { paths ->
            paths
                .filter { it.name.startsWith("Todo18") && it.name.endsWith(".kt") }
                .sorted()
                .toList()
        }

    private fun pureLoc(code: String): Int {
        var inBlockComment = false
        return code.lineSequence().count { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> false
                inBlockComment -> {
                    if (trimmed.contains("*/")) inBlockComment = false
                    false
                }
                trimmed.startsWith("/*") -> {
                    if (!trimmed.contains("*/")) inBlockComment = true
                    false
                }
                trimmed.startsWith("//") -> false
                else -> true
            }
        }
    }

    private fun source(relative: String): String = root.resolve(relative).readText()

    private fun assertCode(code: String, vararg tokens: String) {
        tokens.forEach { assertTrue("Missing code token: $it", code.contains(it)) }
    }

    private fun assertTodo18StartupRuleOrder(fileName: String, expectedRules: List<String>) {
        val code = source("app/src/androidTest/kotlin/com/planterior/helper/$fileName")
        val declaredOrders =
            Regex("""@get:Rule\(order = (\d+)\)""")
                .findAll(code)
                .map { it.groupValues[1].toInt() }
                .toList()
        assertEquals(
            "$fileName must declare consecutive startup rule orders",
            expectedRules.indices.toList(),
            declaredOrders,
        )

        val positions = expectedRules.map { token ->
            val position = code.indexOf(token)
            assertTrue("$fileName is missing startup rule token: $token", position >= 0)
            position
        }
        assertEquals(
            "$fileName must preserve startup rule relative order",
            positions.sorted(),
            positions,
        )
    }

    private fun assertFunction(code: String, name: String) {
        assertTrue(
            "Missing function: $name",
            Regex("fun[^\\n]*\\b$name\\s*\\(").containsMatchIn(code),
        )
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }

    private companion object {
        const val MAX_LOC = 250
        const val MAIN_OVERRIDE_CONTRACT =
            "app/src/main/kotlin/com/planterior/helper/auth/AuthRuntimeDependencyOverrides.kt"
        const val DEBUG_RUNTIME_OVERRIDE =
            "app/src/debug/kotlin/com/planterior/helper/auth/Todo18DebugRuntimeDependencies.kt"
        const val RELEASE_RUNTIME_OVERRIDE =
            "app/src/release/kotlin/com/planterior/helper/auth/Todo18DebugRuntimeDependencies.kt"
        const val DEBUG_CAMERA_OVERRIDE =
            "feature/camera/src/debug/kotlin/com/planterior/helper/feature/camera/Todo18DebugCameraBoundary.kt"
        const val RELEASE_CAMERA_OVERRIDE =
            "feature/camera/src/release/kotlin/com/planterior/helper/feature/camera/Todo18DebugCameraBoundary.kt"
        const val DEBUG_REGISTRATION_STATE =
            "app/src/debug/kotlin/com/planterior/helper/registration/DebugRegistrationState.kt"
        const val RELEASE_REGISTRATION_STATE =
            "app/src/release/kotlin/com/planterior/helper/registration/DebugRegistrationState.kt"

        val EXPECTED_JOURNEY_TESTS =
            listOf(
                "registrationCollectionWateringInventoryMiniHomeShareWeatherAndDeletionPersistInRoom",
                "offlineMiniHomeSaveReplaysTheExactPersistedOperationWithoutPolling",
                "miniHomeRevisionConflictPreservesDraftAndShowsRecoveryAction",
                "cameraPermissionDenialAndMalformedUriStayOnProductionCameraFlow",
                "malformedPickerUriIsRejectedByTheRealPhotoValidator",
                "expiredAndDeletedShareResponsesHaveClosedAccessibleStates",
            )
        val EXPECTED_ASSERTION_ROLES =
            listOf(
                "assertMajorProductJourneyPersistsInRoom",
                "assertOfflineMiniHomeReplayUsesPersistedOperation",
                "assertMiniHomeConflictPreservesDraft",
                "assertCameraPermissionDenial",
                "assertMalformedPickerUriRejected",
                "assertExpiredAndDeletedShareStates",
            )
        val EXPECTED_PRODUCTION_REPOSITORIES =
            listOf(
                "FirebaseRegistrationRepository(",
                "FirebaseCollectionRepository(",
                "FirebaseMiniHomeRepository(",
                "FirebaseInventoryRepository(",
                "OutboxWateringRepository(",
            )
        val FORBIDDEN_SYNCHRONIZATION =
            listOf(
                "Thread.sleep(",
                "SystemClock.sleep(",
                "waitUntil(",
                "waitUntilAtLeastOneExists(",
                "delay(",
            )
    }
}
