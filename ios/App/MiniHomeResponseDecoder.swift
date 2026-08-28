import Foundation
import PlanteriorDomain

struct MiniHomeServerVerification: Sendable {}

enum MiniHomeResponseDecoder {
    private static let snapshotKeys: Set<String> = [
        "contractVersion", "ownerUid", "roomId", "name", "placements",
        "revision", "updatedAtEpochMillis", "snapshotHash"
    ]
    private static let plantPlacementKeys: Set<String> = [
        "placementId", "plantId", "normalizedX", "normalizedY", "zIndex"
    ]
    private static let itemPlacementKeys: Set<String> = [
        "placementId", "itemId", "normalizedX", "normalizedY", "zIndex"
    ]

    static func load(
        data: Data,
        expectedAccountID: String
    ) throws -> MiniHomeAuthoritativeLoadResult {
        let object = try MiniHomeResponseJSON.root(data)
        let kind = try MiniHomeResponseJSON.string(object["kind"])
        switch kind {
        case "empty":
            try MiniHomeResponseJSON.exactKeys(
                object,
                ["kind", "contractVersion", "ownerUid"]
            )
            try requireVersion(object["contractVersion"])
            let owner = try validID(object["ownerUid"])
            guard owner == expectedAccountID else { throw malformed() }
            return .empty(accountID: owner)
        case "snapshot":
            try MiniHomeResponseJSON.exactKeys(
                object,
                snapshotKeys.union(["kind"])
            )
            return try .snapshot(parsedSnapshot(
                object,
                expectedAccountID: expectedAccountID
            ))
        default:
            throw malformed()
        }
    }

    static func save(
        data: Data,
        expectedAccountID: String
    ) throws -> MiniHomeAuthoritativeSaveResult {
        let object = try MiniHomeResponseJSON.root(data)
        try MiniHomeResponseJSON.exactKeys(object, ["kind", "snapshot"])
        let kind = try MiniHomeResponseJSON.string(object["kind"])
        if kind == "conflict", object["snapshot"] is NSNull {
            return .conflict(nil)
        }
        let rawSnapshot = try MiniHomeResponseJSON.object(object["snapshot"])
        try MiniHomeResponseJSON.exactKeys(rawSnapshot, snapshotKeys)
        let snapshot = try parsedSnapshot(
            rawSnapshot,
            expectedAccountID: expectedAccountID
        )
        switch kind {
        case "committed": return .committed(snapshot)
        case "duplicate": return .duplicate(snapshot)
        case "conflict": return .conflict(snapshot)
        default: throw malformed()
        }
    }

    static func snapshot(
        data: Data,
        expectedAccountID: String
    ) throws -> MiniHomeVerifiedSnapshot {
        let object = try MiniHomeResponseJSON.root(data)
        try MiniHomeResponseJSON.exactKeys(object, snapshotKeys)
        return try parsedSnapshot(object, expectedAccountID: expectedAccountID)
    }

    private static func parsedSnapshot(
        _ object: [String: Any],
        expectedAccountID: String
    ) throws -> MiniHomeVerifiedSnapshot {
        do {
            try requireVersion(object["contractVersion"])
            let owner = try validID(object["ownerUid"])
            guard owner == expectedAccountID else { throw malformed() }
            let roomID = try MiniHomeID.parse(validID(object["roomId"]))
            let name = try validName(object["name"])
            let revisionValue = try MiniHomeResponseJSON.uint(object["revision"])
            guard revisionValue > 0 else { throw malformed() }
            let revision = try Revision.parse(revisionValue)
            let epoch = try MiniHomeResponseJSON.uint(object["updatedAtEpochMillis"])
            let placements = try parsedPlacements(object["placements"])
            let hash = try validHash(object["snapshotHash"])
            let home = try MiniHome(
                id: roomID,
                name: name,
                placements: placements,
                revision: revision,
                updatedAt: instant(epoch)
            )
            let snapshot = MiniHomeVerifiedSnapshot(
                wire: MiniHomeSnapshotWire(
                    accountID: owner,
                    home: home,
                    updatedAtEpochMillis: epoch,
                    snapshotHash: hash
                ),
                verification: MiniHomeServerVerification()
            )
            guard MiniHomeCanonicalEncoding.snapshotHash(snapshot) == hash else {
                throw malformed()
            }
            return snapshot
        } catch let error as MiniHomeAuthoritativeError {
            throw error
        } catch {
            throw malformed()
        }
    }

    private static func parsedPlacements(_ raw: Any?) throws -> [MiniHomePlacement] {
        let values = try MiniHomeResponseJSON.array(raw)
        guard values.count <= 20 else { throw malformed() }
        let placements = try values.map { try parsedPlacement($0) }
        guard Set(placements.map(\.id)).count == placements.count else {
            throw malformed()
        }
        return MiniHomeCanonicalEncoding.sortedPlacements(placements)
    }

    private static func parsedPlacement(_ raw: Any) throws -> MiniHomePlacement {
        let object = try MiniHomeResponseJSON.object(raw)
        let id = try PlacementID.parse(validID(object["placementId"]))
        let coordinateX = try MiniHomeResponseJSON.double(object["normalizedX"])
        let coordinateY = try MiniHomeResponseJSON.double(object["normalizedY"])
        let zIndex = try MiniHomeResponseJSON.int(object["zIndex"])
        guard (0 ... 1).contains(coordinateX),
              (0 ... 1).contains(coordinateY),
              (0 ... 19).contains(zIndex)
        else {
            throw malformed()
        }
        switch Set(object.keys) {
        case plantPlacementKeys:
            return try MiniHomePlacement(
                id: id,
                plantID: PersonalPlantID.parse(validID(object["plantId"])),
                itemID: nil,
                normalizedX: coordinateX,
                normalizedY: coordinateY,
                zIndex: zIndex
            )
        case itemPlacementKeys:
            return try MiniHomePlacement(
                id: id,
                plantID: nil,
                itemID: ItemID.parse(validID(object["itemId"])),
                normalizedX: coordinateX,
                normalizedY: coordinateY,
                zIndex: zIndex
            )
        default:
            throw malformed()
        }
    }
}
