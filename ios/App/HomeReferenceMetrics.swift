import PlanteriorDesignSystem
import SwiftUI

/// Exact 402pt Home reference geometry and AX-only sizing guards.
enum HomeReferenceMetrics {
    static let headerRowSpacing: CGFloat = 10
    static let avatarSide = PlanteriorControl.compactVisualSize
    static let metadataMinimumScale: CGFloat = 0.7
    static let weatherGlyphFont = Font.system(size: 11, weight: .semibold)
    static let weatherGlyphSide: CGFloat = 14
    static let miniRoomHeight: CGFloat = 326
    static let roomActionVisualSide = PlanteriorControl.iconWellSize
    static let roomActionInset: CGFloat = 10
    static let careEmptyTopInset: CGFloat = 19
    static let careContentTopOffset: CGFloat = -10
    static let careRowHorizontalInset: CGFloat = 14
    static let careNameMinimumScale: CGFloat = 0.8
    static let careRowHeight: CGFloat = 76
    static let careTrailingHeight = PlanteriorControl.iconWellSize
    static let careEmptySpacing: CGFloat = 6
    static let careEmptyGlyphFont = Font.system(size: 32)
    static let careEmptyHeight: CGFloat = 120
    static let notificationDetailSpacing: CGFloat = 2
    static let weatherRowSpacing: CGFloat = 6
    static let weatherGlyphFrame = PlanteriorSpacing.extraLarge
    static let weatherRowHeight: CGFloat = 64
}
