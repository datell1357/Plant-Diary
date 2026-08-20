import PlanteriorDesignSystem
import Testing

struct ComponentContractTests {
    @Test
    func cardVariantsResolveSystemSurfacesOnly() {
        #expect(PlanteriorCardVariant.standard.background == PlanteriorPalette.surface)
        #expect(PlanteriorCardVariant.standard.border == PlanteriorPalette.border)
        #expect(PlanteriorCardVariant.subtle.background == PlanteriorPalette.subtle)
        #expect(PlanteriorCardVariant.warning.background == PlanteriorPalette.warningSurface)
        #expect(PlanteriorCardVariant.warning.border == nil)
        #expect(PlanteriorCardVariant.success.background == PlanteriorPalette.successSurface)
        #expect(PlanteriorCardVariant.selected.background == PlanteriorPalette.accentSurface)
        #expect(PlanteriorCardVariant.selected.border == PlanteriorPalette.accent)
        #expect(PlanteriorCardVariant.disabled.background == PlanteriorPalette.subtle)
        #expect(PlanteriorCardVariant.disabled.foreground == PlanteriorPalette.textTertiary)
        #expect(PlanteriorCardVariant.standard.foreground == PlanteriorPalette.textPrimary)
    }

    @Test
    func statusPillVariantsExposeSemanticPairs() {
        #expect(PlanteriorStatusVariant.accent.background == PlanteriorPalette.accent)
        #expect(PlanteriorStatusVariant.accent.foreground == PlanteriorPalette.textOnAccent)
        #expect(PlanteriorStatusVariant.neutral.background == PlanteriorPalette.subtle)
        #expect(PlanteriorStatusVariant.neutral.foreground == PlanteriorPalette.textSecondary)
        #expect(PlanteriorStatusVariant.tonal.background == PlanteriorPalette.accentSurface)
        #expect(PlanteriorStatusVariant.tonal.foreground == PlanteriorPalette.accent)
        #expect(PlanteriorStatusVariant.warning.background == PlanteriorPalette.warningSurface)
        #expect(PlanteriorStatusVariant.warning.foreground == PlanteriorPalette.warning)
    }

    @Test
    func secondaryActionUsesOutlinedSurface() {
        #expect(PlanteriorActionStyle.primary.background == PlanteriorPalette.accent)
        #expect(PlanteriorActionStyle.primary.foreground == PlanteriorPalette.textOnAccent)
        #expect(PlanteriorActionStyle.primary.border == nil)
        #expect(PlanteriorActionStyle.secondary.background == PlanteriorPalette.surface)
        #expect(PlanteriorActionStyle.secondary.foreground == PlanteriorPalette.textPrimary)
        #expect(PlanteriorActionStyle.secondary.border == PlanteriorPalette.border)
        #expect(PlanteriorActionStyle.primary.height == PlanteriorControl.primaryButtonHeight)
        #expect(PlanteriorActionStyle.secondary.height == PlanteriorControl.primaryButtonHeight)
    }
}
