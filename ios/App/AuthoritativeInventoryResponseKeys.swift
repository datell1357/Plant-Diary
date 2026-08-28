extension AuthoritativeInventoryResponseDecoder {
    static let snapshotKeys: Set<String> = [
        "contractVersion", "ownerUid", "catalog", "owned",
        "registeredPlantCount", "loadedAtEpochMillis", "partial",
        "inventoryGeneration", "snapshotHash"
    ]
    static let catalogKeys: Set<String> = [
        "itemId", "name", "description", "category", "mediaIdentity",
        "acquisitionCondition", "revision", "updatedAtEpochMillis"
    ]
    static let ownedKeys: Set<String> = [
        "itemId", "acquiredAtEpochMillis", "applied", "revision",
        "availability", "catalogSnapshot"
    ]
    static let ownedSnapshotKeys: Set<String> = [
        "name", "category", "mediaIdentity", "catalogRevision"
    ]
    static let mediaKeys: Set<String> = [
        "path", "sha256", "byteSize", "mimeType", "width", "height",
        "mediaRevision"
    ]
    static let receiptKeys: Set<String> = [
        "kind", "ownerUid", "itemId", "catalogRevision", "ownershipRevision",
        "acquiredAtEpochMillis", "mediaIdentity"
    ]
    static let conditionKeys: Set<String> = [
        "kind", "ownerUid", "itemId", "catalogRevision", "condition"
    ]
}
