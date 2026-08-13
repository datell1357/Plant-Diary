package com.planterior.helper.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.1 명도 대비를 토큰 ARGB 값에서 직접 계산해 고정한다.
 *
 * 스크린샷 승인이나 문구가 아니라 실제 토큰 값만 근거로 삼기 때문에, 토큰을 되돌리면 이 테스트가 즉시 깨진다. 기준은 WCAG 2.1 SC 1.4.3이며 본문
 * 크기(`bodyMedium` 13sp)는 large text 예외에 해당하지 않아 4.5:1을 그대로 요구한다.
 */
class PlanteriorContrastTest {
    @Test
    fun `weather risk body text meets the normal text contrast minimum`() {
        assertContrastAtLeast(
            label = "onWarningContainer/WarningContainer",
            foreground = PlanteriorColorScheme.onErrorContainer,
            background = PlanteriorColorScheme.errorContainer,
            minimum = NORMAL_TEXT_MINIMUM,
        )
    }

    @Test
    fun `weather risk body text stays legible over the warning border`() {
        assertContrastAtLeast(
            label = "onWarningContainer/WarningBorder",
            foreground = PlanteriorPalette.OnWarningContainer,
            background = PlanteriorPalette.WarningBorder,
            minimum = NORMAL_TEXT_MINIMUM,
        )
    }

    @Test
    fun `body text tokens on the app surfaces meet the normal text contrast minimum`() {
        assertContrastAtLeast(
            label = "onBackground/background",
            foreground = PlanteriorColorScheme.onBackground,
            background = PlanteriorColorScheme.background,
            minimum = NORMAL_TEXT_MINIMUM,
        )
        assertContrastAtLeast(
            label = "onSurface/surface",
            foreground = PlanteriorColorScheme.onSurface,
            background = PlanteriorColorScheme.surface,
            minimum = NORMAL_TEXT_MINIMUM,
        )
        assertContrastAtLeast(
            label = "onSurfaceVariant/background",
            foreground = PlanteriorColorScheme.onSurfaceVariant,
            background = PlanteriorColorScheme.background,
            minimum = NORMAL_TEXT_MINIMUM,
        )
        assertContrastAtLeast(
            label = "onPrimary/primary",
            foreground = PlanteriorColorScheme.onPrimary,
            background = PlanteriorColorScheme.primary,
            minimum = NORMAL_TEXT_MINIMUM,
        )
    }

    private fun assertContrastAtLeast(
        label: String,
        foreground: Color,
        background: Color,
        minimum: Double,
    ) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$label 대비는 ${"%.6f".format(ratio)}:1 이며 최소 $minimum:1 을 만족하지 못한다",
            ratio >= minimum,
        )
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val first = relativeLuminance(foreground)
        val second = relativeLuminance(background)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        val argb = color.value shr 32
        val red = channel((argb shr 16 and 0xFFu).toInt())
        val green = channel((argb shr 8 and 0xFFu).toInt())
        val blue = channel((argb and 0xFFu).toInt())
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun channel(value: Int): Double {
        val normalized = value / 255.0
        return if (normalized <= 0.03928) {
            normalized / 12.92
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4)
        }
    }

    private companion object {
        /** WCAG 2.1 SC 1.4.3 normal text 최소 대비. */
        const val NORMAL_TEXT_MINIMUM: Double = 4.5
    }
}
