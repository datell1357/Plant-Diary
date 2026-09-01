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
        let expectedFilters: [ExpectedFilter] = [
            .init(id: "item-mini-shelf", category: .furniture, filter: .furniture),
            .init(id: "item-small-rug", category: .decoration, filter: .floor),
            .init(id: "item-window-frame", category: .decoration, filter: .wall),
            .init(id: "item-flower-stand", category: .furniture, filter: .furniture),
            .init(id: "item-lamp", category: .decoration, filter: .decoration),
            .init(id: "item-wall-art", category: .decoration, filter: .wall),
            .init(id: "item-chair", category: .furniture, filter: .furniture),
            .init(id: "item-cushion", category: .decoration, filter: .decoration),
            .init(id: "item-book-cart", category: .furniture, filter: .furniture),
            .init(id: "item-plant-rack", category: .furniture, filter: .furniture),
            .init(id: "item-round-mat", category: .decoration, filter: .floor),
            .init(id: "item-cozy-rug", category: .furniture, filter: .floor),
            .init(id: "item-vintage-lamp", category: .decoration, filter: .decoration),
            .init(id: "item-green-wall", category: .background, filter: .wall),
            .init(id: "item-succulent-pot", category: .decoration, filter: .decoration),
            .init(id: "item-christmas-tree", category: .decoration, filter: .decoration),
            .init(id: "item-autumn-frame", category: .decoration, filter: .wall)
        ]
        let coveredIDs = Set(expectedFilters.map(\.id))
        #expect(
            Set(InventoryCatalog.items().map(\.id.rawValue))
                .isSubset(of: coveredIDs)
        )

        for expected in expectedFilters {
            let candidate = try item(id: expected.id, category: expected.category)
            let matches = InventoryRoomFilter.allCases.filter { $0.includes(candidate) }
            #expect(
                matches == [expected.filter],
                "\(expected.id) must match exactly \(expected.filter)"
            )
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
        try ShopItem(
            id: ItemID.parse(id),
            name: id,
            category: category,
            assetPath: "items/\(id).png",
            acquisitionCondition: nil,
            publicationState: .public,
            revision: Revision.parse(1)
        )
    }

    private struct ExpectedFilter {
        let id: String
        let category: ItemCategory
        let filter: InventoryRoomFilter
    }
}
