package com.planterior.helper

import androidx.test.platform.app.InstrumentationRegistry
import com.planterior.helper.auth.Todo18DebugRuntimeDependencies
import com.planterior.helper.diagnostic.Todo18DiagnosticCaptureSnapshot
import com.planterior.helper.diagnostic.Todo18DiagnosticEnvelope
import com.planterior.helper.diagnostic.Todo18DiagnosticProvenance
import com.planterior.helper.diagnostic.Todo18DiagnosticProvenanceBinding
import com.planterior.helper.diagnostic.Todo18DiagnosticReceipt
import com.planterior.helper.diagnostic.Todo18ExactEventObservation
import com.planterior.helper.diagnostic.Todo18ExpectedProvenance
import com.planterior.helper.diagnostic.Todo18PipelineEvent
import com.planterior.helper.diagnostic.Todo18RuntimeBinding
import com.planterior.helper.diagnostic.Todo18StateDispatchRecord
import com.planterior.helper.diagnostic.Todo18StateSnapshot
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun captureTodo18DiagnosticProvenance(): Todo18DiagnosticProvenance {
    val arguments = InstrumentationRegistry.getArguments()
    val expected =
        Todo18ExpectedProvenance(
            sourceSha256 = arguments.getString("todo18ExpectedSourceSha256"),
            appApkSha256 = arguments.getString("todo18ExpectedAppApkSha256"),
            androidTestApkSha256 = arguments.getString("todo18ExpectedAndroidTestApkSha256"),
        )
    val embeddedSourceSha256 = BuildConfig.TODO18_FROZEN_SOURCE_SHA256
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    return Todo18DiagnosticProvenanceBinding.captureIfEnabled(
        enabled = Todo18DebugRuntimeDependencies.current()?.renderedStateSink != null,
        expected = expected,
        embeddedSourceSha256 = embeddedSourceSha256,
        appApk = File(instrumentation.targetContext.applicationInfo.sourceDir),
        androidTestApk = File(instrumentation.context.applicationInfo.sourceDir),
    )
        ?: Todo18DiagnosticProvenance(
            expectedSourceSha256 = expected.sourceSha256,
            embeddedSourceSha256 = embeddedSourceSha256,
            expectedAppApkSha256 = expected.appApkSha256,
            observedAppApkSha256 = null,
            expectedAndroidTestApkSha256 = expected.androidTestApkSha256,
            observedAndroidTestApkSha256 = null,
        )
}

internal fun Todo18DiagnosticReceipt.toCompactJson(
    snapshot: Todo18DiagnosticCaptureSnapshot
): String =
    JSONObject()
        .put("envelope", envelope.toJson())
        .put("pipeline", JSONArray(pipeline.map(Todo18PipelineEvent::toJson)))
        .put("stateDispatches", JSONArray(snapshot.stateDispatches.map { it.toJson() }))
        .put("exactEvents", JSONArray(snapshot.exactEvents.map { it.toJson() }))
        .toString()

private fun Todo18DiagnosticEnvelope.toJson() =
    JSONObject()
        .put("schema", schema.jsonValue())
        .put("waitId", waitId?.name.jsonValue())
        .put("expectedSourceSha256", expectedSourceSha256.jsonValue())
        .put("embeddedSourceSha256", embeddedSourceSha256.jsonValue())
        .put("expectedAppApkSha256", expectedAppApkSha256.jsonValue())
        .put("observedAppApkSha256", observedAppApkSha256.jsonValue())
        .put("expectedAndroidTestApkSha256", expectedAndroidTestApkSha256.jsonValue())
        .put("observedAndroidTestApkSha256", observedAndroidTestApkSha256.jsonValue())
        .put("bindingValidated", bindingValidated.jsonValue())
        .put("installedSinkIdentity", installedSinkIdentity.jsonValue())
        .put("runtimeSinkIdentity", runtimeSinkIdentity.jsonValue())
        .put("activitySinkIdentity", activitySinkIdentity.jsonValue())
        .put("activityInstanceIdentity", activityInstanceIdentity.jsonValue())
        .put("navHostInstanceIdentity", navHostInstanceIdentity.jsonValue())
        .put("freshSink", freshSink.jsonValue())
        .put("initialSequence", initialSequence.jsonValue())
        .put("initialCurrentsEmpty", initialCurrentsEmpty.jsonValue())
        .put("initialListenerCount", initialListenerCount.jsonValue())
        .put("priorActivityCount", priorActivityCount.jsonValue())
        .put("priorOverridePresent", priorOverridePresent.jsonValue())
        .put("overrideInstalledAtCapture", overrideInstalledAtCapture.jsonValue())
        .put("activityCreateCount", activityCreateCount.jsonValue())
        .put("activityDestroyCount", activityDestroyCount.jsonValue())
        .put("activityActiveCount", activityActiveCount.jsonValue())
        .put("previousTeardownComplete", previousTeardownComplete.jsonValue())
        .put("captureFinalized", captureFinalized.jsonValue())
        .put("detached", detached.jsonValue())
        .put("drained", drained.jsonValue())
        .put("finalListenerCount", finalListenerCount.jsonValue())
        .put("diagnosticFailures", JSONArray(diagnosticFailures.map { it.name }))

