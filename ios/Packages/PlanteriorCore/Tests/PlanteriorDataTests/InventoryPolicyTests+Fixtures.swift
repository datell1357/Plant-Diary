import PlanteriorData
import PlanteriorDomain
import Testing

protocol InventoryPolicyFixtureProviding {}

extension InventoryPolicyFixtureProviding {
    func item(
        id: String,
        name: String,
        category: ItemCategory,
        condition: String? = nil,
        publicationState: PublicationState = .public
    ) throws -> ShopItem {
        try ShopItem(
            id: ItemID.parse(id),
            name: name,
            category: category,
            assetPath: "items/\(id).png",
            acquisitionCondition: condition,
            publicationState: publicationState,
            revision: Revision.parse(1)
        )
    }

    func ownedItem(
        id: String,
        applied: Bool
    ) throws -> OwnedItem {
        try OwnedItem(
            itemID: ItemID.parse(id),
            acquiredAt: Instant.parse("2026-08-11T00:00:00Z"),
            applied: applied,
            revision: Revision.parse(1)
        )
    }

    func placement(
        id: String,
        itemID: String
    ) throws -> MiniHomePlacement {
        try MiniHomePlacement(
            id: PlacementID.parse(id),
            plantID: nil,
            itemID: ItemID.parse(itemID),
            normalizedX: 0.5,
            normalizedY: 0.5,
            zIndex: 0
        )
    }

    func assertCategoryCap(
        _ category: ItemCategory,
        limit: Int
    ) throws {
        let candidate = try item(
            id: "item-candidate",
            name: "후보",
            category: category
        )
        let owned = try ownedItem(
            id: candidate.id.rawValue,
            applied: false
        )
        var catalog = [candidate]
        var placements: [MiniHomePlacement] = []
        for index in 1 ..< limit {
            let existing = try item(
                id: "item-\(index)",
                name: "기존 \(index)",
                category: category
            )
            catalog.append(existing)
            try placements.append(
                placement(
                    id: "placement-\(index)",
                    itemID: existing.id.rawValue
                )
            )
        }
        let position = try MiniHomePosition(
            normalizedX: 0.5,
            normalizedY: 0.5
        )
        let applied = try ItemPlacementCoordinator.apply(
            item: candidate,
            ownedItems: [owned],
            placements: placements,
            catalogItems: catalog,
            position: position
        )
        #expect(applied.placements.count == limit)
        try assertOverflow(
            category: category,
            owned: owned,
            catalog: catalog,
            placements: applied.placements,
            position: position
        )
    }

    private func assertOverflow(
        category: ItemCategory,
        owned: OwnedItem,
        catalog: [ShopItem],
        placements: [MiniHomePlacement],
        position: MiniHomePosition
    ) throws {
        let overflow = try item(
            id: "item-overflow",
            name: "초과",
            category: category
        )
        let overflowOwned = try ownedItem(
            id: overflow.id.rawValue,
            applied: false
        )
        #expect(throws: ItemPlacementError.categoryLimitReached) {
            try ItemPlacementCoordinator.apply(
                item: overflow,
                ownedItems: [owned, overflowOwned],
                placements: placements,
                catalogItems: catalog + [overflow],
                position: position
            )
        }
    }
}
