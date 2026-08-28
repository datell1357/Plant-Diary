import PlanteriorData
import PlanteriorDomain

enum InventoryPlacementOutcome: Equatable {
    case applied
    case removed
    case unowned
    case limitReached
    case failed(InventoryMutationFailure)
}

@MainActor
struct InventoryPlacementService {
    func toggle(
        item: ShopItem,
        inventory: InventoryRepository,
        miniHome: MiniHomeStore
    ) async -> InventoryPlacementOutcome {
        guard inventory.allowsPlacementMutation else {
            return .failed(
                miniHome.accountID == nil
                    ? .notAuthenticated
                    : .localAcquisitionDisabled
            )
        }
        guard miniHome.accountID != nil else {
            return .failed(.notAuthenticated)
        }
        guard let room = miniHome.draft ?? miniHome.committed else {
            return .failed(.roomUnavailable)
        }
        let isApplied = room.placements.contains { $0.itemID == item.id }
        do {
            let result = try placementResult(
                item: item,
                inventory: inventory,
                room: room
            )
            miniHome.replaceDraftPlacements(result.placements)
            await miniHome.save()
            switch miniHome.state {
            case .saved:
                inventory.synchronizeAppliedItems(with: miniHome.committed)
                return isApplied ? .removed : .applied
            case .conflicted:
                return .failed(.roomConflict)
            case .idle, .mounting, .refreshing, .saving, .failed, .loadFailed:
                return .failed(.persistenceFailed)
            }
        } catch ItemPlacementError.unownedItem {
            return .unowned
        } catch ItemPlacementError.categoryLimitReached {
            return .limitReached
        } catch {
            return .failed(.persistenceFailed)
        }
    }

    private func placementResult(
        item: ShopItem,
        inventory: InventoryRepository,
        room: MiniHome
    ) throws -> ItemPlacementResult {
        if room.placements.contains(where: { $0.itemID == item.id }) {
            return ItemPlacementCoordinator.remove(
                itemID: item.id,
                ownedItems: inventory.ownedItems,
                placements: room.placements
            )
        }
        return try ItemPlacementCoordinator.apply(
            item: item,
            ownedItems: inventory.ownedItems,
            placements: room.placements,
            catalogItems: inventory.catalog,
            position: MiniHomePosition(
                normalizedX: 0.5,
                normalizedY: 0.62
            )
        )
    }
}
