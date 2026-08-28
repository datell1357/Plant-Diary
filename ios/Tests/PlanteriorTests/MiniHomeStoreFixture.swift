import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct MiniHomeStoreFixture {
    let accountA = "account-a"
    let accountB = "account-b"
    let defaults: UserDefaults
    let cache: MiniHomeVerifiedCache
    let placements: [MiniHomePlacement]

    init() throws {
        let suite = "MiniHomeStoreTests.\(UUID().uuidString)"
        defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        cache = MiniHomeVerifiedCache(defaults: defaults)
        placements = try [
            MiniHomePlacement(
                id: PlacementID.parse("placement-plant"),
                plantID: PersonalPlantID.parse("plant-one"),
                itemID: nil,
                normalizedX: 0.25,
                normalizedY: 0.75,
                zIndex: 1
            ),
            MiniHomePlacement(
                id: PlacementID.parse("placement-item"),
                plantID: nil,
                itemID: ItemID.parse("item-one"),
                normalizedX: 0.75,
                normalizedY: 0.25,
                zIndex: 0
            )
        ]
    }

    func store(
        service: MiniHomeStoreServiceFake,
        operationIDs: [String]
    ) -> MiniHomeStore {
        var remaining = operationIDs
        return MiniHomeStore(
            service: service,
            cache: cache,
            makeOperationID: {
                try OperationID.parse(remaining.removeFirst())
            }
        )
    }

    func room(
        name: String,
        revision: UInt64,
        placements: some Sequence<MiniHomePlacement> = []
    ) throws -> MiniHome {
        try MiniHome(
            id: MiniHomeID.parse("room-main"),
            name: name,
            placements: Array(placements),
            revision: Revision.parse(revision),
            updatedAt: Instant.parse("2026-08-11T00:00:00.000Z")
        )
    }

    func snapshot(
        accountID: String? = nil,
        name: String,
        revision: UInt64,
        placements: some Sequence<MiniHomePlacement> = []
    ) throws -> MiniHomeVerifiedSnapshot {
        try Self.verifiedSnapshot(
            accountID: accountID ?? accountA,
            home: room(name: name, revision: revision, placements: placements),
            updatedAtEpochMillis: 1_786_406_400_000
        )
    }

    static func verifiedSnapshot(
        accountID: String,
        home: MiniHome,
        updatedAtEpochMillis: UInt64
    ) throws -> MiniHomeVerifiedSnapshot {
        MiniHomeVerifiedSnapshot.verified(
            accountID: accountID,
            home: home,
            updatedAtEpochMillis: updatedAtEpochMillis
        )
    }
}
