import PlanteriorData
import PlanteriorDomain
import Testing

struct InventoryPolicyTests: InventoryPolicyFixtureProviding {
    @Test
    func filtersPublicCatalogAndReportsAcquisitionEligibility() throws {
        let ownedID = try ItemID.parse("item-owned")
        let entries = try InventoryCatalogPolicy.entries(
            items: [
                item(
                    id: "item-unmet",
                    name: "벤치",
                    category: .furniture,
                    condition: "watered-10"
                ),
                item(
                    id: "item-private",
                    name: "비공개",
                    category: .furniture,
                    publicationState: .draft
                ),
                item(
                    id: "item-owned",
                    name: "의자",
                    category: .furniture
                ),
                item(
                    id: "item-met",
                    name: "테이블",
                    category: .furniture,
                    condition: "registered-plant"
                )
            ],
            ownedItemIDs: [ownedID],
            metConditions: ["registered-plant"],
            category: .furniture
        )

        #expect(entries.map(\.item.name) == ["벤치", "의자", "테이블"])
        #expect(entries.map(\.eligibility) == [
            .conditionNotMet("watered-10"),
            .alreadyOwned,
            .eligible
        ])
    }

    @Test
    func paginatesDeterministicallyWithoutDuplicates() throws {
        var items: [ShopItem] = []
        for index in 1 ... 5 {
            try items.append(
                item(
                    id: "item-\(index)",
                    name: "아이템 \(index)",
                    category: .decoration
                )
            )
        }
        let entries = InventoryCatalogPolicy.entries(
            items: items,
            ownedItemIDs: [],
            metConditions: [],
            category: nil
        )

        let first = InventoryCatalogPolicy.page(
            entries: entries,
            after: nil,
            limit: 2
        )
        let second = InventoryCatalogPolicy.page(
            entries: entries,
            after: first.nextCursor,
            limit: 2
        )
        let third = InventoryCatalogPolicy.page(
            entries: entries,
            after: second.nextCursor,
            limit: 2
        )

        #expect(first.entries.map(\.item.id.rawValue) == ["item-1", "item-2"])
        #expect(second.entries.map(\.item.id.rawValue) == ["item-3", "item-4"])
        #expect(third.entries.map(\.item.id.rawValue) == ["item-5"])
        #expect(third.nextCursor == nil)
    }
}
