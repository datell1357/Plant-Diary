package com.planterior.helper

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMediaBrokerSourceContractTest {
    @Test
    fun `production private media writers use broker and never Firebase Storage upload SDK`() {
        val root = repositoryRoot()
        val productionRoots =
            listOf(root.resolve("app/src"), root.resolve("feature"), root.resolve("core"))
        val production = productionRoots.flatMap { directory ->
            Files.walk(directory).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.extension == "kt" }
                    .filter { path ->
                        val value = path.toString()
                        !value.contains("/test/") && !value.contains("/androidTest/")
                    }
                    .toList()
            }
        }
        val merged = production.joinToString("\n") { it.readText() }
        assertFalse(merged.contains(".putBytes("))
        assertFalse(merged.contains(".putFile("))
        assertFalse(merged.contains(".putStream("))

        val identification =
            root
                .resolve(
                    "app/src/release/kotlin/com/planterior/helper/identify/PhotoIdentificationHandoffFactory.kt"
                )
                .readText()
        assertTrue(identification.contains("FirebasePrivateMediaGateway"))
        assertTrue(identification.contains("PrivateMediaKind.IDENTIFICATION_ORIGINAL"))
        assertTrue(identification.contains("idempotencyKey = original.requestId"))
        assertTrue(identification.contains("getHttpsCallable(\"createIdentificationRequest\")"))
        assertTrue(identification.contains("\"requestId\" to requestId"))
        assertTrue(identification.contains("\"mediaReference\" to mediaReference.wireValue()"))
        assertTrue(identification.contains("\"disclosureVersion\" to disclosureVersion"))
        assertFalse(identification.contains("FirebaseFirestore"))
        assertFalse(identification.contains(".runTransaction"))
        assertFalse(identification.contains("identificationRequests"))
        assertFalse(identification.contains("StorageContract.identificationOriginal"))

        val handoff =
            root
                .resolve(
                    "app/src/main/kotlin/com/planterior/helper/identify/ApprovedPhotoIdentificationHandoff.kt"
                )
                .readText()
        assertTrue(handoff.contains("upload(submission.original(owner, requestId, bytes))"))
        assertTrue(handoff.contains("authorize(owner, requestId, mediaReference)"))
        assertTrue(handoff.contains("backend.authorizeRequest("))
        assertTrue(handoff.contains("requestId.value"))
        assertTrue(
            handoff.indexOf("upload(submission.original(owner, requestId, bytes))") <
                handoff.indexOf("authorize(owner, requestId, mediaReference)")
        )

        val broker =
            listOf("PrivateMediaGateway.kt", "PrivateMediaHttpTransport.kt").joinToString("\n") {
                root
                    .resolve("core/data/src/main/kotlin/com/planterior/helper/core/data/$it")
                    .readText()
            }
        assertTrue(broker.contains("\"reservePrivateMediaUpload\""))
        assertTrue(broker.contains("\"content-length\" to request.bytes.size.toString()"))
        assertTrue(broker.contains("\"x-goog-if-generation-match\" to \"0\""))
        assertTrue(broker.contains("requestMethod = \"PUT\""))
        assertTrue(broker.contains("setFixedLengthStreamingMode(bytes.size)"))
        assertTrue(broker.contains("\"commitPrivateMediaReservation\""))
        assertTrue(
            broker.indexOf("val reserved = reserve(request)") < broker.indexOf("putTransport.put")
        )
        assertTrue(broker.indexOf("putTransport.put") < broker.indexOf("return commit(request"))

        val registration =
            root
                .resolve(
                    "feature/registration/src/main/kotlin/com/planterior/helper/feature/registration/FirebaseRegistrationRepository.kt"
                )
                .readText()
        assertTrue(registration.contains("PrivateMediaKind.PLANT_PHOTO"))
        assertTrue(registration.contains("\"representativeMediaReference\""))
        assertFalse(registration.contains("StorageContract.representativePhoto"))
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Repository root unavailable")
        }
        return current
    }
}
