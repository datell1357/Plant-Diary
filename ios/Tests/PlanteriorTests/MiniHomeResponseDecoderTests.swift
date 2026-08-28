import Foundation
@testable import Planterior
import Testing

struct MiniHomeResponseDecoderTests {
    @Test
    func emptyAndSnapshotLoadDecode_whenResponsesAreExact() throws {
        // Given
        let empty = Data(
            #"{"kind":"empty","contractVersion":1,"ownerUid":"owner-fixture"}"#.utf8
        )

        // When
        let emptyResult = try MiniHomeResponseDecoder.load(
            data: empty,
            expectedAccountID: MiniHomeAuthoritativeServiceTests.accountID
        )
        let snapshotResult = try MiniHomeResponseDecoder.load(
            data: MiniHomeAuthoritativeServiceTests.fixtureData("loadResponse"),
            expectedAccountID: MiniHomeAuthoritativeServiceTests.accountID
        )

        // Then
        #expect(emptyResult == .empty(accountID: "owner-fixture"))
        guard case let .snapshot(snapshot) = snapshotResult else {
            Issue.record("Expected snapshot result")
            return
        }
        #expect(snapshot.home.id.rawValue == "room-main")
        #expect(snapshot.home.placements.map(\.id.rawValue) == [
            "placement-lamp", "placement-plant"
        ])
    }

    @Test(arguments: ["committed", "duplicate", "conflict"])
    func saveVariantsDecode_whenSnapshotsAreVerified(_ kind: String) throws {
        // Given
        let root = try MiniHomeAuthoritativeServiceTests.fixtureRoot()
        let snapshot = try #require(root["snapshot"] as? [String: Any])
        let payload: [String: Any] = ["kind": kind, "snapshot": snapshot]
        let data = try JSONSerialization.data(withJSONObject: payload)

        // When
        let result = try MiniHomeResponseDecoder.save(
            data: data,
            expectedAccountID: MiniHomeAuthoritativeServiceTests.accountID
        )

        // Then
        switch result {
        case let .committed(value): #expect(kind == "committed" && value.snapshotHash.count == 64)
        case let .duplicate(value): #expect(kind == "duplicate" && value.snapshotHash.count == 64)
        case let .conflict(value): #expect(kind == "conflict" && value?.snapshotHash.count == 64)
        }
    }

    @Test
    func nullConflictDecodes_whenServerHasNoCurrentSnapshot() throws {
        // Given
        let data = Data(#"{"kind":"conflict","snapshot":null}"#.utf8)

        // When / Then
        #expect(
            try MiniHomeResponseDecoder.save(
                data: data,
                expectedAccountID: MiniHomeAuthoritativeServiceTests.accountID
            ) == .conflict(nil)
        )
    }

    @Test(arguments: [
        "unknown-root", "unknown-nested", "unknown-kind", "foreign-owner", "bad-owner",
        "bad-revision", "revision-overflow", "bad-revision-type", "bad-epoch", "bad-name",
        "bad-hash", "bad-id", "xor-target", "bad-geometry", "z-range",
        "duplicate-placement", "too-many"
    ])
    func malformedSnapshotFailsClosed_whenInvariantIsBroken(_ mutation: String) throws {
        // Given
        let data = try malformedLoadResponse(mutation)

        // When / Then
        #expect(throws: MiniHomeAuthoritativeError.malformedResponse) {
            try MiniHomeResponseDecoder.load(
                data: data,
                expectedAccountID: MiniHomeAuthoritativeServiceTests.accountID
            )
        }
    }

    private func malformedLoadResponse(_ mutation: String) throws -> Data {
        try MiniHomeAuthoritativeServiceTests.mutatedData(key: "loadResponse") { root in
            var placements = root["placements"] as? [[String: Any]] ?? []
            let envelopeMutations = [
                "unknown-root", "unknown-kind", "foreign-owner", "bad-owner", "bad-name"
            ]
            if envelopeMutations.contains(mutation) {
                mutateEnvelope(&root, mutation: mutation)
            } else if [
                "bad-revision", "revision-overflow", "bad-revision-type", "bad-epoch", "bad-hash"
            ].contains(mutation) {
                mutateScalar(&root, mutation: mutation)
            } else if [
                "unknown-nested", "bad-id", "xor-target", "bad-geometry", "z-range"
            ].contains(mutation) {
                mutatePlacement(&placements, mutation: mutation)
            } else {
                mutateCollection(&placements, mutation: mutation)
            }
            root["placements"] = placements
        }
    }

    private func mutateEnvelope(_ root: inout [String: Any], mutation: String) {
        switch mutation {
        case "unknown-root": root["extra"] = true
        case "unknown-kind": root["kind"] = "unknown"
        case "foreign-owner": root["ownerUid"] = "owner-foreign"
        case "bad-owner": root["ownerUid"] = "bad/path"
        case "bad-name": root["name"] = " surrounded "
        default: break
        }
    }

    private func mutateScalar(_ root: inout [String: Any], mutation: String) {
        switch mutation {
        case "bad-revision": root["revision"] = 0
        case "revision-overflow": root["revision"] = 9_007_199_254_740_992 as UInt64
        case "bad-revision-type": root["revision"] = "7"
        case "bad-epoch": root["updatedAtEpochMillis"] = -1
        case "bad-hash": root["snapshotHash"] = String(repeating: "0", count: 64)
        default: break
        }
    }

    private func mutatePlacement(
        _ placements: inout [[String: Any]],
        mutation: String
    ) {
        switch mutation {
        case "unknown-nested": placements[0]["extra"] = true
        case "bad-id": placements[0]["placementId"] = "bad/path"
        case "xor-target": placements[0]["itemId"] = "item-lamp"
        case "bad-geometry": placements[0]["normalizedX"] = 1.1
        case "z-range": placements[0]["zIndex"] = 20
        default: break
        }
    }

    private func mutateCollection(
        _ placements: inout [[String: Any]],
        mutation: String
    ) {
        switch mutation {
        case "duplicate-placement":
            placements.append(placements[0])
        case "too-many":
            placements = (0 ... 20).map { index in
                var placement = placements[0]
                placement["placementId"] = "placement-\(index)"
                return placement
            }
        default: break
        }
    }
}
