import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct MiniHomeRoomOverlapTests {
    @Test
    func hitResolutionSelectsTheFrontmostVisualPlacementRegardlessOfPersistedOrder() throws {
        let placements = try overlappingPlacements()
        let canvas = MiniHomeEditorCanvas.canvasSize
        let insertionPoint = MiniRoomPlacementMetrics.position(placements[0], in: canvas)

        let hit = MiniRoomPlacementProjector.hitPlacement(
            at: insertionPoint,
            among: placements,
            in: canvas
        )

        #expect(hit?.id.rawValue == "placement-3")
        #expect(
            MiniRoomPlacementProjector.ordered(placements: placements)
                .map(\.id.rawValue) == ["placement-1", "placement-2", "placement-3"]
        )
    }

    @Test
    func zIndexAndStableIDBothDetermineTheVisibleHitTarget() throws {
        let canvas = MiniHomeEditorCanvas.canvasSize
        let lowerID = try placement(id: "placement-z", zIndex: 4)
        let higherID = try placement(id: "placement-a", zIndex: 5)
        let sameLayerEarlierID = try placement(id: "placement-a", zIndex: 5)
        let sameLayerLaterID = try placement(id: "placement-z", zIndex: 5)
        let point = MiniRoomPlacementMetrics.position(lowerID, in: canvas)

        #expect(
            MiniRoomPlacementProjector.hitPlacement(
                at: point,
                among: [higherID, lowerID],
                in: canvas
            )?.id == higherID.id
        )
        #expect(
            MiniRoomPlacementProjector.hitPlacement(
                at: point,
                among: [sameLayerLaterID, sameLayerEarlierID],
                in: canvas
            )?.id == sameLayerLaterID.id
        )
    }

    @Test
    func draggingTheVisibleOverlappedPlacementPersistsOnlyThatPlacement() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let placements = try overlappingPlacements()
        let room = try fixture.room(
            name: "겹친 방",
            revision: 0,
            placements: placements
        )
        let service = MiniHomeStoreServiceFake()
        let store = fixture.store(service: service, operationIDs: ["overlap-save"])
        await store.mount(accountID: fixture.accountA, defaultDraft: room)
        let start = MiniRoomPlacementMetrics.position(
            placements[0],
            in: MiniHomeEditorCanvas.canvasSize
        )
        let draft = try #require(store.draft)
        let selected = try #require(
            MiniRoomPlacementProjector.hitPlacement(
                at: start,
                among: draft.placements,
                in: MiniHomeEditorCanvas.canvasSize
            )
        )
        let destination = try MiniHomePosition(normalizedX: 0.2, normalizedY: 0.8)

        // When
        try store.moveDraftPlacement(id: selected.id, to: destination)
        await store.save()
        let remounted = fixture.store(service: service, operationIDs: [])
        await remounted.mount(accountID: fixture.accountA, defaultDraft: nil)

        // Then
        let restored = try #require(remounted.committed)
        let moved = try #require(restored.placements.first { $0.id == selected.id })
        #expect(selected.id.rawValue == "placement-3")
        #expect(moved.normalizedX == destination.normalizedX)
        #expect(moved.normalizedY == destination.normalizedY)
        #expect(
            restored.placements
                .filter { $0.id != selected.id }
                .allSatisfy { $0.normalizedX == 0.5 && $0.normalizedY == 0.55 }
        )
        #expect(
            restored.placements.map(\.id)
                == MiniHomeCanonicalEncoding.sortedPlacements(placements).map(\.id)
        )
    }

    @Test
    func shareExportUsesTheEditorProjectionForIdentityOrderAndFrames() throws {
        let placements = try overlappingPlacements()
        let room = try MiniHome(
            id: MiniHomeID.parse("overlap-share-room"),
            name: "겹친 공유 방",
            placements: placements,
            revision: Revision.parse(3),
            updatedAt: Instant.parse("2026-08-25T00:00:00Z")
        )
        let result = try #require(MiniHomeShareRenderer().render(room: room))
        let editorProjection = MiniRoomPlacementProjector.resolved(
            placements: placements,
            in: MiniHomeEditorCanvas.canvasSize
        )

        #expect(result.placementProjection == editorProjection)
        #expect(result.placementProjection.map(\.placement.id.rawValue) == [
            "placement-1", "placement-2", "placement-3"
        ])
        #expect(result.placementProjection.allSatisfy {
            $0.hitFrame.width == MiniRoomPlacementMetrics.hitSide
                && $0.hitFrame.height == MiniRoomPlacementMetrics.hitSide
        })
    }

    private func overlappingPlacements() throws -> [MiniHomePlacement] {
        try [
            placement(id: "placement-2", zIndex: 1),
            placement(id: "placement-1", zIndex: 0),
            placement(id: "placement-3", zIndex: 2)
        ]
    }

    private func placement(id: String, zIndex: Int) throws -> MiniHomePlacement {
        try MiniHomePlacement(
            id: PlacementID.parse(id),
            plantID: PersonalPlantID.parse("plant-\(id)"),
            itemID: nil,
            normalizedX: 0.5,
            normalizedY: 0.55,
            zIndex: zIndex
        )
    }
}
