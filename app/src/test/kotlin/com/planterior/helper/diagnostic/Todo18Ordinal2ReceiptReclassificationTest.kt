package com.planterior.helper.diagnostic

import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.INVALID_CAPTURE
import com.planterior.helper.diagnostic.Todo18DiagnosticClassification.ROUTE_STATE_NOT_OBSERVED
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.valid
import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.withKinds
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
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18Ordinal2ReceiptReclassificationTest {
    @Test
    fun `byte-identical ordinal two copies all classify as route state not observed`() {
        RECEIPT_HASHES.forEach { (name, expectedHash) ->
            val bytes = Files.readAllBytes(fixtureReceiptPath(name))
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

            assertEquals(name, expectedHash, bytes.sha256())
            assertEquals(
                name,
                ROUTE_STATE_NOT_OBSERVED,
                Todo18DiagnosticReducer.classify(json.receipt()),
            )
        }
    }

    @Test
    fun `immutable offline non-target receives classify as route state not observed`() {
        val bytes = Files.readAllBytes(fixtureReceiptPath(OFFLINE_RECEIPT))
        val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val pipelineKinds =
            json.getValue("pipeline").jsonArray.map {
                Todo18PipelineEventKind.valueOf(it.jsonObject.string("kind"))
            }
        val dispatches = json.getValue("stateDispatches").jsonArray.map { it.jsonObject }

        assertEquals(OFFLINE_SHA256, bytes.sha256())
        assertEquals(
            listOf(
                Todo18PipelineEventKind.FRAMEWORK_ACTION_BEGIN,
                Todo18PipelineEventKind.FRAMEWORK_ACTION_RETURN,
                Todo18PipelineEventKind.SUBSCRIPTION_RECEIVE,
                Todo18PipelineEventKind.PREDICATE_FALSE,
                Todo18PipelineEventKind.EVENT_REJECTED,
                Todo18PipelineEventKind.AWAIT_FAILURE,
                Todo18PipelineEventKind.DETACH,
                Todo18PipelineEventKind.DRAIN,
            ),
            pipelineKinds,
        )
        assertEquals(8, dispatches.size)
        assertTrue(dispatches.all { it.string("state") == "MINI_HOME_LOADING" })
        dispatches
            .groupBy { it.long("sourceSequence") }
            .values
            .forEach { pair ->
                assertEquals(setOf("BEGIN", "RETURN"), pair.map { it.string("phase") }.toSet())
            }
        assertEquals(ROUTE_STATE_NOT_OBSERVED, Todo18DiagnosticReducer.classify(json.receipt()))
    }

    @Test
    fun `target primary begin without return or failure remains invalid`() {
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
                    Todo18PipelineEventKind.AWAIT_FAILURE,
                    Todo18PipelineEventKind.DETACH,
                    Todo18PipelineEventKind.DRAIN,
                )

        assertEquals(INVALID_CAPTURE, Todo18DiagnosticReducer.classify(receipt))
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

    private fun fixtureReceiptPath(name: String): Path =
        repositoryRoot().resolve("app/src/test/resources/todo18/ordinal-2/$name")

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
        const val CONFLICT_RECEIPT = "conflict_begin_edit-diagnostic.json"
        const val OFFLINE_RECEIPT = "offline_initial_viewing-diagnostic.json"
        const val REGISTRATION_RECEIPT = "registration_select_content-diagnostic.json"
        const val CONFLICT_SHA256 =
            "3272a9254767cbae967709b8224ac98268d5cc7f9447daabf1b3a632972bcc4a"
        const val OFFLINE_SHA256 =
            "8e539c7ee29abca6460c9f64c061afb419175dd923491778ca850c8bd976f050"
        const val REGISTRATION_SHA256 =
            "4b5269afe4aab1ac25d6a0e8b3b6d9dcd201cd270ff939eb7528b751a0fc5db4"
        val RECEIPT_HASHES =
            linkedMapOf(
                CONFLICT_RECEIPT to CONFLICT_SHA256,
                OFFLINE_RECEIPT to OFFLINE_SHA256,
                REGISTRATION_RECEIPT to REGISTRATION_SHA256,
            )
    }
}
