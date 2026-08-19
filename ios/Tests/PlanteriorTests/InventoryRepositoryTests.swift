import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct InventoryRepositoryTests {
    @Test
    func productionRepositoryRejectsClientOwnershipCreation() throws {
        let fixture = try InventoryRepositoryFixture()
        let repository = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: false
        )
        repository.mount(accountID: fixture.accountA)
        let publicItem = try fixture.item(id: "item-public")
        repository.replaceFixture(
            catalog: [publicItem],
            ownedItems: []
        )

        let publicID = try ItemID.parse("item-public")
        let outcome = repository.acquire(
            itemID: publicID,
            metConditions: []
        )

        #expect(outcome == .unavailable)
        #expect(repository.ownedItems.isEmpty)
    }

    @Test
    func qaAcquisitionRetriesAndRejectsDuplicatesAndUnmetConditions() throws {
        let fixture = try InventoryRepositoryFixture()
        let repository = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: true,
            failFirstAcquisition: true
        )
        repository.mount(accountID: fixture.accountA)
        let catalog = try [
            fixture.item(id: "item-free"),
            fixture.item(
                id: "item-locked",
                condition: "watered-10"
            )
        ]
        repository.replaceFixture(
            catalog: catalog,
            ownedItems: []
        )

        let freeID = try ItemID.parse("item-free")
        #expect(
            repository.acquire(
                itemID: freeID,
                metConditions: []
            ) == .failed
        )
        #expect(repository.ownedItems.isEmpty)
        #expect(
            repository.acquire(
                itemID: freeID,
                metConditions: []
            ) == .acquired
        )
        #expect(repository.ownedItems.map(\.itemID) == [freeID])
        #expect(
            repository.acquire(
                itemID: freeID,
                metConditions: []
            ) == .alreadyOwned
        )
        let lockedID = try ItemID.parse("item-locked")
        #expect(
            repository.acquire(
                itemID: lockedID,
                metConditions: []
            ) == .conditionNotMet("watered-10")
        )
    }
}

@MainActor
struct InventoryRepositoryFixture {
    let accountA = "inventory-account-a"
    let accountB = "inventory-account-b"
    let defaults: UserDefaults
    let now: Instant

    init() throws {
        let suiteName = "InventoryRepositoryTests-\(UUID())"
        defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        now = try Instant.parse("2026-08-11T00:00:00Z")
    }

    func item(
        id: String,
        condition: String? = nil
    ) throws -> ShopItem {
        try ShopItem(
            id: ItemID.parse(id),
            name: id,
            category: .decoration,
            assetPath: "items/\(id).png",
            acquisitionCondition: condition,
            publicationState: .public,
            revision: Revision.parse(1)
        )
    }
}
