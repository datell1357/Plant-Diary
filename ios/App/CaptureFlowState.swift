import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

/// The four app-owned states on Figma `plant-capture-flow-board` `14:7`
/// (figma-analysis §6.11). The native camera device UI, the photo library
/// picker, and the permission prompt are deliberately absent: they stay
/// system-owned and are presented over these states, never redrawn.
enum CaptureFlowStep: Equatable {
    case camera
    case photoReview
    case identifying
    case identificationResult
}

/// Capture-only geometry contract extracted from the 402x874 Figma frames.
/// Asset dimensions stay native at the reference size and scale down together
/// on narrower or shorter presentations instead of accumulating per-screen
/// constants.
enum CaptureShutterOuterRing: Equatable {
    case continuousCircle
}

enum CaptureLayoutMetrics {
    static let referenceCanvas = CGSize(width: 402, height: 874)

    static let referenceStatusBarHeight: CGFloat = 48
    static let cameraViewportLength: CGFloat = 320
    static let cameraViewportTopSpacing: CGFloat = 161
    static let cameraReticleLength: CGFloat = 240
    static let cameraFocusCircleLength: CGFloat = 160
    static let cameraReticleArmLength: CGFloat = 44
    static let cameraReticleMiddleSegmentOffsets: [CGFloat] = [64, 128]
    static let cameraReticleCornerRadius = PlanteriorSpacing.medium
    static let cameraReticleStrokeWidth: CGFloat = 2
    static let shutterDiameter: CGFloat = 56
    static let shutterRingDiameter: CGFloat = 72
    static let shutterStrokeWidth: CGFloat = 4
    static let shutterOuterRing = CaptureShutterOuterRing.continuousCircle
    static let shutterOffset = CGSize(width: 7, height: -5)
    static let cameraControlRowHeight: CGFloat = 80
    static let gallerySystemImage = "photo.on.rectangle"
    static let cameraControlMinimumTarget: CGFloat = 44
    static let cameraCloseGlyphFont = Font.system(size: 20, weight: .medium)
    static let cameraControlGlyphFont = Font.system(size: 22)
    static let cameraControlLabelWidth: CGFloat = 80
    static let cameraControlAccessibilityLabelWidth: CGFloat = 104
    static let cameraControlWidth: CGFloat = 88
    static let cameraControlAccessibilityWidth: CGFloat = 112

    static let reviewAssetSize = CGSize(width: 386, height: 444)
    static let reviewContentSize = CGSize(width: 362, height: 420)
    static let reviewAccessibilityPhotoHeight: CGFloat = 232
    static let reviewTopSpacing: CGFloat = 84
    static let reviewAssetTopInset: CGFloat = 8
    static let reviewCaptionTopGap: CGFloat = 10
    static let reviewActionSpacing: CGFloat = 14

    static let identifyingBackdropSize = CGSize(width: 390, height: 844)
    static let identifyingProgressTop: CGFloat = 200
    static let identifyingProgressLength: CGFloat = 120
    static let identifyingCoreLength: CGFloat = 80
    static let identifyingGlyphSize: CGFloat = 40
    static let identifyingRingDashPhase: CGFloat = 9
    static let identifyingDotSize: CGFloat = 8
    static let identifyingHeadlineTopSpacing: CGFloat = 33
    static let identifyingHintTopSpacing: CGFloat = 7
    static let identifyingDotTopSpacing: CGFloat = 31

    static let resultHeroSize = CGSize(width: 362, height: 160)
    static let resultTopSpacing: CGFloat = 38
    static let resultSummaryBottomInset: CGFloat = 18
    static let resultAlternateSpacing: CGFloat = 10
    static let navigationBackVisualSide = PlanteriorControl.compactVisualSize
    static let navigationBackGlyphFont = Font.system(size: 18, weight: .semibold)
    static let reviewSparkleSize = CGSize(width: 22, height: 20)
    static let reviewSparkleStrokeWidth: CGFloat = 2
    static let resultCompactHeroHeight: CGFloat = 140
    static let resultActionVerticalOffset: CGFloat = 7

    static func fittingScale(for availableSize: CGSize) -> CGFloat {
        min(
            1,
            min(
                availableSize.width / referenceCanvas.width,
                availableSize.height / referenceCanvas.height
            )
        )
    }

    static func horizontalScale(for availableWidth: CGFloat) -> CGFloat {
        min(1, availableWidth / referenceCanvas.width)
    }
}

/// Presentation model for the identification result card (§6.11).
struct CaptureSpecies: Equatable {
    let koreanName: String
    let commonName: String
    let binomial: String
    let summary: String

    #if DEBUG
        enum FixtureID: Equatable {
            case authenticatedPrimary
            case authenticatedFirstAlternate
            case authenticatedSecondAlternate
        }

        static func fixtureID(for rawID: String) -> FixtureID? {
            switch rawID {
            case "local-candidate-1": .authenticatedPrimary
            case "local-candidate-2": .authenticatedFirstAlternate
            case "local-candidate-3": .authenticatedSecondAlternate
            default: nil
            }
        }

        /// Display copy for the deterministic local identification fixture.
        static func named(_ rawID: String) -> CaptureSpecies {
            switch fixtureID(for: rawID) {
            case .authenticatedPrimary:
                CaptureSpecies(
                    koreanName: "몬스테라 델리시오사",
                    commonName: "Monstera deliciosa",
                    binomial: "Monstera deliciosa",
                    summary: "넓은 잎에 독특한 구멍이 특징인 열대 관엽식물이에요. 간접광을 좋아하며 겉흙이 마르면 물을 듬뿍 주세요."
                )
            case .authenticatedFirstAlternate:
                CaptureSpecies(
                    koreanName: "몬스테라 아단소니",
                    commonName: "Monstera adansonii",
                    binomial: "Monstera adansonii",
                    summary: "잎에 구멍이 많은 덩굴형 몬스테라예요. 지지대를 세워 주면 더 건강하게 자랍니다."
                )
            case .authenticatedSecondAlternate, nil:
                CaptureSpecies(
                    koreanName: "필로덴드론",
                    commonName: "Philodendron",
                    binomial: "Philodendron hederaceum",
                    summary: "관리가 쉬운 실내 식물로, 반음지에서도 잘 자랍니다."
                )
            }
        }
    #endif
}

extension IdentificationCandidate {
    /// Integer confidence percentage used by the chip and candidate rows.
    var confidencePercentage: Int {
        Int((score * 100).rounded())
    }

    var species: CaptureSpecies {
        #if DEBUG
            if CaptureSpecies.fixtureID(for: plantID.rawValue) != nil {
                return CaptureSpecies.named(plantID.rawValue)
            }
        #endif
        return CaptureSpecies(
            koreanName: koreanName,
            commonName: commonName,
            binomial: scientificName,
            summary: "식별 결과를 바탕으로 식물의 기본 정보를 확인해 보세요."
        )
    }
}
