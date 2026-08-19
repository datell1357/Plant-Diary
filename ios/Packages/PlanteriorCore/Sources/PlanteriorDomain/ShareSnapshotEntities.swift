public enum SharePlacementKind: String, Codable, Sendable {
    case plant = "PLANT"
    case item = "ITEM"
}

public struct ShareSnapshotPlacement: Codable, Equatable, Sendable {
    public let kind: SharePlacementKind
    public let displayName: String
    public let normalizedX: Double
    public let normalizedY: Double
    public let zIndex: Int

    public init(
        kind: SharePlacementKind,
        displayName: String,
        normalizedX: Double,
        normalizedY: Double,
        zIndex: Int
    ) {
        self.kind = kind
        self.displayName = displayName
        self.normalizedX = normalizedX
        self.normalizedY = normalizedY
        self.zIndex = zIndex
    }
}

public struct MiniHomeShareSnapshot: Codable, Equatable, Sendable {
    public let roomName: String
    public let sourceRevision: Revision
    public let placements: [ShareSnapshotPlacement]

    public init(
        roomName: String,
        sourceRevision: Revision,
        placements: [ShareSnapshotPlacement]
    ) {
        self.roomName = roomName
        self.sourceRevision = sourceRevision
        self.placements = placements
    }
}

public struct ShareToken: Codable, Equatable, Hashable, Sendable {
    public let rawValue: String

    public static func parse(_ value: String) throws -> Self {
        guard value.range(
            of: #"^[A-Za-z0-9_-]{32,128}$"#,
            options: .regularExpression
        ) != nil else {
            throw ShareSnapshotError.invalidToken
        }
        return Self(rawValue: value)
    }
}

public enum ShareSnapshotError: Error, Equatable, Sendable {
    case invalidToken
    case invalidRandomBytes
    case invalidTimestamp
}
