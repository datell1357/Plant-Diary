import Combine
import Foundation
import PlanteriorData
import PlanteriorDomain

enum InventoryAcquisitionOutcome: Equatable {
    case acquired
    case conditionNotMet(String)
    case alreadyOwned
    case failed
    case unavailable
}

@MainActor
final class InventoryRepository: ObservableObject {
    @Published var catalog: [ShopItem] = []
    @Published var ownedItems: [OwnedItem] = []

    let defaults: UserDefaults
    let now: Instant?
    private let allowsLocalAcquisition: Bool
    private let failFirstAcquisition: Bool
    private var didFailAcquisition = false
    var accountID: String?

    init(
        defaults: UserDefaults = .standard,
        now: Instant?,
        allowsLocalAcquisition: Bool,
        failFirstAcquisition: Bool = false
    ) {
        self.defaults = defaults
        self.now = now
        self.allowsLocalAcquisition = allowsLocalAcquisition
        self.failFirstAcquisition = failFirstAcquisition
    }

    func mount(accountID: String?) {
        self.accountID = accountID
        guard allowsLocalAcquisition else {
            catalog = []
            ownedItems = []
            return
        }
        guard let data = defaults.data(forKey: persistenceKey),
              let snapshot = try? JSONDecoder().decode(
                  InventorySnapshot.self,
                  from: data
              )
        else {
            catalog = []
            ownedItems = []
            return
        }
        catalog = snapshot.catalog
        ownedItems = snapshot.ownedItems
    }

    @discardableResult
    func replaceFixture(
        catalog: [ShopItem],
        ownedItems: [OwnedItem]
    ) -> Bool {
        self.catalog = catalog
        self.ownedItems = ownedItems
        return persist()
    }

    func entries(
        category: ItemCategory?,
        metConditions: Set<String>
    ) -> [InventoryCatalogEntry] {
        InventoryCatalogPolicy.entries(
            items: catalog,
            ownedItemIDs: Set(ownedItems.map(\.itemID)),
            metConditions: metConditions,
            category: category
        )
    }

    func acquire(
        itemID: ItemID,
        metConditions: Set<String>
    ) -> InventoryAcquisitionOutcome {
        guard allowsLocalAcquisition else {
            return .unavailable
        }
        guard let item = catalog.first(
            where: {
                $0.id == itemID &&
                    $0.publicationState == .public
            }
        ) else {
            return .unavailable
        }
        if ownedItems.contains(where: { $0.itemID == itemID }) {
            return .alreadyOwned
        }
        if let condition = unmetCondition(
            item: item,
            metConditions: metConditions
        ) {
            return .conditionNotMet(condition)
        }
        if failFirstAcquisition, !didFailAcquisition {
            didFailAcquisition = true
            return .failed
        }
        guard let now,
              let revision = try? Revision.parse(1)
        else {
            return .failed
        }
        ownedItems.append(
            OwnedItem(
                itemID: itemID,
                acquiredAt: now,
                applied: false,
                revision: revision
            )
        )
        guard persist() else {
            ownedItems.removeLast()
            return .failed
        }
        return .acquired
    }

    @discardableResult
    func apply(_ result: ItemPlacementResult) -> Bool {
        let previous = ownedItems
        ownedItems = result.ownedItems
        guard persist() else {
            ownedItems = previous
            return false
        }
        return true
    }

    func canApply(_ result: ItemPlacementResult) -> Bool {
        encodedSnapshot(ownedItems: result.ownedItems) != nil
    }

    var allowsPlacementMutation: Bool {
        allowsLocalAcquisition
    }

    var persistenceKey: String {
        "inventory.\(accountID ?? "signed-out").snapshot"
    }

    private func persist() -> Bool {
        guard let data = encodedSnapshot(ownedItems: ownedItems) else {
            return false
        }
        defaults.set(data, forKey: persistenceKey)
        return true
    }

    private func encodedSnapshot(
        ownedItems: [OwnedItem]
    ) -> Data? {
        try? JSONEncoder().encode(
            InventorySnapshot(
                catalog: catalog,
                ownedItems: ownedItems
            )
        )
    }

    private func unmetCondition(
        item: ShopItem,
        metConditions: Set<String>
    ) -> String? {
        guard let condition = item.acquisitionCondition,
              !metConditions.contains(condition)
        else {
            return nil
        }
        return condition
    }
}

struct InventorySnapshot: Codable {
    let catalog: [ShopItem]
    let ownedItems: [OwnedItem]
}
