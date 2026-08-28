import Foundation
import PlanteriorDomain

struct InventorySnapshotResponse: Decodable {
    let contractVersion: Int
    let ownerUid: String
    let catalog: [InventoryCatalogResponse]
    let owned: [InventoryOwnedResponse]
    let registeredPlantCount: Int
    let loadedAtEpochMillis: Int64
    let partial: Bool
    let inventoryGeneration: UInt64
    let snapshotHash: String
}

struct InventoryCatalogResponse: Decodable {
    let itemId: String
    let name: String
    let description: String
    let category: String
    let mediaIdentity: InventoryMediaResponse
    let acquisitionCondition: String?
    let revision: UInt64
    let updatedAtEpochMillis: Int64

    func domainValue() throws -> ShopItem {
        guard !name.isEmpty,
              name.count <= 100,
              !description.isEmpty,
              description.count <= 500,
              [nil, "registered-plant"].contains(acquisitionCondition),
              updatedAtEpochMillis >= 0,
              StorageItemPresentation.acceptsBundledMedia(
                  itemID: itemId,
                  media: mediaIdentity
              ),
              let category = ItemCategory(rawValue: category)
        else {
            throw InventoryProviderError.malformedResponse
        }
        do {
            try mediaIdentity.validate()
            return try ShopItem(
                id: ItemID.parse(itemId),
                name: name,
                category: category,
                assetPath: mediaIdentity.path,
                acquisitionCondition: acquisitionCondition,
                publicationState: .public,
                revision: Revision.parse(revision)
            )
        } catch let error as InventoryProviderError {
            throw error
        } catch {
            throw InventoryProviderError.malformedResponse
        }
    }
}

struct InventoryOwnedResponse: Decodable {
    let itemId: String
    let acquiredAtEpochMillis: Int64
    let applied: Bool
    let revision: UInt64
    let availability: String
    let catalogSnapshot: InventoryOwnedCatalogSnapshotResponse?

    func domainValue() throws -> OwnedItem {
        guard acquiredAtEpochMillis >= 0 else {
            throw InventoryProviderError.malformedResponse
        }
        do {
            try catalogSnapshot?.validate()
            return try OwnedItem(
                itemID: ItemID.parse(itemId),
                acquiredAt: AuthoritativeInventoryResponseDecoder.instant(
                    milliseconds: acquiredAtEpochMillis
                ),
                applied: applied,
                revision: Revision.parse(revision)
            )
        } catch let error as InventoryProviderError {
            throw error
        } catch {
            throw InventoryProviderError.malformedResponse
        }
    }
}

struct InventoryOwnedCatalogSnapshotResponse: Decodable {
    let name: String
    let category: String
    let mediaIdentity: InventoryMediaResponse
    let catalogRevision: UInt64

    func validate() throws {
        guard !name.isEmpty,
              name.count <= 100,
              ItemCategory(rawValue: category) != nil,
              catalogRevision > 0
        else {
            throw InventoryProviderError.malformedResponse
        }
        try mediaIdentity.validate()
    }
}

struct InventoryMediaResponse: Decodable {
    let path: String
    let sha256: String
    let byteSize: Int64
    let mimeType: String
    let width: Int
    let height: Int
    let mediaRevision: UInt64

    func validate() throws {
        guard !path.isEmpty,
              path.count <= 1024,
              sha256.range(
                  of: "^[a-f0-9]{64}$",
                  options: .regularExpression
              ) != nil,
              byteSize > 0,
              ["image/png", "image/jpeg", "image/webp"].contains(mimeType),
              (1 ... 32768).contains(width),
              (1 ... 32768).contains(height),
              mediaRevision > 0,
              mediaRevision <= Revision.maximumWireValue
        else {
            throw InventoryProviderError.malformedResponse
        }
    }
}

struct InventoryReceiptResponse: Decodable {
    let kind: String
    let ownerUid: String
    let itemId: String
    let catalogRevision: UInt64
    let ownershipRevision: UInt64
    let acquiredAtEpochMillis: Int64
    let mediaIdentity: InventoryMediaResponse
}

struct InventoryConditionResponse: Decodable {
    let kind: String
    let ownerUid: String
    let itemId: String
    let catalogRevision: UInt64
    let condition: String
}

struct InventoryConditionNotMet: Error, Equatable, Sendable {
    let condition: String
}
