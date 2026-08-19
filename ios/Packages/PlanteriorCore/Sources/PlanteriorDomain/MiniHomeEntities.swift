public struct MiniHome: Codable, Equatable, Sendable {
    public let id: MiniHomeID
    public let name: String
    public let placements: [MiniHomePlacement]
    public let revision: Revision
    public let updatedAt: Instant

    public init(
        id: MiniHomeID,
        name: String,
        placements: [MiniHomePlacement],
        revision: Revision,
        updatedAt: Instant
    ) {
        self.id = id
        self.name = name
        self.placements = placements
        self.revision = revision
        self.updatedAt = updatedAt
    }
}

public struct MiniHomePlacement: Codable, Equatable, Sendable {
    public let id: PlacementID
    public let plantID: PersonalPlantID?
    public let itemID: ItemID?
    public let normalizedX: Double
    public let normalizedY: Double
    public let zIndex: Int

    public init(
        id: PlacementID,
        plantID: PersonalPlantID?,
        itemID: ItemID?,
        normalizedX: Double,
        normalizedY: Double,
        zIndex: Int
    ) throws {
        guard (plantID != nil) != (itemID != nil) else {
            throw MiniHomePlacementError.invalidTarget
        }
        guard normalizedX.isFinite, normalizedY.isFinite,
              (0 ... 1).contains(normalizedX),
              (0 ... 1).contains(normalizedY),
              zIndex >= 0
        else {
            throw MiniHomePlacementError.invalidGeometry
        }
        self.id = id
        self.plantID = plantID
        self.itemID = itemID
        self.normalizedX = normalizedX
        self.normalizedY = normalizedY
        self.zIndex = zIndex
    }
}

public enum MiniHomePlacementError: Error, Equatable, Sendable {
    case invalidTarget
    case invalidGeometry
}
