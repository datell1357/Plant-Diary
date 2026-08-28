@testable import Planterior
import Testing

@MainActor
struct ItemPlacementCoordinatorTests {
    @Test
    func applyAndRemoveCommitAuthoritativeRoomAndPreserveOwnership() async throws {
        // Given
        let fixture = try InventoryPlacementFixture()
        let service = MiniHomeStoreServiceFake()
        let miniHome = fixture.store(
            service: service,
            operationIDs: ["placement-apply", "placement-remove"]
        )
        try await miniHome.mount(
            accountID: fixture.accountID,
            defaultDraft: fixture.room()
        )
        let inventory = fixture.inventory()
        inventory.replaceFixture(
            catalog: [fixture.item],
            ownedItems: [fixture.ownedItem(applied: false)]
        )

        // When
        let applied = await InventoryPlacementService().toggle(
            item: fixture.item,
            inventory: inventory,
            miniHome: miniHome
        )
        let removed = await InventoryPlacementService().toggle(
            item: fixture.item,
            inventory: inventory,
            miniHome: miniHome
        )

        // Then
        #expect(applied == .applied)
        #expect(removed == .removed)
        #expect(miniHome.committed?.placements.isEmpty == true)
        #expect(miniHome.committed?.revision.rawValue == 2)
        #expect(inventory.ownedItems.first?.itemID == fixture.item.id)
        #expect(inventory.ownedItems.first?.applied == false)
    }

    @Test
    func emptyServerCreatesRoomOnlyThroughExplicitPlacementSave() async throws {
        // Given
        let fixture = try InventoryPlacementFixture()
        let service = MiniHomeStoreServiceFake()
        let miniHome = fixture.store(
            service: service,
            operationIDs: ["placement-first-save"]
        )
        try await miniHome.mount(
            accountID: fixture.accountID,
            defaultDraft: fixture.room()
        )
        let inventory = fixture.inventory()
        inventory.replaceFixture(
            catalog: [fixture.item],
            ownedItems: [fixture.ownedItem(applied: false)]
        )
        #expect(service.requests.isEmpty)

        // When
        let outcome = await InventoryPlacementService().toggle(
            item: fixture.item,
            inventory: inventory,
            miniHome: miniHome
        )

        // Then
        #expect(outcome == .applied)
        #expect(miniHome.committed?.revision.rawValue == 1)
        #expect(miniHome.committed?.placements.first?.itemID == fixture.item.id)
        #expect(inventory.ownedItems.first?.applied == true)
    }
}
