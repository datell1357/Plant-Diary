import Foundation
import PlanteriorDomain

extension InventoryRepository {
    func acquire(
        itemID: ItemID,
        metConditions: Set<String>
    ) async -> InventoryAcquisitionOutcome {
        if allowsLocalAcquisition {
            return acquireLocally(
                itemID: itemID,
                metConditions: metConditions
            )
        }
        guard let mountedAccountID = accountID else {
            return .failed(.notAuthenticated)
        }
        let requestGeneration = inventoryRequestGeneration
        guard isAuthoritativeForCurrentMount else {
            return .failed(.providerUnavailable)
        }
        guard let item = catalog.first(where: {
            $0.id == itemID && $0.publicationState == .public
        }) else {
            return .failed(.itemUnavailable)
        }
        guard let operationID = try? OperationID.parse(UUID().uuidString) else {
            return .failed(.invalidProviderResponse)
        }
        return await acquireAuthoritatively(
            item: item,
            accountID: mountedAccountID,
            operationID: operationID,
            requestGeneration: requestGeneration
        )
    }

    private func acquireAuthoritatively(
        item: ShopItem,
        accountID mountedAccountID: String,
        operationID: OperationID,
        requestGeneration: Int
    ) async -> InventoryAcquisitionOutcome {
        do {
            let receipt = try await authoritativeService.acquire(
                accountID: mountedAccountID,
                itemID: item.id,
                expectedCatalogRevision: item.revision,
                operationID: operationID
            )
            guard accountID == mountedAccountID,
                  inventoryRequestGeneration == requestGeneration,
                  receipt.ownerID == mountedAccountID,
                  receipt.itemID == item.id,
                  receipt.catalogRevision == item.revision
            else {
                return .failed(.invalidProviderResponse)
            }
            guard await refreshAuthoritative(force: true) else {
                return .failed(.providerUnavailable)
            }
            guard ownedItems.contains(where: {
                $0.itemID == item.id && $0.revision == receipt.ownershipRevision
            }) else {
                return .failed(.invalidProviderResponse)
            }
            return receipt.kind == .acquired ? .acquired : .alreadyOwned
        } catch let error as InventoryConditionNotMet {
            return .conditionNotMet(error.condition)
        } catch let error as InventoryProviderError {
            return .failed(mutationFailure(for: error))
        } catch {
            return .failed(.providerUnavailable)
        }
    }

    private func acquireLocally(
        itemID: ItemID,
        metConditions: Set<String>
    ) -> InventoryAcquisitionOutcome {
        guard accountID != nil else {
            return .failed(.notAuthenticated)
        }
        guard let item = catalog.first(where: {
            $0.id == itemID && $0.publicationState == .public
        }) else {
            return .failed(.itemUnavailable)
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
            return .failed(.injectedFailure)
        }
        guard let now,
              let revision = try? Revision.parse(1)
        else {
            return .failed(.clockUnavailable)
        }
        ownedItems.append(
            OwnedItem(
                itemID: itemID,
                acquiredAt: now,
                applied: false,
                revision: revision
            )
        )
        guard persist(source: .local, provenance: nil) else {
            ownedItems.removeLast()
            return .failed(.persistenceFailed)
        }
        return .acquired
    }

    private func mutationFailure(
        for error: InventoryProviderError
    ) -> InventoryMutationFailure {
        switch error {
        case .unauthenticated, .forbidden: .notAuthenticated
        case .itemUnavailable, .catalogChanged: .itemUnavailable
        case .malformedResponse, .idempotencyMismatch: .invalidProviderResponse
        case .unavailable: .providerUnavailable
        }
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
