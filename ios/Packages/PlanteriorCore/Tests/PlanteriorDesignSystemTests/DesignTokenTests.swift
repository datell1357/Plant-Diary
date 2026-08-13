import PlanteriorDesignSystem
import Testing

struct DesignTokenTests {
    @Test
    func figmaPaletteAndGeometryAreFrozen() {
        #expect(PlanteriorPalette.canvas.hex == "#FCFBF7")
        #expect(PlanteriorPalette.surface.hex == "#FFFFFF")
        #expect(PlanteriorPalette.accent.hex == "#3D6642")
        #expect(PlanteriorPalette.subtle.hex == "#EEF3F0")
        #expect(PlanteriorPalette.textOnAccent.hex == "#FFFFFF")
        #expect(PlanteriorRadius.small == 8)
        #expect(PlanteriorRadius.medium == 12)
        #expect(PlanteriorRadius.large == 16)
        #expect(PlanteriorControl.minimumTarget == 44)
        #expect(PlanteriorControl.cameraDiameter == 52)
    }

    @Test
    func motionHonorsReduceMotion() {
        #expect(PlanteriorMotion.duration(reduceMotion: false) == 0.2)
        #expect(PlanteriorMotion.duration(reduceMotion: true) == 0)
    }
}
