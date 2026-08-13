package com.planterior.helper.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 홈 QA 시나리오가 출시 아티팩트로 새지 않는지 소스 세트 경계로 고정한다.
 *
 * 릴리스 빌드는 서명 자격 증명이 있어야 만들 수 있어 CI 밖에서는 APK를 열어볼 수 없다. 그래서 시나리오 코드가 debug 소스 세트에만 존재하고 main·release
 * 어디에도 없다는 사실을 직접 검사한다.
 */
class HomeDebugBoundaryTest {
    private val debugSource =
        File("src/debug/kotlin/com/planterior/helper/home/DebugHomeControls.kt")
    private val releaseSource =
        File("src/release/kotlin/com/planterior/helper/home/DebugHomeControls.kt")

    @Test
    fun `qa scenarios exist only in the debug source set`() {
        assertTrue("debug 구현이 있어야 한다", debugSource.isFile)
        assertTrue("release 대체 구현이 있어야 한다", releaseSource.isFile)

        val debugText = debugSource.readText()
        assertTrue(debugText.contains("weather-failure"))
        assertTrue(debugText.contains("weather-risk"))
        assertTrue(debugText.contains("session-signed-in"))

        val releaseText = releaseSource.readText()
        SCENARIO_MARKERS.forEach { marker ->
            assertFalse("release 소스에 '$marker'가 있으면 안 된다", releaseText.contains(marker))
        }
    }

    @Test
    fun `no qa scenario marker leaks into the shared main source set`() {
        val mainText =
            File("src/main").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }

        SCENARIO_MARKERS.forEach { marker ->
            assertFalse("main 소스에 '$marker'가 있으면 안 된다", mainText.contains(marker))
        }
    }

    private companion object {
        /** 디버그 전용 QA 시나리오 식별자와 저장 위치. 하나라도 출시 경로에 있으면 실패한다. */
        val SCENARIO_MARKERS =
            listOf(
                "weather-failure",
                "weather-risk",
                "weather-ok",
                "home-qa",
                "setDebugHomeScenario",
                "session-signed-in",
                "session-logged-out",
                "session-restoring",
                "setDebugHomeSession",
            )
    }
}
