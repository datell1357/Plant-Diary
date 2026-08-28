import PlanteriorDomain

/// Localized visual identities are derived only from persisted IDs and their
/// stable asset projection, so VoiceOver does not depend on transient tray data.
enum MiniRoomPlacementIdentity {
    static func plantName(for plantID: PersonalPlantID) -> String {
        #if DEBUG
            if let fixtureName = fixturePlantNames[plantID.rawValue] {
                return fixtureName
            }
        #endif
        return plantNamesByAsset[MiniRoomPlantPresentation.asset(for: plantID)]
            ?? "등록 식물"
    }

    static func itemName(for itemID: ItemID, asset: FigmaAsset) -> String {
        knownItemNames[itemID.rawValue]
            ?? itemNamesByAsset[asset]
            ?? "보유 소품"
    }

    #if DEBUG
        private static let fixturePlantNames: [String: String] = [
            "figma-room-plant-0": "몬스테라",
            "figma-room-plant-1": "스투키",
            "figma-room-plant-2": "다육이",
            "figma-room-plant-3": "아레카야자",
            "figma-room-plant-4": "고무나무"
        ]
    #endif

    private static let plantNamesByAsset: [FigmaAsset: String] = [
        .roomPlant01: "몬스테라",
        .roomPlant02: "스투키",
        .roomPlant03: "다육이",
        .roomPlant04: "산세베리아",
        .roomPlant05: "고무나무"
    ]

    private static let knownItemNames: [String: String] = [
        "item-christmas-tree": "크리스마스 트리",
        "item-green-wall": "체크무늬 커튼 창문",
        "item-succulent-pot": "귀여운 다육이 화분",
        "item-lamp": "스탠드 조명",
        "item-cozy-rug": "포근한 러그",
        "item-mini-shelf": "미니 책장",
        "item-vintage-lamp": "빈티지 스탠드 조명",
        "item-small-rug": "작은 러그",
        "item-cushion": "쿠션",
        "item-flower-stand": "꽃 화분 받침대",
        "item-autumn-frame": "가을 단풍 벽장식",
        "item-chair": "의자",
        "item-window-frame": "창문 프레임",
        "item-wall-art": "벽 장식"
    ]

    private static let itemNamesByAsset: [FigmaAsset: String] = [
        .storageItem00: "크리스마스 트리",
        .storageItem01: "체크무늬 커튼 창문",
        .storageItem02: "귀여운 다육이 화분",
        .storageItem03: "스탠드 조명",
        .storageItem04: "포근한 러그",
        .storageItem05: "미니 책장",
        .storageItem06: "빈티지 스탠드 조명",
        .storageItem07: "작은 러그",
        .storageItem08: "쿠션",
        .storageItem09: "꽃 화분 받침대",
        .storageItem10: "가을 단풍 벽장식",
        .storageItem11: "의자",
        .storageItem12: "창문 프레임",
        .storageItem13: "벽 장식"
    ]
}
