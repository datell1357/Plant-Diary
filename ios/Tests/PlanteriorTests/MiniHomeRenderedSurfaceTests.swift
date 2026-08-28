@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct MiniHomeRenderedSurfaceTests {
    @Test
    func referencePlacementRenderingKeepsTrailingBasesTransparent() throws {
        try assertRenderedPlacementBasesAreTransparent(
            MiniHomeView.figmaReferencePlacements.map(
                MiniRoomPlacementPresentation.asset
            )
        )
    }

    @Test
    func canonicalPlantsPaintPinnedEditorCommittedHomeAndSharePixels() throws {
        let room = try makeRoom(placements: MiniHomeView.figmaReferencePlacements)
        let emptyRoom = try makeRoom(placements: [])

        try assertEditorSurface(room: room, emptyRoom: emptyRoom)
        try assertCommittedSurface(room: room, emptyRoom: emptyRoom)
        try assertHomeSurface(room: room, emptyRoom: emptyRoom)
        try assertShareSurface(room: room, emptyRoom: emptyRoom)
    }

    private func makeRoom(placements: [MiniHomePlacement]) throws -> MiniHome {
        try MiniHome(
            id: MiniHomeID.parse("pixel-room"),
            name: "pixel-room",
            placements: placements,
            revision: .zero,
            updatedAt: Instant.parse("2026-08-11T01:00:00Z")
        )
    }
}
