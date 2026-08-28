import Foundation
@testable import Planterior
import PlanteriorDomain

extension AuthoritativeInventoryServiceTests {
    static func snapshot(
        item: ShopItem,
        owned: [OwnedItem],
        generation: UInt64,
        hash: Character
    ) -> AuthoritativeInventorySnapshot {
        AuthoritativeInventorySnapshot(
            catalog: [item],
            ownedItems: owned,
            inventoryGeneration: generation,
            snapshotHash: String(repeating: hash, count: 64)
        )
    }

    static func serverItem() throws -> ShopItem {
        try ShopItem(
            id: ItemID.parse("item-lamp"),
            name: "Lamp",
            category: .decoration,
            assetPath: "items/item-lamp.png",
            acquisitionCondition: nil,
            publicationState: .public,
            revision: Revision.parse(3)
        )
    }

    static func validReceiptData() throws -> Data {
        try sharedContractData(named: "receipt")
    }

    static func validAlreadyOwnedReceiptData() throws -> Data {
        try sharedContractData(named: "alreadyOwnedReceipt")
    }

    static let conditionReceiptData = Data(
        """
        {
          "kind": "condition-not-met",
          "ownerUid": "inventory-account-a",
          "itemId": "item-lamp",
          "catalogRevision": 3,
          "condition": "registered-plant"
        }
        """.utf8
    )

    static func validSnapshotData() throws -> Data {
        try sharedContractData(named: "snapshot")
    }

    private static func sharedContractData(named key: String) throws -> Data {
        var directory = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
        let fileManager = FileManager.default
        while directory.path != "/" {
            let fixture = directory
                .appendingPathComponent("docs/ios/inventory-contract-v3.fixture.json")
            if fileManager.fileExists(atPath: fixture.path) {
                let data = try Data(contentsOf: fixture)
                let root = try JSONSerialization.jsonObject(with: data)
                guard let object = root as? [String: Any], let value = object[key] else {
                    throw InventoryProviderError.malformedResponse
                }
                return try JSONSerialization.data(withJSONObject: value)
            }
            directory.deleteLastPathComponent()
        }
        throw InventoryProviderError.malformedResponse
    }
}

@MainActor
final class InventoryServiceFake: AuthoritativeInventoryService {
    private let loads: [AuthoritativeInventorySnapshot]
    private let receipt: InventoryOwnershipReceipt
    private var loadIndex = 0

    init(
        loads: [AuthoritativeInventorySnapshot],
        receipt: InventoryOwnershipReceipt
    ) {
        self.loads = loads
        self.receipt = receipt
    }

    func load(accountID: String) async throws -> AuthoritativeInventorySnapshot {
        guard loadIndex < loads.count else {
            throw InventoryProviderError.unavailable
        }
        defer { loadIndex += 1 }
        return loads[loadIndex]
    }

    func acquire(
        accountID: String,
        itemID: ItemID,
        expectedCatalogRevision: Revision,
        operationID: OperationID
    ) async throws -> InventoryOwnershipReceipt {
        receipt
    }
}
