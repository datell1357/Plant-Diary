package com.planterior.helper.action

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionEntryBindingSourceContractTest {
    private val root = repositoryRoot()

    @Test
    fun `production routes bind exact controller actions without dispatcher replacement`() {
        val miniHomeRoute =
            root
                .resolve(
                    "feature/minihome/src/main/kotlin/com/planterior/helper/feature/minihome/MiniHomeRoute.kt"
                )
                .readText()
        val wateringRoute =
            root
                .resolve(
                    "feature/watering/src/main/kotlin/com/planterior/helper/feature/watering/WateringRoute.kt"
                )
                .readText()

        assertTrue(miniHomeRoute.contains("onSave = controller::save"))
        assertTrue(
            wateringRoute.contains(
                "scope.launch { runWateringConfirmationAction(controller, publishCompleted) }"
            )
        )
    }

    @Test
    fun `host integration compiles the exact AndroidTest fixtures`() {
        listOf(
                "Todo18Scenario.kt",
                "Todo18MiniHomeRepositoryFixture.kt",
                "Todo18PlantRepositoryFixture.kt",
            )
            .forEach { fileName ->
                val shared =
                    root.resolve("app/src/test/kotlin/com/planterior/helper").resolve(fileName)
                val androidTest =
                    root
                        .resolve("app/src/androidTest/kotlin/com/planterior/helper")
                        .resolve(fileName)
                assertTrue(Files.isSymbolicLink(shared))
                assertEquals(androidTest.toRealPath(), shared.toRealPath())
            }
    }

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("repository root not found")
        }
        return current
    }
}
