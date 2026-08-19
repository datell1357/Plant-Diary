import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct MiniHomeEditorCanvas: View {
    let room: MiniHome
    @ObservedObject var store: MiniHomeStore
    @Binding var errorMessage: String?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                MiniHomeRoomBackground()
                ForEach(
                    MiniHomeGeometry.ordered(room.placements),
                    id: \.id
                ) { placement in
                    placementView(placement, size: geometry.size)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .coordinateSpace(name: "minihome.editor.canvas")
        }
        .frame(height: 320)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("미니홈 배치 공간")
        .accessibilityIdentifier("minihome.editor.canvas")
    }

    private func placementView(
        _ placement: MiniHomePlacement,
        size: CGSize
    ) -> some View {
        placementIcon(placement)
            .position(
                x: CGFloat(
                    MiniHomeGeometry.pixelCoordinate(
                        normalized: placement.normalizedX,
                        length: Double(size.width),
                        itemRadius: 26
                    )
                ),
                y: CGFloat(
                    MiniHomeGeometry.pixelCoordinate(
                        normalized: placement.normalizedY,
                        length: Double(size.height),
                        itemRadius: 26
                    )
                )
            )
            .gesture(
                DragGesture(coordinateSpace: .named("minihome.editor.canvas"))
                    .onEnded { value in
                        move(
                            placement,
                            horizontal: value.location.x,
                            vertical: value.location.y,
                            size: size
                        )
                    }
            )
    }

    private func placementIcon(
        _ placement: MiniHomePlacement
    ) -> some View {
        Image(systemName: "leaf.fill")
            .font(.system(size: 34))
            .foregroundStyle(PlanteriorPalette.accent.color)
            .frame(width: 52, height: 52)
            .background(PlanteriorPalette.surface.color)
            .clipShape(Circle())
            .accessibilityLabel("배치된 식물")
            .accessibilityValue(
                "가로 \(percentage(placement.normalizedX))퍼센트, " +
                    "세로 \(percentage(placement.normalizedY))퍼센트"
            )
            .accessibilityIdentifier(
                "minihome.placement.\(placement.id.rawValue)"
            )
            .accessibilityAction(named: "왼쪽으로 이동") {
                moveBy(
                    placement,
                    horizontalDelta: -0.1,
                    verticalDelta: 0
                )
            }
            .accessibilityAction(named: "오른쪽으로 이동") {
                moveBy(
                    placement,
                    horizontalDelta: 0.1,
                    verticalDelta: 0
                )
            }
            .accessibilityAction(named: "위로 이동") {
                moveBy(
                    placement,
                    horizontalDelta: 0,
                    verticalDelta: -0.1
                )
            }
            .accessibilityAction(named: "아래로 이동") {
                moveBy(
                    placement,
                    horizontalDelta: 0,
                    verticalDelta: 0.1
                )
            }
    }

    private func move(
        _ placement: MiniHomePlacement,
        horizontal: Double,
        vertical: Double,
        size: CGSize
    ) {
        do {
            let position = try MiniHomeGeometry.position(
                dragX: horizontal,
                dragY: vertical,
                roomWidth: Double(size.width),
                roomHeight: Double(size.height)
            )
            try store.moveDraftPlacement(id: placement.id, to: position)
        } catch {
            errorMessage = "식물 위치를 옮기지 못했어요."
        }
    }

    private func moveBy(
        _ placement: MiniHomePlacement,
        horizontalDelta: Double,
        verticalDelta: Double
    ) {
        let nextX = min(
            max(placement.normalizedX + horizontalDelta, 0),
            1
        )
        let nextY = min(
            max(placement.normalizedY + verticalDelta, 0),
            1
        )
        do {
            let position = try MiniHomePosition(
                normalizedX: nextX,
                normalizedY: nextY
            )
            try store.moveDraftPlacement(id: placement.id, to: position)
        } catch {
            errorMessage = "식물 위치를 옮기지 못했어요."
        }
    }

    private func percentage(_ normalized: Double) -> Int {
        Int((normalized * 100).rounded())
    }
}
