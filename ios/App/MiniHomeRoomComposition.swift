import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

/// Shared live room renderer used by the editor, committed MiniHome, and Home.
/// The background may vary by surface, but a placement ID always resolves to
/// the same prop asset and normalized projection.
struct MiniHomeRoomComposition: View {
    let room: MiniHome?
    let background: FigmaAsset
    let roomIdentifier: String
    let roomLabel: String

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Image(background)
                    .resizable()
                    .scaledToFill()
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
                    .accessibilityHidden(true)
                if let room {
                    ForEach(
                        MiniRoomPlacementProjector.resolved(
                            placements: room.placements,
                            in: geometry.size
                        ),
                        id: \.placement.id
                    ) { resolved in
                        MiniRoomPlacementVisual(
                            asset: resolved.asset,
                            size: resolved.visualSize
                        )
                        .frame(
                            width: MiniRoomPlacementMetrics.hitSide,
                            height: MiniRoomPlacementMetrics.hitSide
                        )
                        .contentShape(Rectangle())
                        .position(resolved.position)
                        .accessibilityLabel(
                            MiniRoomPlacementPresentation.accessibilityLabel(
                                for: resolved.placement
                            )
                        )
                        .accessibilityValue(
                            MiniRoomPlacementPresentation.accessibilityValue(
                                for: resolved.placement
                            )
                        )
                        .accessibilityIdentifier(
                            "minihome.placement.\(resolved.placement.id.rawValue)"
                        )
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
        }
    }
}

struct MiniRoomPlacementVisual: View {
    let asset: FigmaAsset
    let size: CGSize

    var body: some View {
        Image(asset)
            .resizable()
            .scaledToFit()
            .frame(width: size.width, height: size.height)
    }
}