private fun Todo18PipelineEvent.toJson() =
    JSONObject()
        .put("ordinal", ordinal)
        .put("kind", kind.name)
        .put("sourceSequence", sourceSequence.jsonValue())
        .put("controllerIdentity", controllerIdentity.jsonValue())
        .put("requestedContentId", requestedContentId?.value.jsonValue())
        .put("beforeState", beforeState?.name.jsonValue())
        .put("afterState", afterState?.name.jsonValue())
        .put("registrationPlantId", registrationPlantId?.value.jsonValue())
        .put("registrationOperationId", registrationOperationId?.value.jsonValue())
        .put("registrationAccountId", registrationAccountId?.value.jsonValue())
        .put("repositoryIdentity", repositoryIdentity.jsonValue())
        .put("navigationIdentity", navigationIdentity.jsonValue())
        .put("runtimeBinding", runtimeBinding?.toJson().jsonValue())
        .put("elapsedNanos", elapsedNanos.jsonValue())

private fun Todo18RuntimeBinding.toJson() =
    JSONObject()
        .put("controllerIdentity", controllerIdentity)
        .put("controllerEpoch", controllerEpoch)
        .put("controllerGeneration", controllerGeneration)
        .put("collectorGeneration", collectorGeneration)
        .put("callbackGeneration", callbackGeneration)
        .put("attachGeneration", attachGeneration)
        .put("disposeGeneration", disposeGeneration)
        .put("lifecycleOwnerIdentity", lifecycleOwnerIdentity)
        .put("lifecycleState", lifecycleState)
        .put("activityIdentity", activityIdentity)
        .put("navHostIdentity", navHostIdentity)
        .put("callbackSinkIdentity", callbackSinkIdentity)

private fun Todo18StateDispatchRecord.toJson() =
    JSONObject()
        .put("ordinal", ordinal)
        .put("waitId", waitId.name)
        .put("sourceSequence", sourceSequence)
        .put("channel", channel.name)
        .put("state", state.name)
        .put("owner", owner?.value.jsonValue())
        .put("selectedContentId", selectedContentId?.value.jsonValue())
        .put("currentBefore", currentBefore?.toJson().jsonValue())
        .put("currentAfter", currentAfter?.toJson().jsonValue())
        .put("primaryListenerCount", primaryListenerCount)
        .put("phase", phase.name)
        .put("freshForWait", freshForWait)
        .put("isolatedInstance", isolatedInstance)

private fun Todo18StateSnapshot.toJson() =
    JSONObject()
        .put("sequence", sequence)
        .put("channel", channel.name)
        .put("state", state.name)
        .put("owner", owner?.value.jsonValue())
        .put("selectedContentId", selectedContentId?.value.jsonValue())

private fun Todo18ExactEventObservation.toJson() =
    JSONObject()
        .put("ordinal", ordinal)
        .put("phase", phase.name)
        .put("matchingCount", matchingCount.jsonValue())
        .put("listenerCount", listenerCount.jsonValue())
        .put("sourceSequence", sourceSequence.jsonValue())

private fun Any?.jsonValue(): Any = this ?: JSONObject.NULL
