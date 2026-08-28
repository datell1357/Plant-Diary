import CryptoKit
import Foundation
import PlanteriorDomain

enum MiniHomeCanonicalEncoding {
    static func snapshot(_ snapshot: MiniHomeVerifiedSnapshot) -> String {
        ([
            "MINIHOME-SNAPSHOT-V1",
            "1",
            encoded(snapshot.accountID),
            encoded(snapshot.home.id.rawValue),
            encoded(snapshot.home.name),
            String(snapshot.home.revision.rawValue),
            String(snapshot.updatedAtEpochMillis)
        ] + placementLines(snapshot.home.placements))
            .joined(separator: "\n")
    }

    static func snapshotHash(_ snapshot: MiniHomeVerifiedSnapshot) -> String {
        sha256(Self.snapshot(snapshot))
    }

    static func request(
        accountID: String,
        expectedRevision: UInt64,
        home: MiniHome
    ) -> String {
        ([
            "MINIHOME-REQUEST-V1",
            "1",
            encoded(accountID),
            String(expectedRevision),
            encoded(home.id.rawValue),
            encoded(home.name)
        ] + placementLines(home.placements))
            .joined(separator: "\n")
    }

    static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map {
            String(format: "%02x", $0)
        }.joined()
    }

    static func sortedPlacements(
        _ placements: [MiniHomePlacement]
    ) -> [MiniHomePlacement] {
        placements.sorted { $0.id.rawValue < $1.id.rawValue }
    }

    private static func placementLines(
        _ placements: [MiniHomePlacement]
    ) -> [String] {
        sortedPlacements(placements).map { placement in
            let target: [String] = switch placement.target {
            case let .plant(plantID):
                ["P", encoded(plantID.rawValue)]
            case let .item(itemID):
                ["I", encoded(itemID.rawValue)]
            }
            return [
                "P",
                encoded(placement.id.rawValue)
            ] + target + [
                coordinateHex(placement.normalizedX),
                coordinateHex(placement.normalizedY),
                String(placement.zIndex)
            ]
        }.map { $0.joined(separator: "\t") }
    }

    private static func encoded(_ value: String) -> String {
        Data(value.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func coordinateHex(_ value: Double) -> String {
        String(format: "%016llx", value == 0 ? 0 : value.bitPattern)
    }
}
