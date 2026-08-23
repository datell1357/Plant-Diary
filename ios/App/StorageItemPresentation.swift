import PlanteriorData
import PlanteriorDomain

/// Display-only inventory metadata. Known catalog fixtures use explicit mappings;
/// production items retain a stable ID-based fallback across launches and accounts.
enum StorageItemPresentation {
    private static let assets: [FigmaAsset] = [
        .storageItem00, .storageItem01, .storageItem02, .storageItem03,
        .storageItem04, .storageItem05, .storageItem06, .storageItem07,
        .storageItem08, .storageItem09, .storageItem10, .storageItem11,
        .storageItem12, .storageItem13
    ]

    static func asset(for item: ShopItem) -> FigmaAsset {
        switch item.id.rawValue {
        case "item-christmas-tree": .storageItem00
        case "item-green-wall": .storageItem01
        case "item-succulent-pot": .storageItem02
        case "item-lamp": .storageItem03
        case "item-cozy-rug": .storageItem04
        case "item-mini-shelf": .storageItem05
        case "item-vintage-lamp": .storageItem06
        case "item-small-rug": .storageItem07
        case "item-cushion": .storageItem08
        case "item-flower-stand": .storageItem09
        case "item-autumn-frame": .storageItem10
        case "item-chair": .storageItem11
        case "item-window-frame": .storageItem12
        case "item-wall-art": .storageItem13
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

    static func detailCategoryName(_ category: ItemCategory) -> String {
        category == .decoration ? "장식" : categoryName(category)
    }

    static func description(for item: ShopItem) -> String {
        if item.id.rawValue == "item-lamp" {
            return "따뜻한 빛이 나는 미니 조명이에요. 미니홈피의 어두운 밤을 더욱 아늑하고 분위기 있게 밝혀줍니다."
        }
        switch item.category {
        case .background:
            return "미니홈의 분위기를 편안하게 바꾸는 배경 아이템\u{2060}이에요."
        case .furniture:
            return "식물과 자연스럽게 어울리는 따뜻한 가구 아이템\u{2060}이에요."
        case .decoration:
            return "미니홈에 작은 포인트를 더하는 장식 아이템\u{2060}이에요."
        }
    }

    static func shopBadge(for item: ShopItem) -> String {
        switch item.id.rawValue {
        case "item-green-wall": "🌱 7일 연속 출석"
        case "item-christmas-tree": "❄️ 겨울 시즌 한정"
        case "item-autumn-frame": "🍁 가을 시즌 한정"
        default: ""
        }
    }

    static func isSeasonal(_ item: ShopItem) -> Bool {
        ["item-christmas-tree", "item-autumn-frame"]
            .contains(item.id.rawValue)
    }

    static func shopOrder(_ item: ShopItem) -> Int {
        [
            "item-cozy-rug", "item-vintage-lamp", "item-green-wall",
            "item-succulent-pot", "item-christmas-tree", "item-autumn-frame"
        ].firstIndex(of: item.id.rawValue) ?? .max
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

    static func contextTitle(for item: ShopItem) -> String {
        item.id.rawValue == "item-lamp" ? "내 방 벽면에 걸기" : "내 방에 배치하기"
    }

    static func contextDescription(for item: ShopItem) -> String {
        if item.id.rawValue == "item-lamp" {
            return "몬스테라 뒤쪽 벽면에 설치되어 은은한 후광을 연출합니다."
        }
        return "미니홈 편집에서 위치를 조정할 수 있어요."
    }

    private static func stableIndex(for value: String) -> Int {
        let hash = value.utf8.reduce(UInt64(1_469_598_103_934_665_603)) {
            ($0 ^ UInt64($1)) &* 1_099_511_628_211
        }
        return Int(hash % UInt64(assets.count))
    }
}
