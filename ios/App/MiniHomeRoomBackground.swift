import PlanteriorDesignSystem
import SwiftUI

struct MiniHomeRoomBackground: View {
    var body: some View {
        Canvas { context, size in
            let room = CGRect(origin: .zero, size: size)
            context.fill(
                Path(roundedRect: room, cornerRadius: 16),
                with: .color(PlanteriorPalette.surface.color)
            )
            let vanishingPoint = CGPoint(
                x: size.width / 2,
                y: size.height * 0.56
            )
            let floorTop = size.height * 0.7
            var leftFloor = Path()
            leftFloor.move(to: CGPoint(x: 0, y: floorTop))
            leftFloor.addLine(to: vanishingPoint)
            leftFloor.addLine(
                to: CGPoint(x: size.width / 2, y: size.height)
            )
            leftFloor.addLine(to: CGPoint(x: 0, y: size.height))
            leftFloor.closeSubpath()
            context.fill(
                leftFloor,
                with: .color(PlanteriorPalette.subtle.color)
            )
            var rightFloor = Path()
            rightFloor.move(to: vanishingPoint)
            rightFloor.addLine(
                to: CGPoint(x: size.width, y: floorTop)
            )
            rightFloor.addLine(
                to: CGPoint(x: size.width, y: size.height)
            )
            rightFloor.addLine(
                to: CGPoint(x: size.width / 2, y: size.height)
            )
            rightFloor.closeSubpath()
            context.fill(
                rightFloor,
                with: .color(
                    PlanteriorPalette.accent.color.opacity(0.28)
                )
            )
            var roomEdges = Path()
            roomEdges.move(to: CGPoint(x: 0, y: floorTop))
            roomEdges.addLine(to: vanishingPoint)
            roomEdges.addLine(to: CGPoint(x: size.width, y: floorTop))
            context.stroke(
                roomEdges,
                with: .color(
                    PlanteriorPalette.accent.color.opacity(0.7)
                ),
                lineWidth: 2
            )
        }
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .stroke(
                    PlanteriorPalette.accent.color.opacity(0.55),
                    lineWidth: 2
                )
        }
    }
}
