import SwiftUI

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

public enum PlanteriorStatusVariant: Hashable, Sendable {
    case accent
    case neutral
    case tonal
    case warning
    case attention

    public var background: PlanteriorColorToken {
        switch self {
        case .accent: PlanteriorPalette.accent
        case .neutral: PlanteriorPalette.subtle
        case .tonal: PlanteriorPalette.accentSurface
        case .warning: PlanteriorPalette.warningSurface
        case .attention: PlanteriorPalette.attentionSurface
        }
    }

    public var foreground: PlanteriorColorToken {
        switch self {
        case .accent: PlanteriorPalette.textOnAccent
        case .neutral: PlanteriorPalette.textSecondary
        case .tonal: PlanteriorPalette.accent
        case .warning: PlanteriorPalette.warning
        case .attention: PlanteriorPalette.attentionText
        }
    }
}

public enum PlanteriorFilterStyle: Hashable, Sendable {
    case selected
    case unselected

    public var background: PlanteriorColorToken {
        self == .selected ? PlanteriorPalette.accent : PlanteriorPalette.surface
    }

    public var foreground: PlanteriorColorToken {
        self == .selected ? PlanteriorPalette.textOnAccent : PlanteriorPalette.textSecondary
    }

    public var border: PlanteriorColorToken? {
        self == .selected ? nil : PlanteriorPalette.border
    }
}

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
    public static let floatingActionGlyph = Font.system(size: 24, weight: .semibold)
}
