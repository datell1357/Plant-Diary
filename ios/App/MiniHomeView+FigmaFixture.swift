import Foundation
import PlanteriorData
import PlanteriorDomain

extension MiniHomeView {
    /// Canonical capture data is enabled only by the explicit DEBUG QA route.
    /// It enters the same draft/store pipeline as a customer's placements, so
    /// every reference plant remains selectable, movable, and persistable.
    static func initialPlacements(
        environment: [String: String]
    ) throws -> [MiniHomePlacement] {
        #if DEBUG
            if environment["QA_MINIHOME_FIGMA_FIXTURE"] == "1" {
                return try figmaReferencePlacements
            }
            if environment["QA_INVENTORY_FIXTURE"] == "1" {
                return try inventoryReferencePlacements
            }
            return []
        #else
            return []
        #endif
    }

    #if DEBUG
        static var inventoryReferencePlacements: [MiniHomePlacement] {
            get throws {
                try zip(
                    figmaReferencePlacements,
                    ["item-mini-shelf", "item-small-rug", "item-flower-stand"]
                ).map {
                    try inventoryPlacement(reference: $0.0, itemID: $0.1)
                }
            }
        }

        static var figmaReferencePlacements: [MiniHomePlacement] {
            get throws { try [
                MiniHomePlacement(
                    id: PlacementID.parse("figma-room-placement-1"),
                    plantID: PersonalPlantID.parse("figma-room-plant-0"),
                    itemID: nil,
                    normalizedX: 0.409,
                    normalizedY: 0.709,
                    zIndex: 1
                ),
                MiniHomePlacement(
                    id: PlacementID.parse("figma-room-placement-2"),
                    plantID: PersonalPlantID.parse("figma-room-plant-1"),
                    itemID: nil,
                    normalizedX: 0.524,
                    normalizedY: 0.651,
                    zIndex: 0
                ),
                MiniHomePlacement(
                    id: PlacementID.parse("figma-room-placement-3"),
                    plantID: PersonalPlantID.parse("figma-room-plant-2"),
                    itemID: nil,
                    normalizedX: 0.612,
                    normalizedY: 0.748,
                    zIndex: 2
                )
            ] }
        }

        private static func inventoryPlacement(
            reference: MiniHomePlacement,
            itemID rawItemID: String
        ) throws -> MiniHomePlacement {
            try MiniHomePlacement(
                id: reference.id,
                plantID: nil,
                itemID: ItemID.parse(rawItemID),
                normalizedX: reference.normalizedX,
                normalizedY: reference.normalizedY,
                zIndex: reference.zIndex
            )
        }

    #endif
}
