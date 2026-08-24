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
        assertTrue(
            identification.contains("\"mediaReference\" to request.mediaReference.wireValue()")
        )
        assertFalse(identification.contains("StorageContract.identificationOriginal"))

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
