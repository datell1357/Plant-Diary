import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct ItemPlacementCoordinatorTests {
    @Test
    func applyAndRemovePersistRoomAndPreserveOwnership() throws {
        let fixture = try InventoryPlacementFixture()
        let miniHomeRepository = try fixture.seedRoom()
        let inventory = fixture.inventory()
        inventory.replaceFixture(
            catalog: [fixture.item],
            ownedItems: [fixture.ownedItem(applied: false)]
        )
        let service = InventoryPlacementService(
            defaults: fixture.defaults
        )
        fixture.verifyApply(
            service: service,
            inventory: inventory,
            miniHome: miniHomeRepository
        )
        fixture.verifyRemoval(
            service: service,
            inventory: inventory,
            miniHome: miniHomeRepository
        )
    }

    @Test
    func missingRoomPreflightPreservesInventory() throws {
        let fixture = try InventoryPlacementFixture()
        let inventory = fixture.inventory()
        inventory.replaceFixture(
            catalog: [fixture.item],
            ownedItems: [fixture.ownedItem(applied: false)]
        )

        let outcome = InventoryPlacementService(
            defaults: fixture.defaults
        ).toggle(
            item: fixture.item,
            inventory: inventory,
            accountID: fixture.accountID,
            now: fixture.now
        )

        #expect(outcome == .unavailable)
        #expect(inventory.ownedItems.first?.applied == false)
    }
}
