import PlanteriorDomain

/// Shipped local-first catalog. Ownership is never pre-seeded from this catalog.
enum InventoryCatalog {
    static func items() -> [ShopItem] {
        guard let revision = try? Revision.parse(1) else {
            return []
        }
        return descriptors.compactMap { descriptor in
            guard let id = try? ItemID.parse(descriptor.id) else {
                return nil
            }
            return ShopItem(
                id: id,
                name: descriptor.name,
                category: descriptor.category,
                assetPath: "items/\(descriptor.id).png",
                acquisitionCondition: descriptor.condition,
                publicationState: .public,
                revision: revision
            )
        }
    }

    private static let descriptors: [Descriptor] = [
        Descriptor("item-cozy-rug", "포근한 러그", .furniture),
        Descriptor("item-vintage-lamp", "빈티지 스탠드 조명", .decoration),
        Descriptor("item-green-wall", "체크무늬 커튼 창문", .background),
        Descriptor("item-succulent-pot", "귀여운 다육이 화분", .decoration),
        Descriptor(
            "item-christmas-tree",
            "크리스마스 트리",
            .decoration,
            condition: "winter-season"
        ),
        Descriptor(
            "item-autumn-frame",
            "가을 단풍 벽장식",
            .decoration,
            condition: "autumn-season"
        )
    ]

    private struct Descriptor {
        let id: String
        let name: String
        let category: ItemCategory
        let condition: String?

        init(
            _ id: String,
            _ name: String,
            _ category: ItemCategory,
            condition: String? = nil
        ) {
            self.id = id
            self.name = name
            self.category = category
            self.condition = condition
        }
    }
}
