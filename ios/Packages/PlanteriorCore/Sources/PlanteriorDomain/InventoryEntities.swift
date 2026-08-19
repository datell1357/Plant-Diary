public struct ShopItem: Codable, Equatable, Identifiable, Sendable {
    public let id: ItemID
    public let name: String
    public let category: ItemCategory
    public let assetPath: String
    public let acquisitionCondition: String?
    public let publicationState: PublicationState
    public let revision: Revision

    public init(
        id: ItemID,
        name: String,
        category: ItemCategory,
        assetPath: String,
        acquisitionCondition: String?,
        publicationState: PublicationState,
        revision: Revision
    ) {
        self.id = id
        self.name = name
        self.category = category
        self.assetPath = assetPath
        self.acquisitionCondition = acquisitionCondition
        self.publicationState = publicationState
        self.revision = revision
    }
}

public struct OwnedItem: Codable, Equatable, Sendable {
    public let itemID: ItemID
    public let acquiredAt: Instant
    public let applied: Bool
    public let revision: Revision

    public init(
        itemID: ItemID,
        acquiredAt: Instant,
        applied: Bool,
        revision: Revision
    ) {
        self.itemID = itemID
        self.acquiredAt = acquiredAt
        self.applied = applied
        self.revision = revision
    }
}
