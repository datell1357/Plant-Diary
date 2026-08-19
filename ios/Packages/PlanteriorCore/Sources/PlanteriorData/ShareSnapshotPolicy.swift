import CryptoKit
import Foundation
import PlanteriorDomain

public enum ShareSnapshotPolicy {
    public static let imageWidth = 1200
    public static let imageHeight = 1200
    public static let lifetimeSeconds: TimeInterval = 30 * 24 * 60 * 60

    public static func snapshot(
        committed room: MiniHome,
        plantNames: [PersonalPlantID: String] = [:],
        itemNames: [ItemID: String] = [:]
    ) -> MiniHomeShareSnapshot {
        let ordered = room.placements.sorted {
            ($0.zIndex, $0.id.rawValue) < ($1.zIndex, $1.id.rawValue)
        }
        return MiniHomeShareSnapshot(
            roomName: room.name,
            sourceRevision: room.revision,
            placements: ordered.map { placement in
                if let plantID = placement.plantID {
                    return ShareSnapshotPlacement(
                        kind: .plant,
                        displayName: plantNames[plantID] ?? "식물",
                        normalizedX: placement.normalizedX,
                        normalizedY: placement.normalizedY,
                        zIndex: placement.zIndex
                    )
                }
                return ShareSnapshotPlacement(
                    kind: .item,
                    displayName: placement.itemID.flatMap {
                        itemNames[$0]
                    } ?? "꾸미기 아이템",
                    normalizedX: placement.normalizedX,
                    normalizedY: placement.normalizedY,
                    zIndex: placement.zIndex
                )
            }
        )
    }

    public static func digest(
        _ snapshot: MiniHomeShareSnapshot
    ) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(snapshot)
        return SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    public static func token(randomBytes: Data) throws -> ShareToken {
        guard randomBytes.count >= 24 else {
            throw ShareSnapshotError.invalidRandomBytes
        }
        let value = randomBytes.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return try ShareToken.parse(value)
    }

    public static func expiresAt(createdAt: Instant) throws -> Instant {
        guard let timestamp = timestamp(from: createdAt) else {
            throw ShareSnapshotError.invalidTimestamp
        }
        let formatter = ISO8601DateFormatter()
        let date = timestamp.date.addingTimeInterval(lifetimeSeconds)
        let base = formatter.string(from: date).dropLast()
        let fractional = timestamp.fractionalDigits.map { ".\($0)" } ?? ""
        return try Instant.parse(
            "\(base)\(fractional)Z"
        )
    }

    public static func isReadable(
        expiresAt: Instant,
        revokedAt: Instant?,
        now: Instant
    ) -> Bool {
        guard revokedAt == nil,
              let expiry = timestamp(from: expiresAt),
              let instant = timestamp(from: now)
        else {
            return false
        }
        if instant.date != expiry.date {
            return instant.date < expiry.date
        }
        return instant.nanoseconds < expiry.nanoseconds
    }

    private static func timestamp(
        from instant: Instant
    ) -> ShareTimestamp? {
        guard instant.rawValue.hasSuffix("Z") else { return nil }
        let body = instant.rawValue.dropLast()
        let pieces = body.split(
            separator: ".",
            maxSplits: 1,
            omittingEmptySubsequences: false
        )
        guard let base = pieces.first,
              let date = ISO8601DateFormatter().date(
                  from: "\(base)Z"
              )
        else {
            return nil
        }
        guard pieces.count == 2 else {
            return ShareTimestamp(
                date: date,
                nanoseconds: 0,
                fractionalDigits: nil
            )
        }
        let digits = String(pieces[1])
        guard (1 ... 9).contains(digits.count),
              digits.allSatisfy(\.isNumber),
              let nanoseconds = Int(
                  digits.padding(
                      toLength: 9,
                      withPad: "0",
                      startingAt: 0
                  )
              )
        else {
            return nil
        }
        return ShareTimestamp(
            date: date,
            nanoseconds: nanoseconds,
            fractionalDigits: digits
        )
    }
}

private struct ShareTimestamp {
    let date: Date
    let nanoseconds: Int
    let fractionalDigits: String?
}
