import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct MiniHomeCanvasView: View {
    let room: MiniHome

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                MiniHomeRoomBackground()
                ForEach(
                    MiniHomeGeometry.ordered(room.placements),
                    id: \.id
                ) { placement in
                    placementView(placement)
                        .position(
                            x: CGFloat(
                                MiniHomeGeometry.pixelCoordinate(
                                    normalized: placement.normalizedX,
                                    length: Double(geometry.size.width),
                                    itemRadius: 26
                                )
                            ),
                            y: CGFloat(
                                MiniHomeGeometry.pixelCoordinate(
                                    normalized: placement.normalizedY,
                                    length: Double(geometry.size.height),
                                    itemRadius: 26
                                )
                            )
                        )
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .frame(height: 320)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("저장된 미니홈 배치 공간")
        .accessibilityIdentifier("minihome.canvas")
    }

    private func placementView(
        _ placement: MiniHomePlacement
    ) -> some View {
        Image(
            systemName: placement.plantID == nil
                ? "shippingbox.fill"
                : "leaf.fill"
        )
        .font(.system(size: 34))
        .foregroundStyle(PlanteriorPalette.accent.color)
        .frame(width: 52, height: 52)
        .background(PlanteriorPalette.surface.color)
        .clipShape(Circle())
        .accessibilityLabel(
            placement.plantID == nil ? "소품" : "식물"
        )
        .accessibilityIdentifier(
            "minihome.placement.\(placement.id.rawValue)"
        )
    }
}
