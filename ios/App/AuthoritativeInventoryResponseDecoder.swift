import Foundation
import PlanteriorDomain

enum AuthoritativeInventoryResponseDecoder {
    static func snapshot(
        data: Data,
        expectedAccountID: String
    ) throws -> AuthoritativeInventorySnapshot {
        let object = try rootObject(data)
        try exactKeys(object, snapshotKeys)
        let response = try decode(InventorySnapshotResponse.self, data: data)
        try validateHeader(response, expectedAccountID: expectedAccountID)
        try validateSnapshotObjects(object, response: response)
        let catalog = try response.catalog.map { try $0.domainValue() }
        let ownedItems = try response.owned.map { try $0.domainValue() }
        try validateRelationships(
            response: response,
            catalog: catalog,
            ownedItems: ownedItems
        )
        guard InventorySnapshotHasher.hash(response) == response.snapshotHash else {
            throw malformed()
        }
        return AuthoritativeInventorySnapshot(
            catalog: catalog,
            ownedItems: ownedItems,
            inventoryGeneration: response.inventoryGeneration,
            snapshotHash: response.snapshotHash
        )
    }

    static func receipt(
        data: Data,
        expectedAccountID: String,
        expectedItemID: ItemID
    ) throws -> InventoryOwnershipReceipt {
        let object = try rootObject(data)
        guard let kind = object["kind"] as? String else { throw malformed() }
        if kind == "condition-not-met" {
            try conditionReceipt(
                object: object,
                data: data,
                accountID: expectedAccountID,
                itemID: expectedItemID
            )
        }
        guard kind == "acquired" || kind == "already-owned" else {
            throw malformed()
        }
        try exactKeys(object, receiptKeys)
        try validateMediaObject(object["mediaIdentity"])
        let response = try decode(InventoryReceiptResponse.self, data: data)
        try response.mediaIdentity.validate()
        return try receiptDomain(
            response,
            kind: kind,
            accountID: expectedAccountID,
            itemID: expectedItemID
        )
    }

    static func instant(milliseconds: Int64) throws -> Instant {
        let date = Date(timeIntervalSince1970: Double(milliseconds) / 1000)
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return try Instant.parse(formatter.string(from: date))
    }

    private static func validateHeader(
        _ response: InventorySnapshotResponse,
        expectedAccountID: String
    ) throws {
        guard response.contractVersion == 3,
              response.ownerUid == expectedAccountID,
              (0 ... 200).contains(response.registeredPlantCount),
              response.loadedAtEpochMillis >= 0,
              response.inventoryGeneration > 0,
              response.snapshotHash.range(
                  of: "^[a-f0-9]{64}$",
                  options: .regularExpression
              ) != nil
        else {
            throw malformed()
        }
    }

    private static func validateSnapshotObjects(
        _ object: [String: Any],
        response: InventorySnapshotResponse
    ) throws {
        let catalog = try array(object["catalog"])
        let owned = try array(object["owned"])
        guard catalog.count == response.catalog.count,
              owned.count == response.owned.count
        else {
            throw malformed()
        }
        try catalog.forEach(validateCatalogObject)
        try owned.forEach(validateOwnedObject)
    }

    private static func validateCatalogObject(_ raw: Any) throws {
        let item = try dictionary(raw)
        try exactKeys(item, catalogKeys)
        try validateMediaObject(item["mediaIdentity"])
    }

    private static func validateOwnedObject(_ raw: Any) throws {
        let owned = try dictionary(raw)
        try exactKeys(owned, ownedKeys)
        guard let value = owned["catalogSnapshot"], !(value is NSNull) else {
            return
        }
        let snapshot = try dictionary(value)
        try exactKeys(snapshot, ownedSnapshotKeys)
        try validateMediaObject(snapshot["mediaIdentity"])
    }

    private static func validateRelationships(
        response: InventorySnapshotResponse,
        catalog: [ShopItem],
        ownedItems: [OwnedItem]
    ) throws {
        let catalogIDs = Set(catalog.map(\.id))
        guard catalogIDs.count == catalog.count,
              Set(ownedItems.map(\.itemID)).count == ownedItems.count
        else {
            throw malformed()
        }
        for (wire, domain) in zip(response.owned, ownedItems) {
            let available = wire.availability == "AVAILABLE"
            guard ["AVAILABLE", "UNAVAILABLE"].contains(wire.availability),
                  available == catalogIDs.contains(domain.itemID),
                  response.partial || available
            else {
                throw malformed()
            }
        }
    }

    private static func conditionReceipt(
        object: [String: Any],
        data: Data,
        accountID: String,
        itemID: ItemID
    ) throws -> Never {
        try exactKeys(object, conditionKeys)
        let response = try decode(InventoryConditionResponse.self, data: data)
        guard response.ownerUid == accountID,
              response.itemId == itemID.rawValue,
              response.condition == "registered-plant",
              response.catalogRevision > 0
        else {
            throw malformed()
        }
        throw InventoryConditionNotMet(condition: response.condition)
    }

    private static func receiptDomain(
        _ response: InventoryReceiptResponse,
        kind: String,
        accountID: String,
        itemID: ItemID
    ) throws -> InventoryOwnershipReceipt {
        guard response.ownerUid == accountID,
              response.itemId == itemID.rawValue,
              response.catalogRevision > 0,
              response.ownershipRevision > 0,
              response.acquiredAtEpochMillis >= 0
        else {
            throw malformed()
        }
        do {
            return try InventoryOwnershipReceipt(
                kind: kind == "acquired" ? .acquired : .alreadyOwned,
                ownerID: response.ownerUid,
                itemID: ItemID.parse(response.itemId),
                catalogRevision: Revision.parse(response.catalogRevision),
                ownershipRevision: Revision.parse(response.ownershipRevision),
                acquiredAt: instant(milliseconds: response.acquiredAtEpochMillis)
            )
        } catch {
            throw malformed()
        }
    }
}
