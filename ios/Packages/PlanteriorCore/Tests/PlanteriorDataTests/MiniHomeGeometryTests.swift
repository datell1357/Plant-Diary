import Foundation
import PlanteriorData
import PlanteriorDomain
import Testing

struct MiniHomeGeometryTests {
    @Test
    func rejectsInvalidNormalizedCoordinates() {
        #expect(throws: MiniHomeGeometryError.invalidCoordinate) {
            try MiniHomePosition(normalizedX: .nan, normalizedY: 0.5)
        }
        #expect(throws: MiniHomeGeometryError.invalidCoordinate) {
            try MiniHomePosition(normalizedX: -0.01, normalizedY: 0.5)
        }
        #expect(throws: MiniHomeGeometryError.invalidCoordinate) {
            try MiniHomePosition(normalizedX: 0.5, normalizedY: 1.01)
        }
    }

    @Test
    func clampsDragCoordinatesToRoomBounds() throws {
        let position = try MiniHomeGeometry.position(
            dragX: -40,
            dragY: 120,
            roomWidth: 100,
            roomHeight: 100
        )

        #expect(position.normalizedX == 0)
        #expect(position.normalizedY == 1)
    }

    @Test
    func mapsNormalizedCentersInsidePlacementRadius() {
        let leading = MiniHomeGeometry.pixelCoordinate(
            normalized: 0,
            length: 200,
            itemRadius: 26
        )
        let trailing = MiniHomeGeometry.pixelCoordinate(
            normalized: 1,
            length: 200,
            itemRadius: 26
        )

        #expect(leading == 26)
        #expect(trailing == 174)
    }

    @Test
    func allocatesFirstUnusedPlacementIdentifier() throws {
        let existing = try [
            PlacementID.parse("placement-1"),
            PlacementID.parse("placement-3")
        ]

        let next = try MiniHomeGeometry.nextPlacementID(
            existing: existing
        )

        #expect(next.rawValue == "placement-2")
    }

    @Test
    func ordersEqualLayersByStablePlacementID() throws {
        let laterID = try PlacementID.parse("placement-b")
        let earlierID = try PlacementID.parse("placement-a")
        let plantID = try PersonalPlantID.parse("plant-b")
        let itemID = try ItemID.parse("item-a")
        let placements = try [
            MiniHomePlacement(
                id: laterID,
                plantID: plantID,
                itemID: nil,
                normalizedX: 0.8,
                normalizedY: 0.2,
                zIndex: 4
            ),
            MiniHomePlacement(
                id: earlierID,
                plantID: nil,
                itemID: itemID,
                normalizedX: 0.2,
                normalizedY: 0.8,
                zIndex: 4
            )
        ]

        #expect(
            MiniHomeGeometry.ordered(placements).map(\.id) ==
                [earlierID, laterID]
        )
    }
}
