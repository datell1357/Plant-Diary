@testable import Planterior
import PlanteriorData
import Testing

@MainActor
struct MiniHomeLivePlacementPersistenceTests {
    @Test
    func canonicalPlacementMovePersistsOnlyForItsAccount() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let canonical = try MiniHomeView.figmaReferencePlacements
        let service = MiniHomeStoreServiceFake()
        let store = fixture.store(service: service, operationIDs: ["move-operation"])
        let draft = try fixture.room(
            name: "Figma 방",
            revision: 0,
            placements: canonical
        )
        await store.mount(accountID: fixture.accountA, defaultDraft: draft)
        let movedID = try #require(canonical.first?.id)
        let destination = try MiniHomePosition(
            normalizedX: 0.25,
            normalizedY: 0.75
        )

        // When
        try store.moveDraftPlacement(id: movedID, to: destination)
        await store.save()

        // Then
        let otherStore = fixture.store(service: service, operationIDs: [])
        await otherStore.mount(accountID: fixture.accountB, defaultDraft: nil)
        #expect(otherStore.committed == nil)

        let restored = fixture.store(service: service, operationIDs: [])
        await restored.mount(accountID: fixture.accountA, defaultDraft: nil)
        let moved = try #require(
            restored.committed?.placements.first { $0.id == movedID }
        )
        #expect(moved.normalizedX == destination.normalizedX)
        #expect(moved.normalizedY == destination.normalizedY)
        #expect(restored.committed?.placements.map(\.id) == canonical.map(\.id))
    }
}
