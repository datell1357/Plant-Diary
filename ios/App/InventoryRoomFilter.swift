import PlanteriorDomain

enum InventoryRoomFilter: String, CaseIterable {
    case wall
    case floor
    case furniture
    case decoration

    var title: String {
        switch self {
        case .wall: "벽지"
        case .floor: "바닥"
        case .furniture: "가구"
        case .decoration: "장식"
        }
    }

    func includes(_ item: ShopItem) -> Bool {
        self == Self.category(for: item)
    }

    private static func category(for item: ShopItem) -> Self {
        let itemID = item.id.rawValue
        if wallItemIDs.contains(itemID) {
            return .wall
        }
        if floorItemIDs.contains(itemID) {
            return .floor
        }
        switch item.category {
        case .background:
            return .wall
        case .furniture:
            return .furniture
        case .decoration:
            return .decoration
        }
    }

    private static let wallItemIDs = Set([
        "item-green-wall", "item-window-frame", "item-wall-art",
        "item-autumn-frame"
    ])
    private static let floorItemIDs = Set([
        "item-cozy-rug", "item-small-rug", "item-round-mat"
    ])
}
