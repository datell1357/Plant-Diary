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
        #expect(PlanteriorPalette.textAccessibleCaption.hex == "#6B7280")
        #expect(PlanteriorPalette.textTertiary.hex == "#9CA3AF")
        #expect(PlanteriorPalette.border.hex == "#E5E7EB")
        #expect(PlanteriorPalette.warningSurface.hex == "#FFF7D6")
        #expect(PlanteriorPalette.warning.hex == "#E97800")
        #expect(PlanteriorPalette.warningText.hex == "#8A4B00")
        #expect(PlanteriorPalette.successSurface.hex == "#EEF5EE")
        #expect(PlanteriorPalette.textOnAccent.hex == "#FFFFFF")
        #expect(PlanteriorPalette.homeWeatherWarningSurface.hex == "#FEF3C7")
        #expect(PlanteriorPalette.homeWeatherWarningBorder.hex == "#FCD34D")
        #expect(PlanteriorPalette.homeCareEmptySurface.hex == "#F5FAF5")
    }

    @Test
    func collectionAttentionAndFloatingActionDepthAreSemanticTokens() {
        #expect(PlanteriorPalette.attention.hex == "#FF4D4F")
        #expect(PlanteriorPalette.attentionText.hex == "#B42318")
        #expect(PlanteriorPalette.attentionSurface.hex == "#FFE8E8")
        #expect(PlanteriorShadow.floatingAction.color == PlanteriorPalette.textPrimary)
        #expect(PlanteriorShadow.floatingAction.opacity == 0.12)
        #expect(PlanteriorShadow.floatingAction.radius == 4)
        #expect(PlanteriorShadow.floatingAction.offsetX == 0)
        #expect(PlanteriorShadow.floatingAction.offsetY == 2)
        #expect(PlanteriorShadow.roomPlacement.opacity == 0.18)
        #expect(PlanteriorShadow.roomPlacement.radius == 2)
        #expect(PlanteriorShadow.roomPlacement.offsetX == 1)
        #expect(PlanteriorShadow.roomPlacement.offsetY == 3)
    }

    @Test
    func providerAndDestructiveColorsRemainSystemExact() {
        #expect(PlanteriorProviderPalette.appleButtonBackground == Color.black)
        #expect(PlanteriorProviderPalette.appleButtonForeground == Color.white)
        #expect(PlanteriorPalette.destructive == Color.red)
    }

    @Test
    func normalTextSemanticPairsMeetWcagAAContrast() {
        let semanticPairs = [
            (PlanteriorPalette.warningText, PlanteriorPalette.homeWeatherWarningSurface),
            (PlanteriorPalette.warningText, PlanteriorPalette.warningSurface),
            (PlanteriorPalette.warningText, PlanteriorPalette.surface),
            (PlanteriorPalette.warningText, PlanteriorPalette.canvas),
            (PlanteriorPalette.textAccessibleCaption, PlanteriorPalette.surface),
            (PlanteriorPalette.textAccessibleCaption, PlanteriorPalette.canvas),
            (PlanteriorPalette.textAccessibleCaption, PlanteriorPalette.homeCareEmptySurface),
            (PlanteriorPalette.attentionText, PlanteriorPalette.attentionSurface)
        ]

        for (foreground, background) in semanticPairs {
            #expect(contrastRatio(foreground, background) >= 4.5)
        }
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
    func sharedChromeGeometryMatchesFigmaReference() {
        #expect(PlanteriorLayout.contentGutter == 16)
        #expect(PlanteriorLayout.topBarHeight == 56)
        #expect(PlanteriorLayout.tabBarHeight == 62)
        #expect(PlanteriorLayout.bottomPanelContentHeight == 306)
        #expect(PlanteriorLayout.bottomPanelTotalHeight == 404)
        #expect(
            PlanteriorLayout.bottomPanelTotalHeight
                - PlanteriorLayout.bottomPanelContentHeight == 98
        )
        #expect(PlanteriorLayout.modalWidth == 320)
        #expect(abs(PlanteriorLayout.heroAspectRatio - CGFloat(5.0 / 3.0)) < 0.0001)
        #expect(abs(PlanteriorLayout.mediaAspectRatio - CGFloat(4.0 / 3.0)) < 0.0001)
        #expect(PlanteriorLayout.mediaThumbnailSize == 48)
        #expect(PlanteriorLayout.floatingActionSize == 56)
        #expect(PlanteriorLayout.floatingActionInset == 16)
    }

    @Test
    func sharedTabSurfaceUsesCanonicalComponentGeometry() {
        let referenceCanvasHeight: CGFloat = 874
        let systemBottomSafeArea: CGFloat = 34
        let expectedSurfaceMinimumY: CGFloat = 778
        let actualSurfaceMinimumY = referenceCanvasHeight
            - systemBottomSafeArea
            - PlanteriorLayout.tabBarHeight

        #expect(actualSurfaceMinimumY == expectedSurfaceMinimumY)
        #expect(PlanteriorControl.minimumTarget >= 44)
        #expect(PlanteriorControl.cameraDiameter == 52)
    }

    @Test
    func iconWellUsesReferenceMaterialAndGeometry() {
        #expect(PlanteriorPalette.iconWellSurface == PlanteriorPalette.canvas)
        #expect(PlanteriorControl.iconWellCornerRadius == PlanteriorRadius.small)
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

private func contrastRatio(
    _ foreground: PlanteriorColorToken,
    _ background: PlanteriorColorToken
) -> Double {
    let foregroundLuminance = relativeLuminance(foreground)
    let backgroundLuminance = relativeLuminance(background)
    return (max(foregroundLuminance, backgroundLuminance) + 0.05)
        / (min(foregroundLuminance, backgroundLuminance) + 0.05)
}

private func relativeLuminance(_ token: PlanteriorColorToken) -> Double {
    let value = UInt64(token.hex.dropFirst(), radix: 16) ?? 0
    let channels = [
        Double((value >> 16) & 0xFF) / 255,
        Double((value >> 8) & 0xFF) / 255,
        Double(value & 0xFF) / 255
    ]
    let linear = channels.map { channel in
        channel <= 0.04045
            ? channel / 12.92
            : pow((channel + 0.055) / 1.055, 2.4)
    }
    return (0.2126 * linear[0]) + (0.7152 * linear[1]) + (0.0722 * linear[2])
}
