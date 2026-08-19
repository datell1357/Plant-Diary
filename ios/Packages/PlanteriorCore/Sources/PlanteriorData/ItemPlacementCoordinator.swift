import PlanteriorDomain

public enum ItemPlacementError: Error, Equatable, Sendable {
    case unownedItem
    case alreadyApplied
    case categoryLimitReached
}

public struct ItemPlacementResult: Equatable, Sendable {
    public let ownedItems: [OwnedItem]
    public let placements: [MiniHomePlacement]
}

public enum ItemPlacementCoordinator {
    public static func apply(
        item: ShopItem,
        ownedItems: [OwnedItem],
        placements: [MiniHomePlacement],
        catalogItems: [ShopItem],
        position: MiniHomePosition
    ) throws -> ItemPlacementResult {
        guard let ownedIndex = ownedItems.firstIndex(
            where: { $0.itemID == item.id }
        ) else {
            throw ItemPlacementError.unownedItem
        }
        guard !placements.contains(
            where: { $0.itemID == item.id }
        ) else {
            throw ItemPlacementError.alreadyApplied
        }
        let categoryByID = Dictionary(
            uniqueKeysWithValues: catalogItems.map {
                ($0.id, $0.category)
            }
        )
        let categoryCount = placements.lazy.filter {
            guard let itemID = $0.itemID else {
                return false
            }
            return categoryByID[itemID] == item.category
        }.count
        guard categoryCount < limit(for: item.category) else {
            throw ItemPlacementError.categoryLimitReached
        }
        let placementID = try MiniHomeGeometry.nextPlacementID(
            existing: placements.map(\.id)
        )
        let placement = try MiniHomePlacement(
            id: placementID,
            plantID: nil,
            itemID: item.id,
            normalizedX: position.normalizedX,
            normalizedY: position.normalizedY,
            zIndex: (placements.map(\.zIndex).max() ?? -1) + 1
        )
        var updatedOwnedItems = ownedItems
        let owned = ownedItems[ownedIndex]
        updatedOwnedItems[ownedIndex] = OwnedItem(
            itemID: owned.itemID,
            acquiredAt: owned.acquiredAt,
            applied: true,
            revision: owned.revision
        )
        return ItemPlacementResult(
            ownedItems: updatedOwnedItems,
            placements: placements + [placement]
        )
    }

    public static func remove(
        itemID: ItemID,
        ownedItems: [OwnedItem],
        placements: [MiniHomePlacement]
    ) -> ItemPlacementResult {
        let updatedOwnedItems = ownedItems.map { owned in
            guard owned.itemID == itemID else {
                return owned
            }
            return OwnedItem(
                itemID: owned.itemID,
                acquiredAt: owned.acquiredAt,
                applied: false,
                revision: owned.revision
            )
        }
        return ItemPlacementResult(
            ownedItems: updatedOwnedItems,
            placements: placements.filter { $0.itemID != itemID }
        )
    }

    private static func limit(for category: ItemCategory) -> Int {
        switch category {
        case .background: 1
        case .furniture, .decoration: 10
        }
    }
}
