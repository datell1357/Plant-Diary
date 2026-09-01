@testable import Planterior
import PlanteriorDomain
import Testing

struct InventoryRoomFilterTests {
    @Test
    func roomFiltersKeepTheWarehouseOrderAndTitles() {
        #expect(InventoryRoomFilter.allCases == [.wall, .floor, .furniture, .decoration])
        #expect(InventoryRoomFilter.allCases.map(\.title) == ["벽지", "바닥", "가구", "장식"])
    }

    @Test
    func bundledAndQAWarehouseItemsMatchExactlyOneRoomFilter() throws {
        let expectedFilters: [(String, ItemCategory, InventoryRoomFilter)] = [
            ("item-mini-shelf", .furniture, .furniture),
            ("item-small-rug", .decoration, .floor),
            ("item-window-frame", .decoration, .wall),
            ("item-flower-stand", .furniture, .furniture),
            ("item-lamp", .decoration, .decoration),
            ("item-wall-art", .decoration, .wall),
            ("item-chair", .furniture, .furniture),
            ("item-cushion", .decoration, .decoration),
            ("item-book-cart", .furniture, .furniture),
            ("item-plant-rack", .furniture, .furniture),
            ("item-round-mat", .decoration, .floor),
            ("item-cozy-rug", .furniture, .floor),
            ("item-vintage-lamp", .decoration, .decoration),
            ("item-green-wall", .background, .wall),
            ("item-succulent-pot", .decoration, .decoration),
            ("item-christmas-tree", .decoration, .decoration),
            ("item-autumn-frame", .decoration, .wall)
        ]
        let coveredIDs = Set(expectedFilters.map(\.0))
        #expect(
            Set(InventoryCatalog.items().map { $0.id.rawValue })
                .isSubset(of: coveredIDs)
        )

        for (id, category, expectedFilter) in expectedFilters {
            let candidate = try item(id: id, category: category)
            let matches = InventoryRoomFilter.allCases.filter { $0.includes(candidate) }
            #expect(matches == [expectedFilter], "\(id) must match exactly \(expectedFilter)")
        }
    }

    @Test
    func unknownItemsFallBackToTheirBroadCategoryExactlyOnce() throws {
        for (category, expectedFilter) in [
            (ItemCategory.background, InventoryRoomFilter.wall),
            (.furniture, .furniture),
            (.decoration, .decoration)
        ] {
            let unknownItem = try item(
                id: "item-future-\(category.rawValue.lowercased())",
                category: category
            )
            let matches = InventoryRoomFilter.allCases.filter { $0.includes(unknownItem) }
            #expect(matches == [expectedFilter])
        }
    }

    private func item(id: String, category: ItemCategory) throws -> ShopItem {
        ShopItem(
            id: try ItemID.parse(id),
            name: id,
            category: category,
            assetPath: "items/\(id).png",
            acquisitionCondition: nil,
            publicationState: .public,
            revision: try Revision.parse(1)
        )
    }
}
