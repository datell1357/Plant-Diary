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
    let cache: MiniHomeVerifiedCache

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
        cache = MiniHomeVerifiedCache(defaults: defaults)
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

    func room() throws -> MiniHome {
        try MiniHome(
            id: MiniHomeID.parse("inventory-room"),
            name: "초록 방",
            placements: [],
            revision: .zero,
            updatedAt: now
        )
    }

    func store(
        service: MiniHomeStoreServiceFake,
        operationIDs: [String]
    ) -> MiniHomeStore {
        var remaining = operationIDs
        return MiniHomeStore(
            service: service,
            cache: cache,
            makeOperationID: {
                try OperationID.parse(remaining.removeFirst())
            }
        )
    }
}
