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
        guard let category = Self.category(for: item) else {
            return false
        }
        return self == category
    }

    private static func category(for item: ShopItem) -> Self? {
        itemCategories[item.id.rawValue]
    }

    private static let itemCategories: [String: Self] = [
        "item-mini-shelf": .furniture,
        "item-small-rug": .floor,
        "item-window-frame": .wall,
        "item-flower-stand": .furniture,
        "item-lamp": .decoration,
        "item-wall-art": .wall,
        "item-chair": .furniture,
        "item-cushion": .decoration,
        "item-book-cart": .furniture,
        "item-plant-rack": .furniture,
        "item-round-mat": .floor,
        "item-cozy-rug": .floor,
        "item-vintage-lamp": .decoration,
        "item-green-wall": .wall,
        "item-succulent-pot": .decoration,
        "item-christmas-tree": .decoration,
        "item-autumn-frame": .wall
    ]
}
