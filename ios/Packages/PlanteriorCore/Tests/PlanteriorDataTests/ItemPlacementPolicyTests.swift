import PlanteriorData
import PlanteriorDomain
import Testing

struct ItemPlacementPolicyTests: InventoryPolicyFixtureProviding {
    @Test
    func deniesUnownedApply() throws {
        let furniture = try item(
            id: "item-chair",
            name: "의자",
            category: .furniture
        )
        let position = try MiniHomePosition(
            normalizedX: 0.5,
            normalizedY: 0.5
        )

        #expect(throws: ItemPlacementError.unownedItem) {
            try ItemPlacementCoordinator.apply(
                item: furniture,
                ownedItems: [],
                placements: [],
                catalogItems: [furniture],
                position: position
            )
        }
    }

    @Test
    func enforcesCategoryCaps() throws {
        try assertCategoryCap(.background, limit: 1)
        try assertCategoryCap(.furniture, limit: 10)
        try assertCategoryCap(.decoration, limit: 10)
    }

    @Test
    func removalPreservesOwnershipAndClearsAppliedState() throws {
        let owned = try ownedItem(id: "item-lamp", applied: true)
        let placed = try placement(
            id: "placement-lamp",
            itemID: "item-lamp"
        )
        let result = ItemPlacementCoordinator.remove(
            itemID: owned.itemID,
            ownedItems: [owned],
            placements: [placed]
        )

        #expect(result.placements.isEmpty)
        #expect(result.ownedItems.count == 1)
        #expect(result.ownedItems.first?.itemID == owned.itemID)
        #expect(result.ownedItems.first?.applied == false)
    }

    @Test
    func rejectsMissingAndAmbiguousPlacementTargets() throws {
        let placementID = try PlacementID.parse("placement-invalid")
        let plantID = try PersonalPlantID.parse("plant-a")
        let itemID = try ItemID.parse("item-a")

        #expect(throws: MiniHomePlacementError.invalidTarget) {
            try MiniHomePlacement(
                id: placementID,
                plantID: nil,
                itemID: nil,
                normalizedX: 0.5,
                normalizedY: 0.5,
                zIndex: 0
            )
        }
        #expect(throws: MiniHomePlacementError.invalidTarget) {
            try MiniHomePlacement(
                id: placementID,
                plantID: plantID,
                itemID: itemID,
                normalizedX: 0.5,
                normalizedY: 0.5,
                zIndex: 0
            )
        }
    }

    @Test
    func allocatesFirstCollisionFreePlacementID() throws {
        let candidate = try item(
            id: "item-new",
            name: "새 아이템",
            category: .decoration
        )
        let owned = try ownedItem(id: "item-new", applied: false)
        let firstPlacement = try placement(
            id: "placement-1",
            itemID: "item-1"
        )
        let thirdPlacement = try placement(
            id: "placement-3",
            itemID: "item-3"
        )
        let firstItem = try item(
            id: "item-1",
            name: "기존 1",
            category: .decoration
        )
        let thirdItem = try item(
            id: "item-3",
            name: "기존 3",
            category: .decoration
        )
        let result = try ItemPlacementCoordinator.apply(
            item: candidate,
            ownedItems: [owned],
            placements: [firstPlacement, thirdPlacement],
            catalogItems: [candidate, firstItem, thirdItem],
            position: MiniHomePosition(
                normalizedX: 0.5,
                normalizedY: 0.5
            )
        )

        #expect(result.placements.last?.id.rawValue == "placement-2")
    }
}
