package com.planterior.helper

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18IntegratedJourneySourceContractTest {
    private val root = repositoryRoot()
    private val androidTestRoot = root.resolve("app/src/androidTest/kotlin/com/planterior/helper")

    @Test
    fun `release dependency and camera seams are immutable pass throughs`() {
        val runtime = source(RELEASE_RUNTIME_OVERRIDE)
        val camera = source(RELEASE_CAMERA_OVERRIDE)

        assertCode(runtime, "todo18DebugRuntimeDependencyOverrides()", "= null")
        assertFalse(runtime.contains("object Todo18DebugRuntimeDependencies"))
        assertCode(camera, "todo18DebugCameraPermission(actual: CameraPermission)", "= actual")
        assertCode(camera, "todo18DebugPhotoPickerUri(): String? = null")
        assertFalse(camera.contains("@Volatile"))
    }

    @Test
    fun `debug graph overrides only typed product dependencies`() {
        val contract = source(MAIN_OVERRIDE_CONTRACT)
        val runtime = source("app/src/main/kotlin/com/planterior/helper/auth/AuthRuntime.kt")
        val activity = source("app/src/main/kotlin/com/planterior/helper/MainActivity.kt")

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
    }

    @Test
    fun `split harness preserves journey behavior and exact event synchronization`() {
        val sources = todo18AndroidTestSources()
        val allCode = sources.values.joinToString("\n")
        val entryPoint = sources.getValue("Todo18IntegratedJourneyMainActivityTest.kt")
        val runtimeRule = sources.getValue("Todo18IntegratedRuntimeRule.kt")
        val eventProbe = sources.getValue("Todo18JourneyEventProbe.kt")

        EXPECTED_JOURNEY_TESTS.forEach { assertFunction(entryPoint, it) }
        EXPECTED_ASSERTION_ROLES.forEach { assertFunction(allCode, it) }
        EXPECTED_PRODUCTION_REPOSITORIES.forEach { assertCode(runtimeRule, it) }
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
            "InventoryTestTags.FEEDBACK",
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
            "MiniHomeTestTags.CONFLICT",
            "MiniHomeTestTags.RETRY",
        )
        assertCode(
            sources.getValue("Todo18CameraJourneyAssertions.kt"),
            "denyCameraPermission()",
            "awaitRejectedPhoto",
            "!event.accepted",
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
        assertCode(sources.getValue("Todo18MainActivityJourneyHarness.kt"), "captureToImage")
        assertSizeContract()

        FORBIDDEN_SYNCHRONIZATION.forEach { forbidden ->
            sources.forEach { (file, code) ->
                assertFalse("$file contains $forbidden", code.contains(forbidden))
            }
        }
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
