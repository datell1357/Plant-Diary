import PlanteriorData
import PlanteriorDomain

/// Display-only metadata for storage. Asset selection is stable across ordering,
/// pagination, account remounts, and app launches because it keys on ItemID.
enum StorageItemPresentation {
    private static let assets: [FigmaAsset] = [
        .storageItem00, .storageItem01, .storageItem02, .storageItem03,
        .storageItem04, .storageItem05, .storageItem06, .storageItem07,
        .storageItem08, .storageItem09, .storageItem10, .storageItem11,
        .storageItem12, .storageItem13
    ]

    static func asset(for item: ShopItem) -> FigmaAsset {
        switch item.id.rawValue {
        case "item-green-wall": .storageItem01
        case "item-chair": .storageItem11
        case "item-lamp": .storageItem03
        default: assets[stableIndex(for: item.id.rawValue)]
        }
    }

    static func heroAsset(for item: ShopItem) -> FigmaAsset {
        item.id.rawValue == "item-lamp" ? .storagePreview : asset(for: item)
    }

    static func categoryName(_ category: ItemCategory) -> String {
        switch category {
        case .background: "배경"
        case .furniture: "가구"
        case .decoration: "소품"
        }
    }

    static func description(for item: ShopItem) -> String {
        switch item.category {
        case .background:
            "미니홈의 분위기를 편안하게 바꾸는 배경 아이템\u{2060}이에요."
        case .furniture:
            "식물과 자연스럽게 어울리는 따뜻한 가구 아이템\u{2060}이에요."
        case .decoration:
            "미니홈에 작은 포인트를 더하는 장식 아이템\u{2060}이에요."
        }
    }

    static func eligibilityText(
        _ eligibility: InventoryAcquisitionEligibility
    ) -> String {
        switch eligibility {
        case .eligible: "획득 가능"
        case .conditionNotMet("registered-plant"):
            "조건 미충족 · 식물 등록 필요"
        case .conditionNotMet:
            "조건 미충족 · 조건 확인 필요"
        case .alreadyOwned: "보유 중"
        }
    }

    static func conditionDescription(for item: ShopItem) -> String {
        switch item.acquisitionCondition {
        case "registered-plant":
            "등록한 식물이 있어야 획득할 수 있어요."
        case .some:
            "획득 조건을 확인해 주세요."
        case nil:
            "별도 획득 조건이 없어요."
        }
    }

    private static func stableIndex(for value: String) -> Int {
        let hash = value.utf8.reduce(UInt64(1_469_598_103_934_665_603)) {
            ($0 ^ UInt64($1)) &* 1_099_511_628_211
        }
        return Int(hash % UInt64(assets.count))
    }
}
