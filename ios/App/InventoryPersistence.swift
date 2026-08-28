import PlanteriorDomain

enum InventoryMutationFailure: Equatable {
    case notAuthenticated
    case localAcquisitionDisabled
    case providerUnavailable
    case invalidProviderResponse
    case itemUnavailable
    case clockUnavailable
    case persistenceFailed
    case roomUnavailable
    case roomConflict
    case injectedFailure
}

enum InventoryAcquisitionOutcome: Equatable {
    case acquired
    case conditionNotMet(String)
    case alreadyOwned
    case failed(InventoryMutationFailure)
}

enum InventorySnapshotSource: String, Codable {
    case local
    case qaFixture
    case serverAuthorized
    case legacy
}

struct InventorySnapshotProvenance: Codable, Equatable {
    let ownerID: String
    let inventoryGeneration: UInt64
    let snapshotHash: String
}

struct InventorySnapshot: Codable {
    let catalog: [ShopItem]
    let ownedItems: [OwnedItem]
    let source: InventorySnapshotSource
    let provenance: InventorySnapshotProvenance?

    init(
        catalog: [ShopItem],
        ownedItems: [OwnedItem],
        source: InventorySnapshotSource,
        provenance: InventorySnapshotProvenance? = nil
    ) {
        self.catalog = catalog
        self.ownedItems = ownedItems
        self.source = source
        self.provenance = provenance
    }

    private enum CodingKeys: String, CodingKey {
        case catalog
        case ownedItems
        case source
        case provenance
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        catalog = try container.decode([ShopItem].self, forKey: .catalog)
        ownedItems = try container.decode([OwnedItem].self, forKey: .ownedItems)
        source = try container.decodeIfPresent(
            InventorySnapshotSource.self,
            forKey: .source
        ) ?? .legacy
        provenance = try container.decodeIfPresent(
            InventorySnapshotProvenance.self,
            forKey: .provenance
        )
    }
}
