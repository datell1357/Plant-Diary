import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

/// Figma `room-canvas-container`: the isometric room fills a radius-lg card that
/// stays the visual focus, placements sit on the room, and a Surface pill carries
/// the move affordance copy.
struct MiniHomeEditorCanvas: View {
    let room: MiniHome
    let placementLabel: (MiniHomePlacement) -> String
    let move: (MiniHomePlacement, MiniHomePosition?) -> Void
    let moveBy: (MiniHomePlacement, Double, Double) -> Void
    /// Painted room height. Default keeps the exact Figma 330pt; the
    /// accessibility layout hands down the height its budget can afford.
    var height: CGFloat = MiniHomeEditorCanvas.canvasSize.height

    /// The authenticated room derivative preserves the Figma window, rug,
    /// geometry, and directional light but contains no baked plant pixels.
    static let baseAsset: FigmaAsset = .roomBase
    static let canvasSize = CGSize(width: 358, height: 330)
    private static let coordinateSpace = "minihome.editor.canvas"

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Image(Self.baseAsset)
                    .resizable()
                    .scaledToFill()
                    .frame(
                        width: geometry.size.width,
                        height: geometry.size.height
                    )
                    .clipped()
                    .accessibilityHidden(true)
                ForEach(
                    MiniRoomPlacementProjector.resolved(
                        placements: room.placements,
                        in: geometry.size
                    ),
                    id: \.placement.id
                ) { resolved in
                    MiniRoomPlacementView(
                        placement: resolved.placement,
                        asset: resolved.asset,
                        label: placementLabel(resolved.placement),
                        size: resolved.visualSize,
                        moveBy: moveBy
                    )
                    .frame(
                        width: MiniRoomPlacementMetrics.hitSide,
                        height: MiniRoomPlacementMetrics.hitSide
                    )
                    .contentShape(Rectangle())
                    .position(resolved.position)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .contentShape(Rectangle())
            .gesture(dragGesture(room.placements, size: geometry.size))
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
            .coordinateSpace(name: Self.coordinateSpace)
        }
        .frame(width: Self.canvasSize.width, height: height)
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
                .accessibilityHidden(true)
            Text("길게 눌러서 가구 이동")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .accessibilityIdentifier("minihome.editor.hint")
        }
        .padding(.horizontal, PlanteriorSpacing.medium)
        .padding(.vertical, MiniRoomReferenceMetrics.hintVerticalInset)
        .background(PlanteriorPalette.surface.color)
        .clipShape(Capsule())
        .padding(PlanteriorSpacing.medium)
    }

    private func dragGesture(
        _ placements: [MiniHomePlacement],
        size: CGSize
    ) -> some Gesture {
        DragGesture(coordinateSpace: .named(Self.coordinateSpace))
            .onEnded { value in
                guard let placement = placement(
                    at: value.startLocation,
                    among: placements,
                    in: size
                ) else {
                    return
                }
                move(placement, try? MiniHomeGeometry.position(
                    dragX: Double(value.location.x),
                    dragY: Double(value.location.y),
                    roomWidth: Double(size.width),
                    roomHeight: Double(size.height)
                ))
            }
    }

    private func placement(
        at point: CGPoint,
        among placements: [MiniHomePlacement],
        in size: CGSize
    ) -> MiniHomePlacement? {
        MiniRoomPlacementProjector.hitPlacement(
            at: point,
            among: placements,
            in: size
        )
    }
}
