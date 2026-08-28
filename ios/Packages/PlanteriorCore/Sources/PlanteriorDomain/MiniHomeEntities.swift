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

public enum MiniHomePlacementTarget: Equatable, Sendable {
    case plant(PersonalPlantID)
    case item(ItemID)
}

public struct MiniHomePlacement: Codable, Equatable, Sendable {
    public let id: PlacementID
    public let target: MiniHomePlacementTarget
    public let normalizedX: Double
    public let normalizedY: Double
    public let zIndex: Int

    public var plantID: PersonalPlantID? {
        guard case let .plant(plantID) = target else { return nil }
        return plantID
    }

    public var itemID: ItemID? {
        guard case let .item(itemID) = target else { return nil }
        return itemID
    }

    public init(
        id: PlacementID,
        plantID: PersonalPlantID?,
        itemID: ItemID?,
        normalizedX: Double,
        normalizedY: Double,
        zIndex: Int
    ) throws {
        switch (plantID, itemID) {
        case let (.some(plantID), .none):
            target = .plant(plantID)
        case let (.none, .some(itemID)):
            target = .item(itemID)
        case (.none, .none), (.some, .some):
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
        self.normalizedX = normalizedX
        self.normalizedY = normalizedY
        self.zIndex = zIndex
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(
            id: container.decode(PlacementID.self, forKey: .id),
            plantID: container.decodeIfPresent(PersonalPlantID.self, forKey: .plantID),
            itemID: container.decodeIfPresent(ItemID.self, forKey: .itemID),
            normalizedX: container.decode(Double.self, forKey: .normalizedX),
            normalizedY: container.decode(Double.self, forKey: .normalizedY),
            zIndex: container.decode(Int.self, forKey: .zIndex)
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encodeIfPresent(plantID, forKey: .plantID)
        try container.encodeIfPresent(itemID, forKey: .itemID)
        try container.encode(normalizedX, forKey: .normalizedX)
        try container.encode(normalizedY, forKey: .normalizedY)
        try container.encode(zIndex, forKey: .zIndex)
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case plantID
        case itemID
        case normalizedX
        case normalizedY
        case zIndex
    }
}

public enum MiniHomePlacementError: Error, Equatable, Sendable {
    case invalidTarget
    case invalidGeometry
}
