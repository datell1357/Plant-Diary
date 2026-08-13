package com.planterior.helper.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Figma Dev Mode 검사 값과 토큰이 어긋나지 않도록 고정한다.
 *
 * 기대값 출처는 `.omo/evidence/task-2-visual-qa/figma-inspection.md`에 정리해 두었다.
 */
class PlanteriorDesignTokenTest {
    @Test
    fun `color tokens match the Figma inspection values`() {
        assertEquals(Color(0xFFFCFBF7), PlanteriorPalette.Background)
        assertEquals(Color(0xFFFFFFFF), PlanteriorPalette.Surface)
        assertEquals(Color(0xFF3D6642), PlanteriorPalette.Primary)
        assertEquals(Color(0xFFFFFFFF), PlanteriorPalette.OnPrimary)
        assertEquals(Color(0xFFEEF3F0), PlanteriorPalette.PrimaryContainer)
        assertEquals(Color(0xFF1F2937), PlanteriorPalette.TextPrimary)
        assertEquals(Color(0xFF6B7280), PlanteriorPalette.TextSecondary)
        assertEquals(Color(0xFF9CA3AF), PlanteriorPalette.TextTertiary)
        assertEquals(Color(0xFFE5E7EB), PlanteriorPalette.Border)
        assertEquals(Color(0xFFD97706), PlanteriorPalette.Warning)
        assertEquals(Color(0xFFFEF3C7), PlanteriorPalette.WarningContainer)
        assertEquals(Color(0xFFFDE68A), PlanteriorPalette.WarningBorder)
    }

    @Test
    fun `material color scheme is wired to the Figma palette`() {
        assertEquals(PlanteriorPalette.Primary, PlanteriorColorScheme.primary)
        assertEquals(PlanteriorPalette.OnPrimary, PlanteriorColorScheme.onPrimary)
        assertEquals(PlanteriorPalette.Background, PlanteriorColorScheme.background)
        assertEquals(PlanteriorPalette.Surface, PlanteriorColorScheme.surface)
        assertEquals(PlanteriorPalette.TextPrimary, PlanteriorColorScheme.onBackground)
        assertEquals(PlanteriorPalette.TextPrimary, PlanteriorColorScheme.onSurface)
        assertEquals(PlanteriorPalette.TextSecondary, PlanteriorColorScheme.onSurfaceVariant)
        assertEquals(PlanteriorPalette.Border, PlanteriorColorScheme.outline)
        assertEquals(PlanteriorPalette.Warning, PlanteriorColorScheme.error)
    }

    @Test
    fun `radius and border tokens match the Figma frame`() {
        assertEquals(8.dp, PlanteriorRadius.Small)
        assertEquals(12.dp, PlanteriorRadius.Card)
        assertEquals(16.dp, PlanteriorRadius.Medium)
        assertEquals(48.dp, PlanteriorRadius.Large)
        assertEquals(1.dp, PlanteriorBorderWidth)
    }

    @Test
    fun `spacing scale stays on the four dp grid used by the tab bar`() {
        val spacing = PlanteriorSpacing()
        assertEquals(2.dp, spacing.extraSmall)
        assertEquals(4.dp, spacing.small)
        assertEquals(8.dp, spacing.medium)
        assertEquals(12.dp, spacing.large)
        assertEquals(16.dp, spacing.extraLarge)
        assertEquals(24.dp, spacing.huge)
    }

    @Test
    fun `typography matches the Figma text layer inspection`() {
        assertEquals(17.sp, PlanteriorTypography.titleLarge.fontSize)
        assertEquals(21.sp, PlanteriorTypography.titleLarge.lineHeight)
        assertEquals(16.sp, PlanteriorTypography.titleMedium.fontSize)
        assertEquals(19.sp, PlanteriorTypography.titleMedium.lineHeight)
        assertEquals(14.sp, PlanteriorTypography.bodyLarge.fontSize)
        assertEquals(17.sp, PlanteriorTypography.bodyLarge.lineHeight)
        assertEquals(13.sp, PlanteriorTypography.bodyMedium.fontSize)
        assertEquals(16.sp, PlanteriorTypography.bodyMedium.lineHeight)
        assertEquals(10.sp, PlanteriorTypography.labelSmall.fontSize)
        assertEquals(12.sp, PlanteriorTypography.labelSmall.lineHeight)
    }
}
