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
