package com.planterior.helper

import androidx.navigation.NavController
import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.auth.Todo18DebugRuntimeDependencies
import com.planterior.helper.diagnostic.Todo18CaptureExactEventObserver
import com.planterior.helper.diagnostic.Todo18DiagnosticCapture
import com.planterior.helper.diagnostic.Todo18DiagnosticEnvelope
import com.planterior.helper.diagnostic.Todo18DiagnosticReceipt
import com.planterior.helper.diagnostic.Todo18PipelineEventKind
import com.planterior.helper.diagnostic.Todo18WaitId
import com.planterior.helper.feature.registration.RegistrationUiState
import com.planterior.helper.navigation.PlanteriorRoute
import com.planterior.helper.navigation.toPlanteriorRoute
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

/** Pre-armed, host-only receipt for the exact manual registration commit boundary. */
internal class Todo18RegistrationCommitDiagnosticCapture(
    private val runtime: Todo18IntegratedRuntimeRule,
    private val compose: Todo18ComposeRule,
) {
    private val sink = runtime.renderedStateSink
    private val capture: Todo18DiagnosticCapture =
        sink.startDiagnosticCapture(Todo18WaitId.REGISTRATION_COMMIT, runtime.initialSinkFreshness)
    private val provenance = captureTodo18DiagnosticProvenance()
    private val observer = Todo18CaptureExactEventObserver(capture) { sink.primaryListenerCount() }
    private val remoteCommit = remoteCommitSubscription()
    private val destination = destinationSubscription()
    private var installedSinkIdentity = "<unavailable>"
    private var runtimeSinkIdentity = "<unavailable>"
    private var activitySinkIdentity = "<unavailable>"
    private var overrideInstalledAtCapture = false
    private var remoteCommitDrained = false
    private var destinationDrained = false

    init {
        remoteCommit.arm()
        destination.arm()
    }

    fun run(submit: () -> Unit) =
        preserveTodo18PrimaryFailure(
            block = {
                captureSinkBinding()
                val completed =
                    Todo18RenderedStateProbe(runtime, compose)
                        .awaitRegistration(
                            matches = { event -> event.state is RegistrationUiState.Completed },
                            trigger = submit,
                            observer = observer,
                        )
                compose.waitForIdle()
                val plantId = (completed.state as RegistrationUiState.Completed).plant.id
                val committed =
                    remoteCommit.await(
                        timeout = EVENT_TIMEOUT_SECONDS,
                        unit = TimeUnit.SECONDS,
                        description = "registration remote commit",
                    )
                val navigated =
                    destination.await(
                        timeout = EVENT_TIMEOUT_SECONDS,
                        unit = TimeUnit.SECONDS,
                        description = "registration navigation destination",
                    ) as PlanteriorRoute.PlantDetail
                remoteCommitDrained = true
                destinationDrained = true
                check(committed.identity == plantId.value) {
                    "remote commit plant did not match completion"
                }
                check(navigated.plantId == plantId.value) {
                    "navigation destination plant did not match completion"
                }
                plantId.value
            },
            finish = ::finalizeReceipt,
        )

    private fun captureSinkBinding() {
        compose.waitForIdle()
        val installedSink = Todo18DebugRuntimeDependencies.current()?.renderedStateSink
        installedSinkIdentity = installedSink?.identity() ?: "<null>"
        runtimeSinkIdentity = sink.identity()
        activitySinkIdentity = compose.activity.todo18RenderedStateSink?.identity() ?: "<null>"
        overrideInstalledAtCapture = installedSink != null
    }

    private fun remoteCommitSubscription() =
        ExactEventSubscription<Todo18BoundaryEvent>(
            matches = { event ->
                (event.kind == "registration-committed").also { matched ->
                    if (matched) {
                        capture.recordPipeline(
                            kind = Todo18PipelineEventKind.REMOTE_COMMIT,
                            registrationPlantId =
                                com.planterior.helper.core.model.PersonalPlantId(event.identity),
                        )
                    }
                }
            },
            subscribe = { receiver ->
                lateinit var closeable: Closeable
                LeasedExactEventRegistration(
                    receiver = receiver,
                    register = { dispatch -> closeable = runtime.boundary.subscribe(dispatch) },
                    unregister = { closeable.close() },
                )
            },
        )

    private fun destinationSubscription() =
        ExactEventSubscription<PlanteriorRoute>(
            matches = { route ->
                val destination = route as? PlanteriorRoute.PlantDetail
                if (destination == null) {
                    false
                } else {
                    capture.recordPipeline(
                        kind = Todo18PipelineEventKind.REGISTRATION_NAVIGATION_DESTINATION,
                        registrationPlantId =
                            com.planterior.helper.core.model.PersonalPlantId(destination.plantId),
                    )
                    true
                }
            },
            subscribe = { receiver ->
                val controller = compose.activity.navigationController
                lateinit var listener: NavController.OnDestinationChangedListener
                LeasedExactEventRegistration(
                    receiver = receiver,
                    register = { dispatch ->
                        listener = NavController.OnDestinationChangedListener { nav, _, _ ->
                            nav.currentBackStackEntry.toPlanteriorRoute()?.let(dispatch)
                        }
                        compose.runOnIdle { controller.addOnDestinationChangedListener(listener) }
                    },
                    unregister = {
                        compose.runOnIdle {
                            controller.removeOnDestinationChangedListener(listener)
                        }
                    },
                )
            },
        )

    private fun finalizeReceipt(primary: Throwable?): IllegalStateException? {
        var cleanupFailure: Throwable? = null
        runCatching { remoteCommit.close() }
            .onSuccess { remoteCommitDrained = true }
            .exceptionOrNull()
            ?.let { cleanupFailure = it }
        runCatching { destination.close() }
            .onSuccess { destinationDrained = true }
            .exceptionOrNull()
            ?.let {
                if (cleanupFailure == null) cleanupFailure = it
                else checkNotNull(cleanupFailure).addSuppressed(it)
            }
        capture.close()
        val snapshot = capture.snapshot()
        val receipt =
            Todo18DiagnosticReceipt(
                envelope =
                    Todo18DiagnosticEnvelope(
                        schema = Todo18DiagnosticEnvelope.SCHEMA,
                        waitId = Todo18WaitId.REGISTRATION_COMMIT,
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
                        detached = remoteCommitDrained && destinationDrained,
                        drained = remoteCommitDrained && destinationDrained,
                        finalListenerCount = sink.primaryListenerCount(),
                        diagnosticFailures = snapshot.failures,
                    ),
                pipeline = snapshot.pipeline,
            )
        return Todo18DiagnosticReceiptFinalizer(
                receiptFile = ::receiptFile,
                diagnosticName = "Todo18 registration commit",
            )
            .finish(
                primary != null,
                if (cleanupFailure == null) "complete" else "subscription-cleanup-failed",
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
                .also { check(it.exists() || it.mkdirs()) }
        return File(directory, "registration-commit-diagnostic.json")
    }

    private companion object {
        const val EVENT_TIMEOUT_SECONDS = 10L
    }

    private fun Any.identity(): String =
        "${javaClass.name}@${Integer.toHexString(System.identityHashCode(this))}"
}
