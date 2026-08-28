import Foundation
import PlanteriorDomain

struct MiniHomeLocalCandidate: Equatable, Sendable {
    let accountID: String
    let home: MiniHome
    let sourceKey: String
}

@MainActor
struct MiniHomeVerifiedCache {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load(accountID: String) -> MiniHomeVerifiedSnapshot? {
        let key = Self.snapshotKey(accountID: accountID)
        guard Self.isValidAccountID(accountID),
              let data = defaults.data(forKey: key)
        else {
            return nil
        }
        do {
            return try MiniHomeResponseDecoder.snapshot(
                data: data,
                expectedAccountID: accountID
            )
        } catch {
            defaults.removeObject(forKey: key)
            return nil
        }
    }

    func store(_ snapshot: MiniHomeVerifiedSnapshot) throws {
        let data = try Self.wireData(snapshot)
        let reparsed = try MiniHomeResponseDecoder.snapshot(
            data: data,
            expectedAccountID: snapshot.accountID
        )
        guard reparsed == snapshot else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        defaults.set(data, forKey: Self.snapshotKey(accountID: snapshot.accountID))
    }

    func localCandidate(accountID: String) -> MiniHomeLocalCandidate? {
        guard Self.isValidAccountID(accountID) else { return nil }
        let sourceKey = "home.\(accountID).committed-mini-home"
        guard let data = defaults.data(forKey: sourceKey),
              let home = try? JSONDecoder().decode(MiniHome.self, from: data)
        else {
            return nil
        }
        return MiniHomeLocalCandidate(
            accountID: accountID,
            home: home,
            sourceKey: sourceKey
        )
    }

    func removeSnapshot(accountID: String) {
        guard Self.isValidAccountID(accountID) else { return }
        defaults.removeObject(forKey: Self.snapshotKey(accountID: accountID))
    }

    static func snapshotKey(accountID: String) -> String {
        "minihome.verified.v1.\(accountID).snapshot"
    }

    private static func wireData(
        _ snapshot: MiniHomeVerifiedSnapshot
    ) throws -> Data {
        let placements: [[String: Any]] = snapshot.home.placements.map { placement in
            var object: [String: Any] = [
                "placementId": placement.id.rawValue,
                "normalizedX": placement.normalizedX,
                "normalizedY": placement.normalizedY,
                "zIndex": placement.zIndex
            ]
            switch (placement.plantID, placement.itemID) {
            case let (.some(plantID), .none):
                object["plantId"] = plantID.rawValue
            case let (.none, .some(itemID)):
                object["itemId"] = itemID.rawValue
            case (.some, .some), (.none, .none):
                break
            }
            return object
        }
        let object: [String: Any] = [
            "contractVersion": 1,
            "ownerUid": snapshot.accountID,
            "roomId": snapshot.home.id.rawValue,
            "name": snapshot.home.name,
            "placements": placements,
            "revision": snapshot.home.revision.rawValue,
            "updatedAtEpochMillis": snapshot.updatedAtEpochMillis,
            "snapshotHash": snapshot.snapshotHash
        ]
        guard JSONSerialization.isValidJSONObject(object) else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    private static func isValidAccountID(_ value: String) -> Bool {
        value.range(
            of: "^[A-Za-z0-9_-]{1,128}$",
            options: .regularExpression
        ) != nil
    }
}
