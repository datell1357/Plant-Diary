package com.planterior.helper.diagnostic

import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.TASK1_PUBLICATION_MISSING
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

class Todo18Ordinal4ReceiptReclassificationTest {
    @Test
    fun `exact immutable ordinal four receipt remains Task1 publication missing`() {
        val bytes = Files.readAllBytes(fixturePath())
        val receipt = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.receipt()

        assertEquals(RECEIPT_SHA256, bytes.sha256())
        assertEquals(TASK1_PUBLICATION_MISSING, Todo18DiagnosticReducer.classify(receipt))
    }

    private fun JsonObject.receipt(): Todo18DiagnosticReceipt {
        val envelope = getValue("envelope").jsonObject
        return Todo18DiagnosticReceipt(
            envelope =
                Todo18DiagnosticEnvelope(
                    schema = envelope.stringOrNull("schema"),
                    waitId = envelope.stringOrNull("waitId")?.let(Todo18WaitId::valueOf),
                    expectedSourceSha256 = envelope.stringOrNull("expectedSourceSha256"),
                    embeddedSourceSha256 = envelope.stringOrNull("embeddedSourceSha256"),
                    expectedAppApkSha256 = envelope.stringOrNull("expectedAppApkSha256"),
                    observedAppApkSha256 = envelope.stringOrNull("observedAppApkSha256"),
                    expectedAndroidTestApkSha256 =
                        envelope.stringOrNull("expectedAndroidTestApkSha256"),
                    observedAndroidTestApkSha256 =
                        envelope.stringOrNull("observedAndroidTestApkSha256"),
                    bindingValidated = envelope.boolean("bindingValidated"),
                    installedSinkIdentity = envelope.stringOrNull("installedSinkIdentity"),
                    runtimeSinkIdentity = envelope.stringOrNull("runtimeSinkIdentity"),
                    activitySinkIdentity = envelope.stringOrNull("activitySinkIdentity"),
                    freshSink = envelope.boolean("freshSink"),
                    initialSequence = envelope.longOrNull("initialSequence"),
                    initialCurrentsEmpty = envelope.boolean("initialCurrentsEmpty"),
                    initialListenerCount = envelope.int("initialListenerCount"),
                    priorActivityCount = envelope.int("priorActivityCount"),
                    priorOverridePresent = envelope.boolean("priorOverridePresent"),
                    overrideInstalledAtCapture = envelope.boolean("overrideInstalledAtCapture"),
                    activityCreateCount = envelope.int("activityCreateCount"),
                    activityDestroyCount = envelope.int("activityDestroyCount"),
                    activityActiveCount = envelope.int("activityActiveCount"),
                    previousTeardownComplete = envelope.boolean("previousTeardownComplete"),
                    captureFinalized = envelope.boolean("captureFinalized"),
                    detached = envelope.boolean("detached"),
                    drained = envelope.boolean("drained"),
                    finalListenerCount = envelope.int("finalListenerCount"),
                    diagnosticFailures =
                        envelope.getValue("diagnosticFailures").jsonArray.map {
                            Todo18DiagnosticFailure.valueOf(it.jsonPrimitive.content)
                        },
                ),
            pipeline =
                getValue("pipeline").jsonArray.map { element ->
                    val event = element.jsonObject
                    Todo18PipelineEvent(
                        ordinal = event.long("ordinal"),
                        kind = Todo18PipelineEventKind.valueOf(event.string("kind")),
                        sourceSequence = event.longOrNull("sourceSequence"),
                        controllerIdentity = event.int("controllerIdentity"),
                    )
                },
        )
    }

    private fun fixturePath(): Path =
        repositoryRoot().resolve("app/src/test/resources/todo18/ordinal-4/$RECEIPT")

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.stringOrNull(name: String): String? =
        getValue(name).jsonPrimitive.contentOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        getValue(name).jsonPrimitive.booleanOrNull

    private fun JsonObject.int(name: String): Int? = getValue(name).jsonPrimitive.intOrNull

    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long

    private fun JsonObject.longOrNull(name: String): Long? = getValue(name).jsonPrimitive.longOrNull

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        const val RECEIPT = "offline_begin_edit-diagnostic.json"
        const val RECEIPT_SHA256 =
            "55b7b6bf744e40d810495327e422115720c894a5e3c6f87b827aabbacece57a4"
    }
}
