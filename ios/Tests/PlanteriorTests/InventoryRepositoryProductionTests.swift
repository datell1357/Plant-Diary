import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct InventoryRepositoryProductionTests {
    @Test
    func productionMountIgnoresLocallyPersistedOwnership() throws {
        let fixture = try InventoryRepositoryFixture()
        let item = try fixture.item(id: "item-forged")
        let local = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: true
        )
        local.mount(accountID: fixture.accountA)
        local.replaceFixture(
            catalog: [item],
            ownedItems: [
                OwnedItem(
                    itemID: item.id,
                    acquiredAt: fixture.now,
                    applied: true,
                    revision: item.revision
                )
            ]
        )

        let production = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: false
        )
        production.mount(accountID: fixture.accountA)

        #expect(production.catalog.isEmpty)
        #expect(production.ownedItems.isEmpty)
    }
}
