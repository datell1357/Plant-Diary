import Foundation
import PlanteriorData
import PlanteriorDomain
import Testing

struct ShareSnapshotPolicyTests {
    @Test
    func committedSnapshotIsStableOrderedAndPrivateFieldFree() throws {
        let fixture = try ShareSnapshotFixture()
        let snapshot = ShareSnapshotPolicy.snapshot(
            committed: fixture.room,
            plantNames: [fixture.plantID: "몬스테라"],
            itemNames: [fixture.itemID: "원목 의자"]
        )
        let encoded = try JSONEncoder().encode(snapshot)
        let json = try #require(String(data: encoded, encoding: .utf8))

        #expect(snapshot.sourceRevision.rawValue == 3)
        #expect(snapshot.placements.map(\.displayName) == [
            "원목 의자",
            "몬스테라"
        ])
        #expect(!json.contains(fixture.plantID.rawValue))
        #expect(!json.contains(fixture.itemID.rawValue))
        #expect(!json.contains("owner"))
        #expect(!json.contains("note"))
        #expect(!json.contains("location"))
        #expect(!json.contains("photo"))
    }

    @Test
    func digestAndDimensionsAreDeterministic() throws {
        let fixture = try ShareSnapshotFixture()
        let snapshot = ShareSnapshotPolicy.snapshot(committed: fixture.room)

        #expect(ShareSnapshotPolicy.imageWidth == 1200)
        #expect(ShareSnapshotPolicy.imageHeight == 1200)
        #expect(
            try ShareSnapshotPolicy.digest(snapshot) ==
                ShareSnapshotPolicy.digest(snapshot)
        )
    }

    @Test
    func tokensExpiryAndRevokeFailClosed() throws {
        let createdAt = try Instant.parse("2026-08-11T00:00:00Z")
        let expiresAt = try ShareSnapshotPolicy.expiresAt(
            createdAt: createdAt
        )
        let first = try ShareSnapshotPolicy.token(
            randomBytes: Data(repeating: 1, count: 24)
        )
        let second = try ShareSnapshotPolicy.token(
            randomBytes: Data(repeating: 2, count: 24)
        )
        let beforeExpiry = try Instant.parse("2026-09-09T23:59:59Z")

        #expect(first != second)
        #expect(expiresAt.rawValue == "2026-09-10T00:00:00Z")
        #expect(
            ShareSnapshotPolicy.isReadable(
                expiresAt: expiresAt,
                revokedAt: nil,
                now: beforeExpiry
            )
        )
        #expect(
            !ShareSnapshotPolicy.isReadable(
                expiresAt: expiresAt,
                revokedAt: nil,
                now: expiresAt
            )
        )
        #expect(
            !ShareSnapshotPolicy.isReadable(
                expiresAt: expiresAt,
                revokedAt: createdAt,
                now: createdAt
            )
        )
    }

    @Test
    func fractionalExpiryUsesChronologicalComparison() throws {
        let createdAt = try Instant.parse(
            "2026-08-11T00:00:00.123456789Z"
        )
        let expiresAt = try ShareSnapshotPolicy.expiresAt(
            createdAt: createdAt
        )
        let before = try Instant.parse(
            "2026-09-10T00:00:00.123456788Z"
        )
        let exact = try Instant.parse(
            "2026-09-10T00:00:00.123456789Z"
        )

        #expect(
            expiresAt.rawValue ==
                "2026-09-10T00:00:00.123456789Z"
        )
        #expect(
            ShareSnapshotPolicy.isReadable(
                expiresAt: expiresAt,
                revokedAt: nil,
                now: before
            )
        )
        #expect(
            !ShareSnapshotPolicy.isReadable(
                expiresAt: expiresAt,
                revokedAt: nil,
                now: exact
            )
        )
    }
}

private struct ShareSnapshotFixture {
    let plantID: PersonalPlantID
    let itemID: ItemID
    let room: MiniHome

    init() throws {
        plantID = try PersonalPlantID.parse("private-plant")
        itemID = try ItemID.parse("private-item")
        let itemPlacementID = try PlacementID.parse("placement-a")
        let plantPlacementID = try PlacementID.parse("placement-b")
        let item = try MiniHomePlacement(
            id: itemPlacementID,
            plantID: nil,
            itemID: itemID,
            normalizedX: 0.2,
            normalizedY: 0.3,
            zIndex: 0
        )
        let plant = try MiniHomePlacement(
            id: plantPlacementID,
            plantID: plantID,
            itemID: nil,
            normalizedX: 0.7,
            normalizedY: 0.6,
            zIndex: 1
        )
        let roomID = try MiniHomeID.parse("share-room")
        let revision = try Revision.parse(3)
        let updatedAt = try Instant.parse("2026-08-11T00:00:00Z")
        room = MiniHome(
            id: roomID,
            name: "공유할 방",
            placements: [plant, item],
            revision: revision,
            updatedAt: updatedAt
        )
    }
}
