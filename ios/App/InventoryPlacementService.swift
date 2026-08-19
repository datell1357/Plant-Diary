import Foundation
import PlanteriorData
import PlanteriorDomain

enum InventoryPlacementOutcome: Equatable {
    case applied
    case removed
    case unowned
    case limitReached
    case failed
    case unavailable
}

@MainActor
struct InventoryPlacementService {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func toggle(
        item: ShopItem,
        inventory: InventoryRepository,
        accountID: String?,
        now: Instant?
    ) -> InventoryPlacementOutcome {
        guard inventory.allowsPlacementMutation,
              let now
        else {
            return .unavailable
        }
        let miniHome = LocalMiniHomeRepository(
            accountID: accountID,
            defaults: defaults,
            now: now
        )
        guard let room = miniHome.load() else {
            return .unavailable
        }
        let isApplied = inventory.ownedItems.first(
            where: { $0.itemID == item.id }
        )?.applied == true
        do {
            let result = try placementResult(
                item: item,
                inventory: inventory,
                room: room,
                isApplied: isApplied
            )
            guard inventory.canApply(result) else {
                return .failed
            }
            return try commit(
                result: result,
                previousRoom: room,
                inventory: inventory,
                miniHome: miniHome,
                success: isApplied ? .removed : .applied
            )
        } catch ItemPlacementError.unownedItem {
            return .unowned
        } catch ItemPlacementError.categoryLimitReached {
            return .limitReached
        } catch {
            return .failed
        }
    }

    private func placementResult(
        item: ShopItem,
        inventory: InventoryRepository,
        room: MiniHome,
        isApplied: Bool
    ) throws -> ItemPlacementResult {
        if isApplied {
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

    private func commit(
        result: ItemPlacementResult,
        previousRoom: MiniHome,
        inventory: InventoryRepository,
        miniHome: LocalMiniHomeRepository,
        success: InventoryPlacementOutcome
    ) throws -> InventoryPlacementOutcome {
        let draft = MiniHome(
            id: previousRoom.id,
            name: previousRoom.name,
            placements: result.placements,
            revision: previousRoom.revision,
            updatedAt: previousRoom.updatedAt
        )
        switch try miniHome.save(
            draft: draft,
            expectedRevision: previousRoom.revision
        ) {
        case .committed:
            return inventory.apply(result)
                ? success
                : .failed
        case .conflict, .failed:
            return .failed
        }
    }
}
