import PlanteriorDomain

/// Persistence stores stable domain IDs, not presentation names. Resolving from
/// those IDs keeps the exact prop identity across relaunch and account remount.
enum MiniRoomPlacementPresentation {
    static func asset(for placement: MiniHomePlacement) -> FigmaAsset {
        if let plantID = placement.plantID {
            return MiniRoomPlantPresentation.asset(for: plantID)
        }
        guard let itemID = placement.itemID else {
            return .roomPlant01
        }
        return itemAsset(for: itemID)
    }

    static func accessibilityLabel(for placement: MiniHomePlacement) -> String {
        let identity = if let plantID = placement.plantID {
            "식물 \(plantName(for: plantID))"
        } else if let itemID = placement.itemID {
            "소품 \(itemName(for: itemID))"
        } else {
            "소품"
        }
        return "\(identity), \(spatialIdentity(for: placement)), 배치 식별자 \(placement.id.rawValue)"
    }

    static func accessibilityValue(for placement: MiniHomePlacement) -> String {
        "가로 \(percentage(placement.normalizedX))퍼센트, 세로 \(percentage(placement.normalizedY))퍼센트"
    }

    private static func plantName(for plantID: PersonalPlantID) -> String {
        MiniRoomPlacementIdentity.plantName(for: plantID)
    }

    private static func itemName(for itemID: ItemID) -> String {
        MiniRoomPlacementIdentity.itemName(for: itemID, asset: itemAsset(for: itemID))
    }

    private static func spatialIdentity(for placement: MiniHomePlacement) -> String {
        let horizontal = placement.normalizedX < 1.0 / 3.0
            ? "왼쪽"
            : placement.normalizedX > 2.0 / 3.0 ? "오른쪽" : "가운데"
        let vertical = placement.normalizedY < 1.0 / 3.0
            ? "위쪽"
            : placement.normalizedY > 2.0 / 3.0 ? "아래쪽" : "중간"
        return "\(horizontal) \(vertical)"
    }

    private static func percentage(_ normalized: Double) -> Int {
        Int((normalized * 100).rounded())
    }

    private static func itemAsset(for itemID: ItemID) -> FigmaAsset {
        knownItemAssets[itemID.rawValue] ?? stableItemAssets[
            MiniRoomPlantPresentation.stableIndex(
                for: itemID.rawValue,
                count: stableItemAssets.count
            )
        ]
    }

    private static let knownItemAssets: [String: FigmaAsset] = [
        "item-christmas-tree": .storageItem00,
        "item-green-wall": .storageItem01,
        "item-succulent-pot": .storageItem02,
        "item-lamp": .storageItem03,
        "item-cozy-rug": .storageItem04,
        "item-mini-shelf": .storageItem05,
        "item-vintage-lamp": .storageItem06,
        "item-small-rug": .storageItem07,
        "item-cushion": .storageItem08,
        "item-flower-stand": .storageItem09,
        "item-autumn-frame": .storageItem10,
        "item-chair": .storageItem11,
        "item-window-frame": .storageItem12,
        "item-wall-art": .storageItem13
    ]

    private static let stableItemAssets: [FigmaAsset] = [
        .storageItem00, .storageItem01, .storageItem02, .storageItem03,
        .storageItem04, .storageItem05, .storageItem06, .storageItem07,
        .storageItem08, .storageItem09, .storageItem10, .storageItem11,
        .storageItem12, .storageItem13
    ]
}
