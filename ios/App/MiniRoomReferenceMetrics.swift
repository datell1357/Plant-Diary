import PlanteriorDesignSystem
import SwiftUI

/// Exact room-editor geometry from the 402x874 Figma frame.
enum MiniRoomReferenceMetrics {
    static let canvasSize = CGSize(width: 358, height: 330)
    static let statusBarHeight: CGFloat = 48
    static let canvasGutter: CGFloat = 22
    static let canvasTopInset: CGFloat = 100

    static func canvasGutter(availableWidth: CGFloat) -> CGFloat {
        min(canvasGutter, max(0, (availableWidth - canvasSize.width) / 2))
    }

    static let hintVerticalInset: CGFloat = 5
    static let footerHeight: CGFloat = 40
    static let footerTextMinimumScale: CGFloat = 0.75
    static let headerTitleMinimumScale: CGFloat = 0.55
    static let headerSaveMinimumScale: CGFloat = 0.3
    /// No optical shrink: the default tray caption paints at its full size.
    static let noTextShrink: CGFloat = 1
    /// A tray name such as `스킨답서스` outgrows its wrapped accessibility
    /// column, so the caption shrinks to one painted line instead of hard
    /// breaking mid-word and stranding a final syllable.
    static let trayCaptionMinimumScale: CGFloat = 0.6
    static let fullOpacity: Double = 1
    static let trayHeight: CGFloat = 117
    static let trayTopInset = PlanteriorSpacing.large
    static let trayHorizontalInset: CGFloat = 17
    static let traySpacing: CGFloat = 14
    static let trayTileSide: CGFloat = 70
    static let trayAccessibilityTileSide: CGFloat = 56
    static let trayBadgeSide: CGFloat = 20
    static let trayBadgeGlyphSide: CGFloat = 9
    static let trayBadgeOffset = CGSize(width: -3, height: 3)
    static let traySelectedBorder: CGFloat = 2
    static let placementHitSide: CGFloat = 72
    static let placementBaseWidth: CGFloat = 50
    static let placementBaseHeight: CGFloat = 56
    static let itemBaseWidth: CGFloat = 80
    static let itemBaseHeight: CGFloat = 68

    // MARK: Accessibility layout

    /// At the accessibility sizes the control strips wrap instead of scrolling,
    /// so the 874pt frame is budgeted explicitly: the room keeps at least this
    /// much painted height and the wrapped strips take what is left.
    static let accessibilityCanvasMinimumHeight: CGFloat = 200
    /// Wrapped strips read top-to-bottom in source order, so two columns keep
    /// each Korean caption on one line without a horizontal scroller.
    static let accessibilityColumnCount = 2
    /// The five categories need three columns at the accessibility sizes: two
    /// columns wrap them into three rows, and the extra row overruns the 874pt
    /// frame so the strips collide with the room and each other. Three columns
    /// paint the same five captions in two rows inside the budget.
    static let accessibilityCategoryColumnCount = 3
    /// Two wrapped rows are what the remaining budget paints without pushing
    /// the footer actions off the 874pt frame.
    static let accessibilityTrayVisibleRows = 2
    static let accessibilityTrayVisibleCount =
        accessibilityColumnCount * accessibilityTrayVisibleRows
    static let depthBase: CGFloat = 0.82
    static let depthRange: CGFloat = 0.28
}
