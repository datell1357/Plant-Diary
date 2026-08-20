import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

/// One placed miniature on the room canvas. Touch dragging is owned by the
/// canvas; this view carries the VoiceOver move actions so keyboard and
/// assistive users can reposition without a drag.
struct MiniRoomPlacementView: View {
    let placement: MiniHomePlacement
    let asset: FigmaAsset
    let label: String
    let side: CGFloat
    let moveBy: (MiniHomePlacement, Double, Double) -> Void

    private static let step = 0.1

    var body: some View {
        Image(asset)
            .resizable()
            .scaledToFit()
            .frame(width: side, height: side)
            .accessibilityLabel(label)
            .accessibilityValue(
                "가로 \(percentage(placement.normalizedX))퍼센트, " +
                    "세로 \(percentage(placement.normalizedY))퍼센트"
            )
            .accessibilityIdentifier(
                "minihome.placement.\(placement.id.rawValue)"
            )
            .accessibilityAction(named: "왼쪽으로 이동") {
                moveBy(placement, -Self.step, 0)
            }
            .accessibilityAction(named: "오른쪽으로 이동") {
                moveBy(placement, Self.step, 0)
            }
            .accessibilityAction(named: "위로 이동") {
                moveBy(placement, 0, -Self.step)
            }
            .accessibilityAction(named: "아래로 이동") {
                moveBy(placement, 0, Self.step)
            }
    }

    private func percentage(_ normalized: Double) -> Int {
        Int((normalized * 100).rounded())
    }
}
