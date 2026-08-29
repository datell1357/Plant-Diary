package com.planterior.helper.diagnostic

import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.CROSS_TEST_LEAK
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.INVALID_CAPTURE
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.RUNTIME_SINK_IDENTITY_MISMATCH
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.STREAM_DISPATCH_MISSED
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.HASH_A
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.HASH_C
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.valid
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.withKinds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18Invocation2ReviewBlockerTest {
    private val root = repositoryRoot()

    @Test
    fun `identity fields come from three independently observed references`() {
        val capture = source(ANDROID_CAPTURE)

        assertFalse(capture.contains("runtimeSinkIdentity = sinkIdentity"))
        assertTrue(capture.contains("Todo18DebugRuntimeDependencies.current()"))
        assertTrue(capture.contains("runtime.renderedStateSink"))
        assertTrue(capture.contains("compose.activity.todo18RenderedStateSink"))

        val baseline = valid()
        val mismatch =
            baseline.copy(
                envelope =
                    baseline.envelope.copy(
                        installedSinkIdentity = "installed",
                        runtimeSinkIdentity = "runtime",
                        activitySinkIdentity = "activity",
                    )
            )
        assertEquals(
            RUNTIME_SINK_IDENTITY_MISMATCH,
            Todo18DiagnosticReducer.classify(mismatch),
        )
    }

    @Test
    fun `prior and active lifecycle fields come from actual lifecycle and override state`() {
        val capture = source(ANDROID_CAPTURE)
        val runtime = source(ANDROID_RUNTIME)

        assertFalse(
            capture.contains("previousTeardownComplete = runtime.initialSinkFreshness.fresh")
        )
        listOf(
                "priorOverridePresent",
                "priorActivityCount",
                "activityDestroyCount",
                "activityActiveCount",
            )
            .forEach { token ->
                assertTrue("Missing lifecycle observation: $token", runtime.contains(token))
            }

        val baseline = valid()
        val leaks =
            listOf(
                baseline.envelope.copy(priorActivityCount = 1),
                baseline.envelope.copy(priorOverridePresent = true),
                baseline.envelope.copy(activityDestroyCount = 1, activityActiveCount = 0),
                baseline.envelope.copy(activityCreateCount = 2),
            )
        leaks.forEach { envelope ->
            assertEquals(
                CROSS_TEST_LEAK,
                Todo18DiagnosticReducer.classify(baseline.copy(envelope = envelope)),
            )
        }
    }

    @Test
    fun `wrong expected app test and source hashes are independently invalid`() {
        val baseline = valid()
        val mismatches =
            listOf(
                baseline.envelope.copy(expectedSourceSha256 = HASH_C),
                baseline.envelope.copy(expectedAppApkSha256 = HASH_C),
                baseline.envelope.copy(expectedAndroidTestApkSha256 = HASH_A),
            )

        mismatches.forEach { envelope ->
            assertEquals(
                INVALID_CAPTURE,
                Todo18DiagnosticReducer.classify(baseline.copy(envelope = envelope)),
            )
        }
    }

    @Test
    fun `omitted build and runtime expected inputs are invalid and explicitly represented`() {
        val baseline = valid()
        val omitted =
            listOf(
                baseline.envelope.copy(expectedSourceSha256 = null),
                baseline.envelope.copy(expectedAppApkSha256 = null),
                baseline.envelope.copy(expectedAndroidTestApkSha256 = null),
                baseline.envelope.copy(embeddedSourceSha256 = null),
            )
        omitted.forEach { envelope ->
            assertEquals(
                INVALID_CAPTURE,
                Todo18DiagnosticReducer.classify(baseline.copy(envelope = envelope)),
            )
        }

        val build = source("app/build.gradle.kts")
        val provenance = source(ANDROID_PROVENANCE)
        val releaseOverride =
            source(
                "app/src/release/kotlin/com/planterior/helper/auth/Todo18DebugRuntimeDependencies.kt"
            )
        assertTrue(build.contains("todo18.frozenSourceSha256"))
        assertTrue(build.contains("TODO18_FROZEN_SOURCE_SHA256"))
        assertTrue(provenance.contains("BuildConfig.TODO18_FROZEN_SOURCE_SHA256"))
        assertTrue(provenance.contains("targetContext.applicationInfo.sourceDir"))
        assertTrue(provenance.contains("context.applicationInfo.sourceDir"))
        assertTrue(provenance.contains("Todo18DiagnosticProvenanceBinding.captureIfEnabled"))
        assertFalse(releaseOverride.contains("Todo18DiagnosticProvenance"))
        assertFalse(releaseOverride.contains("MessageDigest"))
        assertFalse(Regex("TODO18_FROZEN_SOURCE_SHA256[^\\n]*[0-9a-f]{64}").containsMatchIn(build))
        listOf(
                "todo18ExpectedSourceSha256",
                "todo18ExpectedAppApkSha256",
                "todo18ExpectedAndroidTestApkSha256",
            )
            .forEach { argument ->
                assertTrue(
                    "Missing mandatory runtime input: $argument",
                    provenance.contains(argument),
                )
            }
    }

    @Test
    fun `observed primary dispatch failure reaches stream dispatch missed`() {
        val receipt =
            valid()
                .withKinds(
                    Todo18PipelineEventKind.FRAMEWORK_ACTION_BEGIN,
                    Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN,
                    Todo18PipelineEventKind.SCREEN_CALLBACK,
                    Todo18PipelineEventKind.CONTROLLER_ENTRY,
                    Todo18PipelineEventKind.CONTROLLER_TARGET_STATE,
                    Todo18PipelineEventKind.ROUTE_STATE_OBSERVED,
                    Todo18PipelineEventKind.TASK1_PUBLICATION,
                    Todo18PipelineEventKind.PRIMARY_DISPATCH_BEGIN,
                    Todo18PipelineEventKind.PRIMARY_DISPATCH_FAILURE,
                    Todo18PipelineEventKind.AWAIT_FAILURE,
                    Todo18PipelineEventKind.DETACH,
                    Todo18PipelineEventKind.DRAIN,
                )

        assertEquals(STREAM_DISPATCH_MISSED, Todo18DiagnosticReducer.classify(receipt))
    }

    private fun source(relative: String): String = root.resolve(relative).readText()

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }

    private companion object {
        const val ANDROID_CAPTURE =
            "app/src/androidTest/kotlin/com/planterior/helper/Todo18TransitionDiagnosticCapture.kt"
        const val ANDROID_RUNTIME =
            "app/src/androidTest/kotlin/com/planterior/helper/Todo18IntegratedRuntimeRule.kt"
        const val ANDROID_PROVENANCE =
            "app/src/androidTest/kotlin/com/planterior/helper/Todo18TransitionDiagnosticReceiptJson.kt"
    }
}
