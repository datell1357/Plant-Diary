import PlanteriorDesignSystem
import SwiftUI

/// Collection-only measurements extracted from the 402x874 Figma frames.
enum CollectionReferenceMetrics {
    static let shellSpacing: CGFloat = 18
    static let headerActionSide = PlanteriorControl.compactVisualSize
    static let searchFieldHeight = PlanteriorControl.secondaryButtonHeight
    static let summaryImageSide = PlanteriorLayout.mediaThumbnailSize
    static let rowSpacing: CGFloat = 10
    static let rowImageSide: CGFloat = 64
    static let statusDotSide: CGFloat = 8
    static let collectionNameMinimumScale: CGFloat = 0.8
    static let atomicSpeciesMinimumScale: CGFloat = 0.6
    static let emptyIllustrationDefaultSide: CGFloat = 140
    static let emptyIllustrationAccessibilitySide: CGFloat = 96
    static let emptyIllustrationInset = PlanteriorSpacing.extraLarge
    static let emptyTitleTopInset: CGFloat = 28
    static let emptyTitleMinimumHeight: CGFloat = 20.333
    static let emptyBodyTopInset: CGFloat = 7.5
    static let emptyBodyMinimumHeight: CGFloat = 18
    static let emptyBodyOpticalOffset: CGFloat = 0.5
    static let emptyPrimaryTopInset: CGFloat = 28.5
    static let emptyPrimaryHeight: CGFloat = 46
    static let searchEmptyGlyphFont = Font.system(size: 40)
}
