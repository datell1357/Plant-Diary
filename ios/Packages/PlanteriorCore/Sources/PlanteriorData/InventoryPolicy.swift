import PlanteriorDomain

public enum InventoryAcquisitionEligibility: Equatable, Sendable {
    case eligible
    case conditionNotMet(String)
    case alreadyOwned
}

public struct InventoryCatalogEntry: Equatable, Sendable {
    public let item: ShopItem
    public let eligibility: InventoryAcquisitionEligibility
}

public struct InventoryCatalogPage: Equatable, Sendable {
    public let entries: [InventoryCatalogEntry]
    public let nextCursor: String?
}

public enum InventoryCatalogPolicy {
    public static func entries(
        items: [ShopItem],
        ownedItemIDs: Set<ItemID>,
        metConditions: Set<String>,
        category: ItemCategory?
    ) -> [InventoryCatalogEntry] {
        items
            .filter {
                $0.publicationState == .public &&
                    (category == nil || $0.category == category)
            }
            .sorted {
                ($0.name, $0.id.rawValue) <
                    ($1.name, $1.id.rawValue)
            }
            .map { item in
                InventoryCatalogEntry(
                    item: item,
                    eligibility: eligibility(
                        item: item,
                        ownedItemIDs: ownedItemIDs,
                        metConditions: metConditions
                    )
                )
            }
    }

    private static func eligibility(
        item: ShopItem,
        ownedItemIDs: Set<ItemID>,
        metConditions: Set<String>
    ) -> InventoryAcquisitionEligibility {
        if ownedItemIDs.contains(item.id) {
            return .alreadyOwned
        }
        if let condition = unmetCondition(
            item: item,
            metConditions: metConditions
        ) {
            return .conditionNotMet(condition)
        }
        return .eligible
    }

    private static func unmetCondition(
        item: ShopItem,
        metConditions: Set<String>
    ) -> String? {
        guard let condition = item.acquisitionCondition,
              !metConditions.contains(condition)
        else {
            return nil
        }
        return condition
    }
}
