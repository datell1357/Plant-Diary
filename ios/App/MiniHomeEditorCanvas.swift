import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

/// Figma `room-canvas-container`: the isometric room fills a radius-lg card that
/// stays the visual focus, placements sit on the room, and a Surface pill carries
/// the move affordance copy.
struct MiniHomeEditorCanvas: View {
    let room: MiniHome
    let placementAsset: (MiniHomePlacement) -> FigmaAsset
    let placementLabel: (MiniHomePlacement) -> String
    let move: (MiniHomePlacement, MiniHomePosition?) -> Void
    let moveBy: (MiniHomePlacement, Double, Double) -> Void

    /// Figma `isometric-3d-room` ships at 358x330 logical points.
    private static let aspectRatio: CGFloat = 358.0 / 330.0
    private static let itemSide: CGFloat = 56
    private static let coordinateSpace = "minihome.editor.canvas"

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Image(.roomHero)
                    .resizable()
                    .scaledToFill()
                    .accessibilityIdentifier("minihome.editor.room")
                    .accessibilityLabel("\(room.name) 방")
                ForEach(
                    MiniHomeGeometry.ordered(room.placements),
                    id: \.id
                ) { placement in
                    MiniRoomPlacementView(
                        placement: placement,
                        asset: placementAsset(placement),
                        label: placementLabel(placement),
                        side: Self.itemSide,
                        moveBy: moveBy
                    )
                    .position(position(placement, in: geometry.size))
                    .gesture(dragGesture(placement, size: geometry.size))
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
            .coordinateSpace(name: Self.coordinateSpace)
        }
        .aspectRatio(Self.aspectRatio, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .overlay(alignment: .bottomLeading) { hintPill }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("미니홈 배치 공간")
        .accessibilityIdentifier("minihome.editor.canvas")
    }

    private var hintPill: some View {
        HStack(spacing: PlanteriorSpacing.extraSmall) {
            Image(systemName: "circle.grid.2x2.fill")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.accent.color)
            Text("길게 눌러서 가구 이동")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .accessibilityIdentifier("minihome.editor.hint")
        }
        .padding(.horizontal, PlanteriorSpacing.medium)
        .padding(.vertical, 5)
        .background(PlanteriorPalette.surface.color)
        .clipShape(Capsule())
        .padding(PlanteriorSpacing.medium)
    }

    private func position(
        _ placement: MiniHomePlacement,
        in size: CGSize
    ) -> CGPoint {
        CGPoint(
            x: CGFloat(MiniHomeGeometry.pixelCoordinate(
                normalized: placement.normalizedX,
                length: Double(size.width),
                itemRadius: Double(Self.itemSide / 2)
            )),
            y: CGFloat(MiniHomeGeometry.pixelCoordinate(
                normalized: placement.normalizedY,
                length: Double(size.height),
                itemRadius: Double(Self.itemSide / 2)
            ))
        )
    }

    private func dragGesture(
        _ placement: MiniHomePlacement,
        size: CGSize
    ) -> some Gesture {
        DragGesture(coordinateSpace: .named(Self.coordinateSpace))
            .onEnded { value in
                move(placement, try? MiniHomeGeometry.position(
                    dragX: Double(value.location.x),
                    dragY: Double(value.location.y),
                    roomWidth: Double(size.width),
                    roomHeight: Double(size.height)
                ))
            }
    }
}
