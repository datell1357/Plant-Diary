import Foundation
@testable import Planterior
import Testing

@MainActor
struct MiniHomeCanonicalDragProjectionTests {
    /// These points were pinned from the 358x330 authenticated room reference.
    /// They intentionally do not ask production geometry or AX traversal where
    /// to hit: changing fixture coordinates, stacking, or target projection must
    /// continue to resolve the same painted plant identities at these points.
    @Test
    func canonicalReferencePointsHitTheirPinnedPlantIDs() throws {
        let placements = try MiniHomeView.figmaReferencePlacements
        let canvas = CGSize(width: 358, height: 330)
        let expectedHits: [(point: CGPoint, id: String)] = [
            (CGPoint(x: 130, y: 245), "figma-room-placement-1"),
            (CGPoint(x: 160, y: 175), "figma-room-placement-2"),
            (CGPoint(x: 235, y: 250), "figma-room-placement-3")
        ]

        for expectation in expectedHits {
            #expect(
                MiniRoomPlacementProjector.hitPlacement(
                    at: expectation.point,
                    among: placements,
                    in: canvas
                )?.id.rawValue == expectation.id
            )
        }
    }

    @Test
    func canonicalProjectionKeepsIndependentReferenceCentersAndStacking() throws {
        let placements = try MiniHomeView.figmaReferencePlacements
        let projected = MiniRoomPlacementProjector.resolved(
            placements: placements,
            in: CGSize(width: 358, height: 330)
        )
        let expected: [(id: String, center: CGPoint)] = [
            ("figma-room-placement-2", CGPoint(x: 186, y: 204)),
            ("figma-room-placement-1", CGPoint(x: 153, y: 219)),
            ("figma-room-placement-3", CGPoint(x: 211, y: 229))
        ]

        #expect(projected.map(\.placement.id.rawValue) == expected.map(\.id))
        for (actual, pin) in zip(projected, expected) {
            #expect(abs(actual.position.x - pin.center.x) < 0.75)
            #expect(abs(actual.position.y - pin.center.y) < 0.75)
        }
    }
}
