import Foundation
import PlanteriorData

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
enum CaptureLayoutMetrics {
    static let referenceCanvas = CGSize(width: 402, height: 874)

    static let cameraViewportLength: CGFloat = 320
    static let cameraViewportTopSpacing: CGFloat = 161
    static let cameraReticleLength: CGFloat = 240
    static let cameraFocusCircleLength: CGFloat = 160
    static let cameraReticleArmLength: CGFloat = 44
    static let shutterDiameter: CGFloat = 56
    static let shutterRingDiameter: CGFloat = 72
    static let shutterStrokeWidth: CGFloat = 4
    static let cameraControlRowHeight: CGFloat = 80
    static let gallerySystemImage = "photo"
    static let cameraControlMinimumTarget: CGFloat = 44

    static let reviewAssetSize = CGSize(width: 386, height: 444)
    static let reviewContentSize = CGSize(width: 362, height: 420)
    static let reviewCompactContentHeight: CGFloat = 232
    static let reviewCompactRegionHeight: CGFloat = 256
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

/// Presentation model for the identification result card (§6.11). The
/// identification service returns opaque content IDs and scores; this maps them
/// onto the Korean name / binomial / description the Figma card renders.
struct CaptureSpecies: Equatable {
    let koreanName: String
    let binomial: String
    let summary: String

    /// Display copy for a candidate. The local identification service yields
    /// `local-candidate-1...3`, matching the board's Monstera-led result.
    static func named(_ rawID: String) -> CaptureSpecies {
        switch rawID {
        case "local-candidate-1":
            CaptureSpecies(
                koreanName: "몬스테라 델리오사",
                binomial: "Monstera deliciosa",
                summary: "넓은 잎에 독특한 구멍이 특징인 열대 관엽식물이에요. 간접광을 좋아하며 겉흙이 마르면 물을 듬뿍 주세요."
            )
        case "local-candidate-2":
            CaptureSpecies(
                koreanName: "몬스테라 아단소니",
                binomial: "Monstera adansonii",
                summary: "잎에 구멍이 많은 덩굴형 몬스테라예요. 지지대를 세워 주면 더 건강하게 자랍니다."
            )
        default:
            CaptureSpecies(
                koreanName: "필로덴드론",
                binomial: "Philodendron hederaceum",
                summary: "관리가 쉬운 실내 식물로, 반음지에서도 잘 자랍니다."
            )
        }
    }
}

extension IdentificationCandidate {
    /// Integer confidence percentage used by the chip and candidate rows.
    var confidencePercentage: Int {
        Int((score * 100).rounded())
    }

    var species: CaptureSpecies {
        CaptureSpecies.named(plantID.rawValue)
    }
}
