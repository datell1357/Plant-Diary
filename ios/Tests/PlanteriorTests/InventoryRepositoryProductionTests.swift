import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct InventoryRepositoryProductionTests {
    @Test
    func debugLocalCatalogAcquisitionPersistsOnlyForTheSignedInAccount() async throws {
        let fixture = try InventoryRepositoryFixture()
        let repository = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: true
        )
        repository.mount(accountID: fixture.accountA)

        #expect(repository.ownedItems.isEmpty)
        let lamp = try #require(
            repository.catalog.first { $0.id.rawValue == "item-vintage-lamp" }
        )
        #expect(
            await repository.acquire(itemID: lamp.id, metConditions: []) == .acquired
        )

        repository.mount(accountID: fixture.accountB)
        #expect(repository.ownedItems.isEmpty)
        #expect(repository.catalog.contains { $0.id == lamp.id })

        repository.mount(accountID: fixture.accountA)
        #expect(repository.ownedItems.map(\.itemID) == [lamp.id])
    }

    @Test
    func signedOutCatalogCannotCreateOwnership() async throws {
        let fixture = try InventoryRepositoryFixture()
        let repository = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: true
        )
        repository.mount(accountID: nil)
        let item = try #require(repository.catalog.first)

        #expect(
            await repository.acquire(itemID: item.id, metConditions: [])
                == .failed(.notAuthenticated)
        )
        #expect(repository.ownedItems.isEmpty)
        #expect(fixture.defaults.data(forKey: repository.persistenceKey) == nil)
    }
}
