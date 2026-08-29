package com.planterior.helper

import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.auth.Todo18DebugRuntimeDependencies
import com.planterior.helper.diagnostic.Todo18CaptureExactEventObserver
import com.planterior.helper.diagnostic.Todo18DiagnosticCapture
import com.planterior.helper.diagnostic.Todo18DiagnosticEnvelope
import com.planterior.helper.diagnostic.Todo18DiagnosticReceipt
import com.planterior.helper.diagnostic.Todo18ExactEventObserver
import com.planterior.helper.diagnostic.Todo18ExactEventPhase
import com.planterior.helper.diagnostic.Todo18PipelineEventKind
import com.planterior.helper.diagnostic.Todo18WaitId
import java.io.File

internal class Todo18TransitionDiagnosticCapture(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
    private val waitId: Todo18WaitId,
) {
    private val sink = runtime.renderedStateSink
    private val capture: Todo18DiagnosticCapture =
        sink.startDiagnosticCapture(waitId, runtime.initialSinkFreshness)
    private val provenance = captureTodo18DiagnosticProvenance()
    private val observer = Todo18CaptureExactEventObserver(capture) { sink.primaryListenerCount() }
    private var installedSinkIdentity = "<unavailable>"
    private var runtimeSinkIdentity = "<unavailable>"
    private var activitySinkIdentity = "<unavailable>"
    private var activityInstanceIdentity = "<unavailable>"
    private var navHostInstanceIdentity = "<unavailable>"
    private var overrideInstalledAtCapture = false

    fun <T> run(
        wait: (Todo18ExactEventObserver) -> T,
        uiPostcondition: () -> Unit,
    ): T =
        preserveTodo18PrimaryFailure(
            block = {
                compose.waitForIdle()
                check(runtime.activityCreateCount == 1) {
                    "Todo18 expected one MainActivity, found ${runtime.activityCreateCount}"
                }
                val installedOverrides = Todo18DebugRuntimeDependencies.current()
                val installedSink = installedOverrides?.renderedStateSink
                val runtimeSink = runtime.renderedStateSink
                val activitySink = compose.activity.todo18RenderedStateSink
                installedSinkIdentity = installedSink?.identity() ?: "<null>"
                runtimeSinkIdentity = runtimeSink.identity()
                activitySinkIdentity = activitySink?.identity() ?: "<null>"
                activityInstanceIdentity = compose.activity.identity()
                navHostInstanceIdentity = compose.activity.navigationController.identity()
                overrideInstalledAtCapture = installedOverrides != null
                check(runtime.initialSinkFreshness.fresh) {
                    "Todo18 sink was not fresh before Activity creation"
                }
                wait(observer).also {
                    compose.waitForIdle()
                    uiPostcondition()
                    capture.recordPipeline(Todo18PipelineEventKind.UI_POSTCONDITION)
                }
            },
            finish = { primary -> finalizeReceipt(primary) },
        )

    private fun finalizeReceipt(primary: Throwable?): IllegalStateException? {
        capture.close()
        val snapshot = capture.snapshot()
        val finalListenerCount = sink.primaryListenerCount()
        val exactPhases = snapshot.exactEvents.map { it.phase }.toSet()
        val receipt =
            Todo18DiagnosticReceipt(
                envelope =
                    Todo18DiagnosticEnvelope(
                        schema = Todo18DiagnosticEnvelope.SCHEMA,
                        waitId = waitId,
                        expectedSourceSha256 = provenance.expectedSourceSha256,
                        embeddedSourceSha256 = provenance.embeddedSourceSha256,
                        expectedAppApkSha256 = provenance.expectedAppApkSha256,
                        observedAppApkSha256 = provenance.observedAppApkSha256,
                        expectedAndroidTestApkSha256 = provenance.expectedAndroidTestApkSha256,
                        observedAndroidTestApkSha256 = provenance.observedAndroidTestApkSha256,
                        bindingValidated = provenance.bindingValidated,
                        installedSinkIdentity = installedSinkIdentity,
                        runtimeSinkIdentity = runtimeSinkIdentity,
                        activitySinkIdentity = activitySinkIdentity,
                        activityInstanceIdentity = activityInstanceIdentity,
                        navHostInstanceIdentity = navHostInstanceIdentity,
                        freshSink = runtime.initialSinkFreshness.fresh,
                        initialSequence = runtime.initialSinkFreshness.initialSequence,
                        initialCurrentsEmpty = runtime.initialSinkFreshness.initialCurrentsEmpty,
                        initialListenerCount = runtime.initialSinkFreshness.initialListenerCount,
                        priorActivityCount = runtime.priorActivityCount,
                        priorOverridePresent = runtime.priorOverridePresent,
                        overrideInstalledAtCapture = overrideInstalledAtCapture,
                        activityCreateCount = runtime.activityCreateCount,
                        activityDestroyCount = runtime.activityDestroyCount,
                        activityActiveCount = runtime.activityActiveCount,
                        previousTeardownComplete = runtime.previousTeardownComplete,
                        captureFinalized = snapshot.closed,
                        detached = Todo18ExactEventPhase.DETACH in exactPhases,
                        drained = Todo18ExactEventPhase.DRAIN in exactPhases,
                        finalListenerCount = finalListenerCount,
                        diagnosticFailures = snapshot.failures,
                    ),
                pipeline = snapshot.pipeline,
            )
        return Todo18DiagnosticReceiptFinalizer(
                receiptFile = ::receiptFile,
                diagnosticName = "Todo18 ${waitId.name}",
            )
            .finish(
                hasPrimaryFailure = primary != null,
                status = "complete",
            ) { file ->
                file.writeText(receipt.toCompactJson(snapshot))
            }
    }

    private fun receiptFile(): File {
        val directory =
            requireNotNull(
                    InstrumentationRegistry.getInstrumentation()
                        .targetContext
                        .getExternalFilesDir("todo18-e2e-journeys")
                )
                .also {
                    check(it.exists() || it.mkdirs())
                }
        return File(directory, "${waitId.name.lowercase()}-diagnostic.json")
    }

    private fun Any.identity(): String =
        "${javaClass.name}@${Integer.toHexString(System.identityHashCode(this))}"
}
