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

public enum PlanteriorPalette {
    public static let canvas = PlanteriorColorToken(hex: "#FCFBF7")
    public static let surface = PlanteriorColorToken(hex: "#FFFFFF")
    public static let accent = PlanteriorColorToken(hex: "#3D6642")
    public static let subtle = PlanteriorColorToken(hex: "#EEF3F0")
    public static let textPrimary = PlanteriorColorToken(hex: "#1C241D")
    public static let textSecondary = PlanteriorColorToken(hex: "#667067")
    public static let textOnAccent = PlanteriorColorToken(hex: "#FFFFFF")
    public static let border = PlanteriorColorToken(hex: "#D8DED9")
}

public enum PlanteriorRadius {
    public static let small: CGFloat = 8
    public static let medium: CGFloat = 12
    public static let large: CGFloat = 16
}

public enum PlanteriorControl {
    public static let minimumTarget: CGFloat = 44
    public static let cameraDiameter: CGFloat = 52
}

public enum PlanteriorMotion {
    public static func duration(reduceMotion: Bool) -> Double {
        reduceMotion ? 0 : 0.2
    }
}

public enum PlanteriorTypography {
    public static let screenTitle = Font.title2.weight(.bold)
    public static let sectionTitle = Font.headline.weight(.semibold)
    public static let body = Font.body
    public static let caption = Font.caption
}
