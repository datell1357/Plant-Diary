import CoreGraphics
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

    #if DEBUG
        /// The canvas uses three independently movable props exported from the
        /// room artwork; the tray uses the five authenticated 70pt selector
        /// images. Keeping both maps fixture-only prevents QA presentation from
        /// changing production's ID-stable authoritative projection.
        static let referenceTrayAssets: [FigmaAsset] = [
            .roomTrayPlant03, .roomTrayPlant05, .roomTrayPlant04,
            .roomTrayPlant02, .roomTrayPlant01
        ]

        private static let fixtureCanvasAssets: [String: FigmaAsset] = [
            "figma-room-plant-0": .roomPlant01,
            "figma-room-plant-1": .roomPlant02,
            "figma-room-plant-2": .roomPlant03
        ]

        private static let fixtureTrayAssets = Dictionary(
            uniqueKeysWithValues: zip(
                (0 ..< referenceTrayAssets.count).map {
                    "figma-room-plant-\($0)"
                },
                referenceTrayAssets
            )
        )

        private static let fixtureVisualSizes: [String: CGSize] = [
            "figma-room-plant-0": CGSize(width: 50, height: 72),
            "figma-room-plant-1": CGSize(width: 36, height: 67),
            "figma-room-plant-2": CGSize(width: 35, height: 56)
        ]
    #endif

    static func asset(for plantID: PersonalPlantID) -> FigmaAsset {
        #if DEBUG
            if let fixtureAsset = fixtureCanvasAssets[plantID.rawValue] {
                return fixtureAsset
            }
        #endif
        return assets[stableIndex(for: plantID.rawValue, count: assets.count)]
    }

    static func trayAsset(for plantID: PersonalPlantID) -> FigmaAsset {
        #if DEBUG
            if let fixtureAsset = fixtureTrayAssets[plantID.rawValue] {
                return fixtureAsset
            }
        #endif
        return asset(for: plantID)
    }

    static func referenceVisualSize(for plantID: PersonalPlantID) -> CGSize? {
        #if DEBUG
            return fixtureVisualSizes[plantID.rawValue]
        #else
            return nil
        #endif
    }

    static func stableIndex(for value: String, count: Int) -> Int {
        let hash = value.utf8.reduce(UInt64(1_469_598_103_934_665_603)) {
            ($0 ^ UInt64($1)) &* 1_099_511_628_211
        }
        return Int(hash % UInt64(count))
    }
}
