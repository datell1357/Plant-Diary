import PlanteriorDesignSystem
import SwiftUI
import Testing

struct DesignTokenTests {
    @Test
    func figmaPaletteIsFrozen() {
        #expect(PlanteriorPalette.canvas.hex == "#FCFBF7")
        #expect(PlanteriorPalette.surface.hex == "#FFFFFF")
        #expect(PlanteriorPalette.subtle.hex == "#EEF3F0")
        #expect(PlanteriorPalette.accentSurface.hex == "#EBF0EC")
        #expect(PlanteriorPalette.accent.hex == "#3D6642")
        #expect(PlanteriorPalette.textPrimary.hex == "#1F2937")
        #expect(PlanteriorPalette.textSecondary.hex == "#6B7280")
        #expect(PlanteriorPalette.textTertiary.hex == "#9CA3AF")
        #expect(PlanteriorPalette.border.hex == "#E5E7EB")
        #expect(PlanteriorPalette.warningSurface.hex == "#FFF7D6")
        #expect(PlanteriorPalette.warning.hex == "#E97800")
        #expect(PlanteriorPalette.successSurface.hex == "#EEF5EE")
        #expect(PlanteriorPalette.textOnAccent.hex == "#FFFFFF")
    }

    @Test
    func spacingScaleFollowsFourPointGrid() {
        #expect(PlanteriorSpacing.extraSmall == 4)
        #expect(PlanteriorSpacing.small == 8)
        #expect(PlanteriorSpacing.medium == 12)
        #expect(PlanteriorSpacing.large == 16)
        #expect(PlanteriorSpacing.extraLarge == 20)
        #expect(PlanteriorSpacing.huge == 24)
        #expect(PlanteriorSpacing.section == 32)
        #expect(PlanteriorSpacing.board == 40)
    }

    @Test
    func radiusScaleExcludesPresentationChrome() {
        #expect(PlanteriorRadius.small == 8)
        #expect(PlanteriorRadius.medium == 12)
        #expect(PlanteriorRadius.large == 16)
        #expect(PlanteriorRadius.extraLarge == 20)
        #expect(PlanteriorRadius.sheet == 24)
        #expect(PlanteriorRadius.full == 999)
    }

    @Test
    func controlGeometryMatchesFigmaReference() {
        #expect(PlanteriorControl.minimumTarget == 44)
        #expect(PlanteriorControl.cameraDiameter == 52)
        #expect(PlanteriorControl.primaryButtonHeight == 52)
        #expect(PlanteriorControl.navigationBarHeight == 56)
        #expect(PlanteriorControl.rowHeight == 56)
    }

    @Test
    func iconWellGeometryScalesForAccessibilityCategories() {
        #expect(PlanteriorControl.iconWellSize(for: .large) == 32)
        #expect(
            PlanteriorControl.iconWellSize(
                for: .accessibilityExtraExtraExtraLarge
            ) >= PlanteriorControl.minimumTarget
        )
    }

    @Test
    func typographyRolesBindToDynamicTypeStyles() {
        #expect(PlanteriorTypography.screenTitle == Font.headline.weight(.semibold))
        #expect(PlanteriorTypography.pageTitle == Font.title3.weight(.bold))
        #expect(PlanteriorTypography.heroGreeting == Font.title3.weight(.bold))
        #expect(PlanteriorTypography.sectionTitle == Font.headline.weight(.semibold))
        #expect(PlanteriorTypography.cardTitle == Font.subheadline.weight(.semibold))
        #expect(PlanteriorTypography.body == Font.body)
        #expect(PlanteriorTypography.supporting == Font.subheadline)
        #expect(PlanteriorTypography.caption == Font.caption)
        #expect(PlanteriorTypography.microLabel == Font.caption2.weight(.medium))
    }

    @Test
    func motionHonorsReduceMotion() {
        #expect(PlanteriorMotion.duration(reduceMotion: false) == 0.2)
        #expect(PlanteriorMotion.duration(reduceMotion: true) == 0)
    }
}
