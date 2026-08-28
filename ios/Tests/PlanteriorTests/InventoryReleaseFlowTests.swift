import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct InventoryReleaseFlowTests {
    @Test
    func debugAcquiredCatalogItemReachesWarehouseAndAccountScopedPlacement() async throws {
        // Given
        let fixture = try InventoryRepositoryFixture()
        let inventory = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: true
        )
        inventory.mount(accountID: fixture.accountA)
        let item = try #require(
            inventory.catalog.first { $0.id.rawValue == "item-vintage-lamp" }
        )
        let service = MiniHomeStoreServiceFake()
        let cache = MiniHomeVerifiedCache(defaults: fixture.defaults)
        let miniHome = MiniHomeStore(
            service: service,
            cache: cache,
            makeOperationID: { try OperationID.parse("release-placement") }
        )
        let room = try MiniHome(
            id: MiniHomeID.parse("release-room"),
            name: "초록 방",
            placements: [],
            revision: .zero,
            updatedAt: fixture.now
        )
        await miniHome.mount(accountID: fixture.accountA, defaultDraft: room)
        #expect(
            await inventory.acquire(itemID: item.id, metConditions: []) == .acquired
        )

        // When
        let outcome = await InventoryPlacementService().toggle(
            item: item,
            inventory: inventory,
            miniHome: miniHome
        )

        // Then
        #expect(outcome == .applied)
        #expect(miniHome.committed?.placements.first?.itemID == item.id)
        let restoredInventory = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: true
        )
        restoredInventory.mount(accountID: fixture.accountA)
        restoredInventory.synchronizeAppliedItems(with: miniHome.committed)
        #expect(restoredInventory.ownedItems.first?.itemID == item.id)
        #expect(restoredInventory.ownedItems.first?.applied == true)

        await miniHome.mount(accountID: fixture.accountB, defaultDraft: room)
        restoredInventory.mount(accountID: fixture.accountB)
        restoredInventory.synchronizeAppliedItems(with: miniHome.committed)
        #expect(restoredInventory.ownedItems.isEmpty)
        #expect(miniHome.committed == nil)
    }
}
