import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct MiniHomeRoomAccessibilityProjectionTests {
    @Test
    func persistedPlacementsKeepUniqueSpokenIdentityValueAndOrderAfterRemount() async throws {
        let storeFixture = try MiniHomeStoreFixture()
        let now = try Instant.parse("2026-08-11T01:00:00Z")
        let fixture = try roomFixture(now: now)
        assertSpokenProjection(
            MiniRoomPlacementProjector.ordered(
                placements: fixture.room.placements
            ),
            hasOrder: fixture.expectedOrder
        )

        let service = MiniHomeStoreServiceFake()
        let firstMount = storeFixture.store(
            service: service,
            operationIDs: ["voice-save"]
        )
        await firstMount.mount(
            accountID: storeFixture.accountA,
            defaultDraft: fixture.room
        )
        await firstMount.save()
        let remounted = storeFixture.store(service: service, operationIDs: [])
        await remounted.mount(accountID: storeFixture.accountA, defaultDraft: nil)
        let restored = try #require(remounted.committed)

        // Editor, committed MiniHome, and Home all consume this shared order.
        let editorOrder = MiniRoomPlacementProjector.ordered(
            placements: restored.placements
        )
        let committedOrder = MiniRoomPlacementProjector.ordered(
            placements: restored.placements
        )
        let homeOrder = MiniRoomPlacementProjector.ordered(
            placements: restored.placements
        )
        assertSpokenProjection(editorOrder, hasOrder: fixture.expectedOrder)
        #expect(committedOrder == editorOrder)
        #expect(homeOrder == editorOrder)
    }

    private func roomFixture(
        now: Instant
    ) throws -> (room: MiniHome, expectedOrder: [String]) {
        let repeatedPlantID = try PersonalPlantID.parse("figma-room-plant-0")
        let itemID = try ItemID.parse("item-mini-shelf")
        let placements = try [
            placement(
                id: "voice-plant-upper",
                plantID: repeatedPlantID,
                itemID: nil,
                normalizedY: 0.3,
                zIndex: 2
            ),
            placement(
                id: "voice-item-middle",
                plantID: nil,
                itemID: itemID,
                normalizedY: 0.5,
                zIndex: 0
            ),
            placement(
                id: "voice-plant-lower",
                plantID: repeatedPlantID,
                itemID: nil,
                normalizedY: 0.8,
                zIndex: 1
            )
        ]
        return try (
            MiniHome(
                id: MiniHomeID.parse("voice-room"),
                name: "voice-room",
                placements: placements,
                revision: .zero,
                updatedAt: now
            ),
            ["voice-item-middle", "voice-plant-lower", "voice-plant-upper"]
        )
    }

    private func placement(
        id: String,
        plantID: PersonalPlantID?,
        itemID: ItemID?,
        normalizedY: Double,
        zIndex: Int
    ) throws -> MiniHomePlacement {
        try MiniHomePlacement(
            id: PlacementID.parse(id),
            plantID: plantID,
            itemID: itemID,
            normalizedX: 0.5,
            normalizedY: normalizedY,
            zIndex: zIndex
        )
    }

    private func assertSpokenProjection(
        _ placements: [MiniHomePlacement],
        hasOrder expectedOrder: [String]
    ) {
        let labels = placements.map(MiniRoomPlacementPresentation.accessibilityLabel)
        let values = placements.map(MiniRoomPlacementPresentation.accessibilityValue)
        #expect(placements.map(\.id.rawValue) == expectedOrder)
        #expect(Set(labels).count == placements.count)
        #expect(Set(values).count == placements.count)
        #expect(labels.allSatisfy { !$0.contains("배치된 식물") })
        #expect(labels.allSatisfy { !$0.contains("배치된 소품") })
        #expect(values.allSatisfy { $0.contains("가로") && $0.contains("세로") })
    }
}
