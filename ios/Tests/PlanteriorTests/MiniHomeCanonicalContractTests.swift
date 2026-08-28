import CryptoKit
import Foundation
@testable import Planterior
import Testing

struct MiniHomeCanonicalContractTests {
    @Test
    func fixtureBytesAndHashesMatchBackend_whenSharedContractIsConsumed() throws {
        // Given
        let root = try MiniHomeAuthoritativeServiceTests.fixtureRoot()
        let snapshot = try MiniHomeAuthoritativeServiceTests.snapshot()
        let canonicalSnapshot = try #require(root["canonicalSnapshotEncoding"] as? String)
        let canonicalRequest = try #require(root["canonicalRequestEncoding"] as? String)
        let requestHash = try #require(root["requestHash"] as? String)
        let expectedSnapshotHash =
            "faaab600e855c12fbb97933232ddba4674069a8fe03874d860d728c49de17bb1"
        let expectedRequestHash =
            "fe1d175e863aa02e5d9b741da24cb563899db935b10de85fa47ecf1687689a66"

        // When
        let snapshotBytes = MiniHomeCanonicalEncoding.snapshot(snapshot)
        let requestBytes = MiniHomeCanonicalEncoding.request(
            accountID: MiniHomeAuthoritativeServiceTests.accountID,
            expectedRevision: 6,
            home: snapshot.home
        )

        // Then
        #expect(snapshotBytes == canonicalSnapshot)
        #expect(requestBytes == canonicalRequest)
        #expect(MiniHomeCanonicalEncoding.snapshotHash(snapshot) == snapshot.snapshotHash)
        #expect(MiniHomeCanonicalEncoding.sha256(requestBytes) == requestHash)
        #expect(snapshot.snapshotHash == expectedSnapshotHash)
        #expect(requestHash == expectedRequestHash)
    }

    @Test
    func placementOrderDoesNotChangeCanonicalBytes_whenWireOrderDiffers() throws {
        // Given
        let snapshot = try MiniHomeAuthoritativeServiceTests.snapshot()
        let reversedData = try MiniHomeAuthoritativeServiceTests.mutatedData(
            key: "snapshot"
        ) { object in
            let placements = object["placements"] as? [[String: Any]] ?? []
            object["placements"] = Array(placements.reversed())
        }

        // When
        let reversed = try MiniHomeResponseDecoder.snapshot(
            data: reversedData,
            expectedAccountID: snapshot.accountID
        )

        // Then
        #expect(
            MiniHomeCanonicalEncoding.snapshot(snapshot)
                == MiniHomeCanonicalEncoding.snapshot(reversed)
        )
    }
}
