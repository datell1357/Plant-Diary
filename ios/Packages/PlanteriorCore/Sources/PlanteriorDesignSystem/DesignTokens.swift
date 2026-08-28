import SwiftUI

public struct PlanteriorColorToken: Hashable, Sendable {
    public let hex: String

    public init(hex: String) {
        self.hex = hex
    }

    public var color: Color {
        let value = UInt64(hex.dropFirst(), radix: 16) ?? 0
        return Color(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
}

/// Frozen Figma palette (`초보 식집사`, Page 1). Add a semantic role here before
/// introducing any new visual color.
public enum PlanteriorPalette {
    public static let canvas = PlanteriorColorToken(hex: "#FCFBF7")
    public static let surface = PlanteriorColorToken(hex: "#FFFFFF")
    public static let subtle = PlanteriorColorToken(hex: "#EEF3F0")
    public static let accentSurface = PlanteriorColorToken(hex: "#EBF0EC")
    public static let iconWellSurface = canvas
    public static let accent = PlanteriorColorToken(hex: "#3D6642")
    public static let textPrimary = PlanteriorColorToken(hex: "#1F2937")
    public static let textSecondary = PlanteriorColorToken(hex: "#6B7280")
    public static let textAccessibleCaption = PlanteriorColorToken(hex: "#6B7280")
    public static let textTertiary = PlanteriorColorToken(hex: "#9CA3AF")
    public static let textOnAccent = PlanteriorColorToken(hex: "#FFFFFF")
    public static let border = PlanteriorColorToken(hex: "#E5E7EB")
    public static let warningSurface = PlanteriorColorToken(hex: "#FFF7D6")
    public static let warning = PlanteriorColorToken(hex: "#E97800")
    public static let warningText = PlanteriorColorToken(hex: "#8A4B00")
    public static let successSurface = PlanteriorColorToken(hex: "#EEF5EE")
    public static let collectionStatus = PlanteriorColorToken(hex: "#10B981")
    public static let homeWeatherWarningSurface = PlanteriorColorToken(hex: "#FEF3C7")
    public static let homeWeatherWarningBorder = PlanteriorColorToken(hex: "#FCD34D")
    public static let homeCareEmptySurface = PlanteriorColorToken(hex: "#F5FAF5")
    public static let attention = PlanteriorColorToken(hex: "#FF4D4F")
    public static let attentionText = PlanteriorColorToken(hex: "#B42318")
    public static let attentionSurface = PlanteriorColorToken(hex: "#FFE8E8")
    public static let mediaScrim = PlanteriorColorToken(hex: "#000000")
    public static let destructive = Color.red
}

/// Provider-owned colors are exact system primitives and stay at auth boundaries.
public enum PlanteriorProviderPalette {
    public static let appleButtonBackground = Color.black
    public static let appleButtonForeground = Color.white
}

public enum PlanteriorSpacing {
    public static let none: CGFloat = 0
    public static let extraSmall: CGFloat = 4
    public static let small: CGFloat = 8
    public static let medium: CGFloat = 12
    public static let large: CGFloat = 16
    public static let extraLarge: CGFloat = 20
    public static let huge: CGFloat = 24
    public static let section: CGFloat = 32
    public static let board: CGFloat = 40
}

public enum PlanteriorLayout {
    public static let contentGutter: CGFloat = 16
    public static let topBarHeight: CGFloat = 56
    /// App-owned surface above the native bottom safe area; separator y=778 at 402x874.
    public static let tabBarHeight: CGFloat = 62
    public static let bottomPanelContentHeight: CGFloat = 306
    public static let bottomPanelTotalHeight: CGFloat = 404
    public static let modalWidth: CGFloat = 320
    public static let heroAspectRatio: CGFloat = 5.0 / 3.0
    public static let mediaAspectRatio: CGFloat = 4.0 / 3.0
    public static let mediaThumbnailSize: CGFloat = 48
    public static let floatingActionSize: CGFloat = 56
    public static let floatingActionInset: CGFloat = 16
}

public enum PlanteriorRadius {
    public static let small: CGFloat = 8
    public static let medium: CGFloat = 12
    public static let large: CGFloat = 16
    public static let extraLarge: CGFloat = 20
    public static let sheet: CGFloat = 24
    public static let full: CGFloat = 999
}

public enum PlanteriorControl {
    public static let minimumTarget: CGFloat = 44
    public static let cameraDiameter: CGFloat = 52
    public static let primaryButtonHeight: CGFloat = 52
    public static let navigationBarHeight = PlanteriorLayout.topBarHeight
    public static let rowHeight: CGFloat = 56
    public static let iconWellSize: CGFloat = 32
    public static let iconWellCornerRadius = PlanteriorRadius.small
    public static let compactVisualSize: CGFloat = 40
    public static let secondaryButtonHeight: CGFloat = 48
    public static let hairline: CGFloat = 1

    public static func iconWellSize(
        for sizeCategory: ContentSizeCategory
    ) -> CGFloat {
        sizeCategory.isAccessibilityCategory ? minimumTarget : iconWellSize
    }
}

public enum PlanteriorOpacity {
    public static let dimmer: Double = 0.4
    public static let mediaBadge: Double = 0.72
    public static let disabled: Double = 0.55
    public static let pressed: Double = 0.7
    public static let strong: Double = 0.9
    public static let medium: Double = 0.5
    public static let faint: Double = 0.24
}

public struct PlanteriorShadowToken: Hashable, Sendable {
    public let color: PlanteriorColorToken
    public let opacity: Double
    public let radius: CGFloat
    public let offsetX: CGFloat
    public let offsetY: CGFloat

    public init(
        color: PlanteriorColorToken,
        opacity: Double,
        radius: CGFloat,
        offsetX: CGFloat,
        offsetY: CGFloat
    ) {
        self.color = color
        self.opacity = opacity
        self.radius = radius
        self.offsetX = offsetX
        self.offsetY = offsetY
    }
}

public enum PlanteriorShadow {
    public static let floatingAction = PlanteriorShadowToken(
        color: PlanteriorPalette.textPrimary,
        opacity: 0.12,
        radius: 4,
        offsetX: 0,
        offsetY: 2
    )
    public static let roomPlacement = PlanteriorShadowToken(
        color: PlanteriorPalette.mediaScrim,
        opacity: 0.18,
        radius: 2,
        offsetX: 1,
        offsetY: 3
    )
}

public enum PlanteriorMotion {
    public static func duration(reduceMotion: Bool) -> Double {
        reduceMotion ? 0 : 0.2
    }

    public static func standard(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .easeInOut(duration: duration(reduceMotion: false))
    }
}
