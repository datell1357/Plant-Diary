import CryptoKit
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct MiniHomeShareRendererTests {
    @Test
    func rendersCommittedRevisionAtFixedDeterministicSize() throws {
        let room = try shareRoom(name: "저장된 방", revision: 3)
        let renderer = MiniHomeShareRenderer()
        let firstImage = renderer.render(room: room)
        let secondImage = renderer.render(room: room)
        let first = try #require(firstImage)
        let second = try #require(secondImage)

        #expect(first.image.cgImage?.width == 1200)
        #expect(first.image.cgImage?.height == 1200)
        #expect(first.snapshot.sourceRevision.rawValue == 3)
        #expect(first.digest == second.digest)
        #expect(first.pngData == second.pngData)
    }

    @Test
    func capturedCommittedRoomDoesNotFollowLaterDraft() throws {
        let committed = try shareRoom(name: "저장된 방", revision: 2)
        let draft = try shareRoom(name: "미저장 초안", revision: 2)
        let renderer = MiniHomeShareRenderer()
        let capturedImage = renderer.render(room: committed)
        let captured = try #require(capturedImage)

        _ = renderer.render(room: draft)
        #expect(captured.snapshot.roomName == "저장된 방")
        #expect(captured.snapshot.sourceRevision.rawValue == 2)
    }
}

func shareRoom(name: String, revision: UInt64) throws -> MiniHome {
    let roomID = try MiniHomeID.parse("share-room")
    let placementID = try PlacementID.parse("share-placement")
    let plantID = try PersonalPlantID.parse("private-plant")
    let parsedRevision = try Revision.parse(revision)
    let updatedAt = try Instant.parse("2026-08-11T00:00:00Z")
    let placement = try MiniHomePlacement(
        id: placementID,
        plantID: plantID,
        itemID: nil,
        normalizedX: 0.45,
        normalizedY: 0.55,
        zIndex: 0
    )
    return MiniHome(
        id: roomID,
        name: name,
        placements: [placement],
        revision: parsedRevision,
        updatedAt: updatedAt
    )
}
