import PlanteriorDomain
import SwiftUI

/// Read-only committed room. It deliberately uses the same plant-free scene,
/// asset resolver, depth sizing, and placement projection as the editor.
struct MiniHomeCanvasView: View {
    let room: MiniHome

    var body: some View {
        MiniHomeRoomComposition(
            room: room,
            background: .roomBase,
            roomIdentifier: "minihome.committed.room",
            roomLabel: "\(room.name) 방"
        )
        .frame(height: 320)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("저장된 미니홈 배치 공간")
        .accessibilityIdentifier("minihome.canvas")
    }
}
