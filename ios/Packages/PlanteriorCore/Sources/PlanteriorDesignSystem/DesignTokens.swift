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
    public static let accent = PlanteriorColorToken(hex: "#3D6642")
    public static let textPrimary = PlanteriorColorToken(hex: "#1F2937")
    public static let textSecondary = PlanteriorColorToken(hex: "#6B7280")
    public static let textTertiary = PlanteriorColorToken(hex: "#9CA3AF")
    public static let textOnAccent = PlanteriorColorToken(hex: "#FFFFFF")
    public static let border = PlanteriorColorToken(hex: "#E5E7EB")
    public static let warningSurface = PlanteriorColorToken(hex: "#FFF7D6")
    public static let warning = PlanteriorColorToken(hex: "#E97800")
    public static let successSurface = PlanteriorColorToken(hex: "#EEF5EE")
}

/// 4pt spacing grid from `docs/ios/DESIGN.md` §4. Names are spelled out because the
/// repository lint forbids two-character identifiers; the scale itself is unchanged.
/// `board` is Figma composite-board chrome, never an in-app gutter.
public enum PlanteriorSpacing {
    public static let extraSmall: CGFloat = 4
    public static let small: CGFloat = 8
    public static let medium: CGFloat = 12
    public static let large: CGFloat = 16
    public static let extraLarge: CGFloat = 20
    public static let huge: CGFloat = 24
    public static let section: CGFloat = 32
    public static let board: CGFloat = 40
}

/// In-app corner radii. The 48pt Figma phone radius is device chrome and is absent here.
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
    public static let navigationBarHeight: CGFloat = 56
    public static let rowHeight: CGFloat = 56
    public static let iconWellSize: CGFloat = 32
    public static let hairline: CGFloat = 1

    public static func iconWellSize(
        for sizeCategory: ContentSizeCategory
    ) -> CGFloat {
        sizeCategory.isAccessibilityCategory ? minimumTarget : iconWellSize
    }
}

public enum PlanteriorMotion {
    public static func duration(reduceMotion: Bool) -> Double {
        reduceMotion ? 0 : 0.2
    }

    public static func standard(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .easeInOut(duration: duration(reduceMotion: false))
    }
}

/// Semantic card roles. Depth is border and tonal shift only; no decorative shadow.
public enum PlanteriorCardVariant: Hashable, Sendable {
    case standard
    case subtle
    case warning
    case success
    case selected
    case disabled

    public var background: PlanteriorColorToken {
        switch self {
        case .standard: PlanteriorPalette.surface
        case .subtle, .disabled: PlanteriorPalette.subtle
        case .warning: PlanteriorPalette.warningSurface
        case .success: PlanteriorPalette.successSurface
        case .selected: PlanteriorPalette.accentSurface
        }
    }

    public var border: PlanteriorColorToken? {
        switch self {
        case .standard: PlanteriorPalette.border
        case .selected: PlanteriorPalette.accent
        case .subtle, .warning, .success, .disabled: nil
        }
    }

    public var foreground: PlanteriorColorToken {
        switch self {
        case .disabled: PlanteriorPalette.textTertiary
        case .warning: PlanteriorPalette.warning
        case .standard, .subtle, .success, .selected: PlanteriorPalette.textPrimary
        }
    }
}

/// Semantic pill/status roles for counts, care state, and eligibility chips.
public enum PlanteriorStatusVariant: Hashable, Sendable {
    case accent
    case neutral
    case tonal
    case warning

    public var background: PlanteriorColorToken {
        switch self {
        case .accent: PlanteriorPalette.accent
        case .neutral: PlanteriorPalette.subtle
        case .tonal: PlanteriorPalette.accentSurface
        case .warning: PlanteriorPalette.warningSurface
        }
    }

    public var foreground: PlanteriorColorToken {
        switch self {
        case .accent: PlanteriorPalette.textOnAccent
        case .neutral: PlanteriorPalette.textSecondary
        case .tonal: PlanteriorPalette.accent
        case .warning: PlanteriorPalette.warning
        }
    }
}

/// Completion-action roles. Reference visual height is 52pt for both styles.
public enum PlanteriorActionStyle: Hashable, Sendable {
    case primary
    case secondary

    public var background: PlanteriorColorToken {
        self == .primary ? PlanteriorPalette.accent : PlanteriorPalette.surface
    }

    public var foreground: PlanteriorColorToken {
        self == .primary ? PlanteriorPalette.textOnAccent : PlanteriorPalette.textPrimary
    }

    public var border: PlanteriorColorToken? {
        self == .primary ? nil : PlanteriorPalette.border
    }

    public var height: CGFloat {
        PlanteriorControl.primaryButtonHeight
    }
}

/// Dynamic Type roles from `docs/ios/DESIGN.md` §3. Fixed Figma point sizes describe
/// hierarchy only; shipping views bind to these semantic styles so AX5 can reflow.
public enum PlanteriorTypography {
    public static let screenTitle = Font.headline.weight(.semibold)
    public static let pageTitle = Font.title3.weight(.bold)
    public static let heroGreeting = Font.title3.weight(.bold)
    public static let sectionTitle = Font.headline.weight(.semibold)
    public static let cardTitle = Font.subheadline.weight(.semibold)
    public static let body = Font.body
    public static let supporting = Font.subheadline
    public static let caption = Font.caption
    public static let microLabel = Font.caption2.weight(.medium)
}
