package com.planterior.helper.feature.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugArtifactBoundaryTest {
    @Test
    fun `debug fake provider lives outside main and release source sets`() {
        val debugClass =
            java.io.File(
                "src/debug/kotlin/com/planterior/helper/feature/auth/DebugAuthHarnessActivity.kt"
            )
        val mainTree =
            java.io
                .File("src/main")
                .walkTopDown()
                .filter { it.isFile }
                .joinToString("\n") { it.readText() }

        assertTrue(debugClass.isFile)
        assertTrue(debugClass.readText().contains("DebugProvider"))
        assertFalse(mainTree.contains("DebugProvider"))
        assertFalse(mainTree.contains("debug-account-a"))
    }
}
