package com.planterior.helper

import android.content.Context
import com.planterior.helper.diagnostic.Todo18DiagnosticProvenance
import com.planterior.helper.minihome.Todo18MiniHomeOwnerOperationDiagnosticRecorder
import com.planterior.helper.minihome.putTodo18MiniHomeBoundaryDiagnosticEvents
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runners.model.Statement

internal fun todo18RuntimeDiagnosticStatement(
    before: () -> Unit,
    body: () -> Unit,
    after: () -> Unit,
    finish: (Throwable?) -> IllegalStateException?,
): Statement =
    object : Statement() {
        override fun evaluate() {
            preserveTodo18PrimaryFailure(
                block = {
                    before()
                    preserveTodo18PrimaryFailure(
                        block = body,
                        finish = {
                            try {
                                after()
                                null
                            } catch (failure: Exception) {
                                IllegalStateException("Todo18 runtime cleanup failed", failure)
                            }
                        },
                    )
                },
                finish = finish,
            )
        }
    }

internal fun writeTodo18MiniHomeBoundarySnapshot(
    application: Context,
    recorder: Todo18MiniHomeOwnerOperationDiagnosticRecorder,
    capturedProvenance: Todo18DiagnosticProvenance?,
    methodName: String,
    primaryFailure: Throwable?,
): IllegalStateException? {
    return try {
        val directory = requireNotNull(application.getExternalFilesDir("todo18-e2e-journeys"))
        check(directory.exists() || directory.mkdirs())
        val provenance = requireNotNull(capturedProvenance)
        val safeName = methodName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(directory, "$safeName-minihome-boundaries-runtime.json")
            .writeText(
                buildJsonObject {
                    put("schema", "todo18-minihome-boundaries-v1")
                    put("testMethod", methodName)
                    put("purpose", "forensic-only-not-acceptance")
                    put("testFailed", primaryFailure != null)
                    put("testFailureClass", primaryFailure?.javaClass?.name)
                    put("capturedAtEpochMillis", System.currentTimeMillis())
                    put("bindingValidated", provenance.bindingValidated)
                    put("expectedSourceSha256", provenance.expectedSourceSha256)
                    put("embeddedSourceSha256", provenance.embeddedSourceSha256)
                    put("expectedAppApkSha256", provenance.expectedAppApkSha256)
                    put("observedAppApkSha256", provenance.observedAppApkSha256)
                    put("expectedAndroidTestApkSha256", provenance.expectedAndroidTestApkSha256)
                    put("observedAndroidTestApkSha256", provenance.observedAndroidTestApkSha256)
                    putTodo18MiniHomeBoundaryDiagnosticEvents(recorder)
                }
                    .toString()
            )
        null
    } catch (failure: Exception) {
        IllegalStateException("MiniHome boundary receipt could not be written", failure)
    }
}
