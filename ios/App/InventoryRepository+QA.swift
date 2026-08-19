import Foundation
import PlanteriorDomain

extension InventoryRepository {
    func seedQAIfNeeded(processInfo: ProcessInfo = .processInfo) {
        #if DEBUG
            resetQAIfNeeded(processInfo: processInfo)
            guard processInfo.environment["QA_INVENTORY_FIXTURE"] == "1",
                  catalog.isEmpty,
                  let fixture = Self.qaFixture(now: now)
            else {
                return
            }
            replaceFixture(
                catalog: fixture.catalog,
                ownedItems: fixture.ownedItems
            )
        #endif
    }

    private func resetQAIfNeeded(processInfo: ProcessInfo) {
        guard let resetToken = processInfo.environment[
            "QA_INVENTORY_RESET_TOKEN"
        ], defaults.string(
            forKey: "qa.inventory.reset-token"
        ) != resetToken else {
            return
        }
        defaults.removeObject(forKey: persistenceKey)
        defaults.set(resetToken, forKey: "qa.inventory.reset-token")
        catalog = []
        ownedItems = []
    }

    private static func qaFixture(
        now: Instant?
    ) -> InventorySnapshot? {
        guard let now,
              let revision = try? Revision.parse(1),
              let chairID = try? ItemID.parse("item-chair"),
              let lampID = try? ItemID.parse("item-lamp"),
              let wallID = try? ItemID.parse("item-green-wall")
        else {
            return nil
        }
        return InventorySnapshot(
            catalog: qaCatalog(
                revision: revision,
                chairID: chairID,
                lampID: lampID,
                wallID: wallID
            ),
            ownedItems: [
                OwnedItem(
                    itemID: chairID,
                    acquiredAt: now,
                    applied: false,
                    revision: revision
                )
            ]
        )
    }

    private static func qaCatalog(
        revision: Revision,
        chairID: ItemID,
        lampID: ItemID,
        wallID: ItemID
    ) -> [ShopItem] {
        [
            ShopItem(
                id: wallID,
                name: "초록 벽지",
                category: .background,
                assetPath: "items/green-wall.png",
                acquisitionCondition: nil,
                publicationState: .public,
                revision: revision
            ),
            ShopItem(
                id: chairID,
                name: "원목 의자",
                category: .furniture,
                assetPath: "items/chair.png",
                acquisitionCondition: nil,
                publicationState: .public,
                revision: revision
            ),
            ShopItem(
                id: lampID,
                name: "새싹 조명",
                category: .decoration,
                assetPath: "items/lamp.png",
                acquisitionCondition: "registered-plant",
                publicationState: .public,
                revision: revision
            )
        ]
    }
}
