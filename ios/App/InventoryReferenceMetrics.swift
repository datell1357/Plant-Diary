import PlanteriorDesignSystem
import SwiftUI

/// Exact Inventory Figma geometry. Shared scale values continue to come from
/// PlanteriorDesignSystem; these values are local to warehouse/shop/detail.
enum InventoryReferenceMetrics {
    /// The authenticated frame's content begins on a half-pixel boundary.
    /// Native safe areas remain authoritative; only their measured excess over
    /// this reference edge is removed from the Inventory scroll owner.
    static let referenceContentTop: CGFloat = 47.5

    static func contentTopCorrection(
        measuredSafeAreaTop: CGFloat
    ) -> CGFloat {
        min(PlanteriorSpacing.none, referenceContentTop - measuredSafeAreaTop)
    }

    static let headerHeight = PlanteriorControl.minimumTarget
    static let headerIconSide = PlanteriorControl.iconWellSize
    static let creditIconSide: CGFloat = 20
    static let creditWidth: CGFloat = 179
    static let creditHeight: CGFloat = 38
    static let creditTopInset: CGFloat = 7
    static let filterTrackHeight: CGFloat = 53
    static let filterHeight: CGFloat = 31
    static let shopCardWidth: CGFloat = 173
    static let shopCardHeight: CGFloat = 180
    static let shopCardImageSize = CGSize(width: 153, height: 110)
    static let shopCardInset: CGFloat = 9
    static let shopCardImageToTitleSpacing: CGFloat = 10
    static let shopGridColumnSpacing: CGFloat = 12

    static func shopGridRowSpacing(scrollBodyHeight: CGFloat) -> CGFloat {
        scrollBodyHeight < 760
            ? PlanteriorSpacing.small
            : PlanteriorSpacing.medium
    }

    static let standardFilterWidth: CGFloat = 56
    static let seasonalFilterWidth: CGFloat = 72
    static let accessibilityFilterMinimumWidth: CGFloat = 80
    static let gridCardWidth: CGFloat = 110
    static let gridCardHeight: CGFloat = 130
    static let gridSpacing: CGFloat = 10
    static let cardTextSpacing: CGFloat = 6
    static let cardImageSize = CGSize(width: 94, height: 80)
    static let appliedBadgeFont = Font.system(size: 9, weight: .semibold)
    static let appliedBadgeInset: CGFloat = 6
    static let appliedBadgeHeight: CGFloat = 18
    static let appliedBadgeRadius: CGFloat = 5
    static let countTrackHeight: CGFloat = 35
    static let accessibilityCountMinimumHeight = PlanteriorControl.minimumTarget
    static let accessibilityCountToGridSpacing: CGFloat = 24

    static let detailHeroToTitleSpacing: CGFloat = 20
    static let detailTitleToStatusSpacing: CGFloat = 25
    static let detailStatusToActionSpacing: CGFloat = 20
    static let detailActionToPreviewSpacing: CGFloat = 17
    static let detailBackGlyph = Font.system(size: 18, weight: .semibold)
    static let detailFavoriteGlyph = Font.system(size: 17, weight: .semibold)
    static let detailFavoriteSide = PlanteriorControl.iconWellSize
    static let detailHeroHeight: CGFloat = 220
    static let detailCategoryHeight: CGFloat = 22
    static let detailTitleSpacing: CGFloat = 8
    static let detailTitleFont = Font.title2.weight(.bold)
    static let detailBodyLineSpacing = PlanteriorSpacing.extraSmall
    static let detailStatusHeight: CGFloat = 50
    static let detailActionHeight = PlanteriorControl.secondaryButtonHeight
    static let detailPreviewSpacing: CGFloat = 2
    static let detailPreviewHeight: CGFloat = 72
}
