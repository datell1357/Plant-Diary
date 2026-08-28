import Foundation
import PlanteriorData
import PlanteriorDomain

extension InventoryRepository {
    @discardableResult
    func apply(_ result: ItemPlacementResult) -> Bool {
        guard allowsPlacementMutation else { return false }
        let previous = ownedItems
        ownedItems = result.ownedItems
        guard allowsLocalAcquisition else {
            return true
        }
        guard persist(source: .local, provenance: nil) else {
            ownedItems = previous
            return false
        }
        return true
    }

    func canApply(_ result: ItemPlacementResult) -> Bool {
        guard allowsPlacementMutation else { return false }
        guard !allowsLocalAcquisition else {
            return encodedSnapshot(
                ownedItems: result.ownedItems,
                source: .local,
                provenance: nil
            ) != nil
        }
        return true
    }

    var allowsPlacementMutation: Bool {
        accountID != nil && isAuthoritativeForCurrentMount
    }

    var persistenceKey: String {
        "inventory.\(accountID ?? "signed-out").snapshot"
    }

    func persistedSnapshot() -> InventorySnapshot? {
        guard let data = defaults.data(forKey: persistenceKey) else {
            return nil
        }
        return try? JSONDecoder().decode(InventorySnapshot.self, from: data)
    }

    func persist(
        source: InventorySnapshotSource,
        provenance: InventorySnapshotProvenance?,
        ownedItems: [OwnedItem]? = nil
    ) -> Bool {
        guard accountID != nil,
              let data = encodedSnapshot(
                  ownedItems: ownedItems ?? self.ownedItems,
                  source: source,
                  provenance: provenance
              )
        else {
            return false
        }
        defaults.set(data, forKey: persistenceKey)
        return true
    }

    private func encodedSnapshot(
        ownedItems: [OwnedItem],
        source: InventorySnapshotSource,
        provenance: InventorySnapshotProvenance?
    ) -> Data? {
        try? JSONEncoder().encode(
            InventorySnapshot(
                catalog: catalog,
                ownedItems: ownedItems,
                source: source,
                provenance: provenance
            )
        )
    }

    func applyAuthoritative(
        _ snapshot: AuthoritativeInventorySnapshot,
        accountID mountedAccountID: String
    ) -> Bool {
        guard accepts(snapshot, for: mountedAccountID) else {
            return false
        }
        let serverOwnedItems = snapshot.ownedItems
        let nextProvenance = InventorySnapshotProvenance(
            ownerID: mountedAccountID,
            inventoryGeneration: snapshot.inventoryGeneration,
            snapshotHash: snapshot.snapshotHash
        )
        let previousCatalog = catalog
        let previousOwnedItems = ownedItems
        let previousProvenance = provenance
        catalog = snapshot.catalog
        ownedItems = serverOwnedItems
        provenance = nextProvenance
        guard persist(
            source: .serverAuthorized,
            provenance: nextProvenance,
            ownedItems: serverOwnedItems
        ) else {
            catalog = previousCatalog
            ownedItems = previousOwnedItems
            provenance = previousProvenance
            isAuthoritativeForCurrentMount = false
            return false
        }
        isAuthoritativeForCurrentMount = true
        return true
    }

    private func accepts(
        _ snapshot: AuthoritativeInventorySnapshot,
        for accountID: String
    ) -> Bool {
        guard let provenance,
              provenance.ownerID == accountID
        else {
            return true
        }
        guard snapshot.inventoryGeneration >= provenance.inventoryGeneration else {
            return false
        }
        return snapshot.inventoryGeneration != provenance.inventoryGeneration
            || snapshot.snapshotHash == provenance.snapshotHash
    }
}
