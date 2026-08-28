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
    let size: CGSize
    let moveBy: (MiniHomePlacement, Double, Double) -> Void

    private static let step = 0.1

    var body: some View {
        MiniRoomPlacementVisual(asset: asset, size: size)
            .accessibilityLabel(label)
            .accessibilityValue(
                MiniRoomPlacementPresentation.accessibilityValue(for: placement)
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
}
