import PlanteriorDomain

/// One card in the Figma `items-selector-panel`: a square asset tile plus a
/// caption. Plant entries come from the registered collection, every other
/// category from owned inventory, so the tray never invents content.
struct MiniRoomTrayEntry: Identifiable, Equatable {
    enum Target: Equatable {
        case plant(PersonalPlantID)
        case item(ItemID)
    }

    let target: Target
    let name: String
    let asset: FigmaAsset

    var id: String {
        switch target {
        case let .plant(plantID): "plant-\(plantID.rawValue)"
        case let .item(itemID): "item-\(itemID.rawValue)"
        }
    }

    var plantID: PersonalPlantID? {
        guard case let .plant(plantID) = target else {
            return nil
        }
        return plantID
    }

    var itemID: ItemID? {
        guard case let .item(itemID) = target else {
            return nil
        }
        return itemID
    }
}

/// Stable asset selection for placed and tray-listed plants. Named species use
/// the Figma miniature exported for them; anything else keys on the personal
/// plant identifier so a miniature stays identical across relaunches,
/// reordering, and account remounts.
enum MiniRoomPlantPresentation {
    static let assets: [FigmaAsset] = [
        .roomPlant01, .roomPlant02, .roomPlant03, .roomPlant04, .roomPlant05
    ]

    /// `myroom-editor` tray order: 몬스테라 / 스투키 / 산세베리아 / 아레카야자 / 고무나무.
    private static let namedAssets: [String: FigmaAsset] = [
        "몬스테라": .roomPlant03,
        "스투키": .roomPlant05,
        "산세베리아": .roomPlant04,
        "아레카야자": .roomPlant02,
        "고무나무": .roomPlant01
    ]

    static func asset(for plantID: PersonalPlantID) -> FigmaAsset {
        assets[stableIndex(for: plantID.rawValue, count: assets.count)]
    }

    static func asset(for plantID: PersonalPlantID, named name: String) -> FigmaAsset {
        namedAssets[name] ?? asset(for: plantID)
    }

    static func stableIndex(for value: String, count: Int) -> Int {
        let hash = value.utf8.reduce(UInt64(1_469_598_103_934_665_603)) {
            ($0 ^ UInt64($1)) &* 1_099_511_628_211
        }
        return Int(hash % UInt64(count))
    }
}
