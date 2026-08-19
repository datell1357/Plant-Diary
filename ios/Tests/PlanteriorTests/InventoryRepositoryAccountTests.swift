@testable import Planterior
import Testing

@MainActor
struct InventoryRepositoryAccountTests {
    @Test
    func accountRemountRestoresOnlyThatAccountsInventory() throws {
        let fixture = try InventoryRepositoryFixture()
        let repository = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: true
        )
        let item = try fixture.item(id: "item-account")
        repository.mount(accountID: fixture.accountA)
        repository.replaceFixture(catalog: [item], ownedItems: [])
        #expect(
            repository.acquire(
                itemID: item.id,
                metConditions: []
            ) == .acquired
        )

        repository.mount(accountID: fixture.accountB)
        #expect(repository.catalog.isEmpty)
        #expect(repository.ownedItems.isEmpty)
        repository.replaceFixture(catalog: [item], ownedItems: [])

        repository.mount(accountID: fixture.accountA)
        #expect(repository.ownedItems.map(\.itemID) == [item.id])
    }
}
