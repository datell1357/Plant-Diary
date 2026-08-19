import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct InventoryPlacementFixture {
    let accountID = "inventory-placement-account"
    let defaults: UserDefaults
    let now: Instant
    let item: ShopItem

    init() throws {
        let suiteName = "ItemPlacementCoordinatorTests-\(UUID())"
        defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        now = try Instant.parse("2026-08-11T00:00:00Z")
        item = try ShopItem(
            id: ItemID.parse("item-chair"),
            name: "원목 의자",
            category: .furniture,
            assetPath: "items/chair.png",
            acquisitionCondition: nil,
            publicationState: .public,
            revision: Revision.parse(1)
        )
    }

    func inventory() -> InventoryRepository {
        let repository = InventoryRepository(
            defaults: defaults,
            now: now,
            allowsLocalAcquisition: true
        )
        repository.mount(accountID: accountID)
        return repository
    }

    func ownedItem(applied: Bool) -> OwnedItem {
        OwnedItem(
            itemID: item.id,
            acquiredAt: now,
            applied: applied,
            revision: item.revision
        )
    }

    func seedRoom() throws -> LocalMiniHomeRepository {
        let repository = LocalMiniHomeRepository(
            accountID: accountID,
            defaults: defaults,
            now: now
        )
        let room = try room()
        guard case .committed = try repository.save(
            draft: room,
            expectedRevision: room.revision
        ) else {
            throw InventoryPlacementFixtureError.seedFailed
        }
        return repository
    }

    func verifyApply(
        service: InventoryPlacementService,
        inventory: InventoryRepository,
        miniHome: LocalMiniHomeRepository
    ) {
        #expect(toggle(service: service, inventory: inventory) == .applied)
        #expect(miniHome.load()?.placements.first?.itemID == item.id)
        #expect(inventory.ownedItems.first?.applied == true)
    }

    func verifyRemoval(
        service: InventoryPlacementService,
        inventory: InventoryRepository,
        miniHome: LocalMiniHomeRepository
    ) {
        #expect(toggle(service: service, inventory: inventory) == .removed)
        #expect(miniHome.load()?.placements.isEmpty == true)
        #expect(inventory.ownedItems.first?.itemID == item.id)
        #expect(inventory.ownedItems.first?.applied == false)
    }

    private func room() throws -> MiniHome {
        try MiniHome(
            id: MiniHomeID.parse("inventory-room"),
            name: "초록 방",
            placements: [],
            revision: Revision.parse(0),
            updatedAt: now
        )
    }

    private func toggle(
        service: InventoryPlacementService,
        inventory: InventoryRepository
    ) -> InventoryPlacementOutcome {
        service.toggle(
            item: item,
            inventory: inventory,
            accountID: accountID,
            now: now
        )
    }
}

private enum InventoryPlacementFixtureError: Error {
    case seedFailed
}
