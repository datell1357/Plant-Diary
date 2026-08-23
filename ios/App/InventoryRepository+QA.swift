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

    private static func qaFixture(now: Instant?) -> InventorySnapshot? {
        guard let now,
              let revision = try? Revision.parse(1)
        else {
            return nil
        }

        let catalog: [ShopItem?] = [
            qaItem("item-mini-shelf", "미니 책장", .furniture, .draft, revision),
            qaItem("item-small-rug", "작은 러그", .decoration, .draft, revision),
            qaItem("item-window-frame", "창문 프레임", .decoration, .draft, revision),
            qaItem("item-flower-stand", "화분 거치대", .furniture, .draft, revision),
            qaItem("item-lamp", "감성 조명", .decoration, .draft, revision),
            qaItem("item-wall-art", "벽 그림", .decoration, .draft, revision),
            qaItem("item-chair", "미니 테이블", .furniture, .draft, revision),
            qaItem("item-cushion", "쿠션", .decoration, .draft, revision),
            qaItem("item-book-cart", "북 카트", .furniture, .draft, revision),
            qaItem("item-plant-rack", "식물 선반", .furniture, .draft, revision),
            qaItem("item-round-mat", "원형 매트", .decoration, .draft, revision),
            qaItem("item-cozy-rug", "포근한 러그", .furniture, .public, revision),
            qaItem("item-vintage-lamp", "빈티지 스탠드 조명", .decoration, .public, revision),
            qaItem("item-green-wall", "체크무늬 커튼 창문", .background, .public, revision),
            qaItem("item-succulent-pot", "귀여운 다육이 화분", .decoration, .public, revision),
            qaItem(
                "item-christmas-tree",
                "크리스마스 트리",
                .decoration,
                .public,
                revision,
                "winter-season"
            ),
            qaItem(
                "item-autumn-frame",
                "가을 단풍 벽장식",
                .decoration,
                .public,
                revision,
                "autumn-season"
            )
        ]
        let resolvedCatalog = catalog.compactMap(\.self)
        guard resolvedCatalog.count == catalog.count else {
            return nil
        }

        let ownedIDs = [
            "item-mini-shelf", "item-small-rug", "item-window-frame",
            "item-flower-stand", "item-lamp", "item-wall-art", "item-chair",
            "item-cushion", "item-book-cart", "item-plant-rack",
            "item-round-mat", "item-cozy-rug"
        ]
        let appliedIDs = Set([
            "item-mini-shelf", "item-small-rug", "item-flower-stand"
        ])
        let ownedItems = ownedIDs.compactMap { rawID -> OwnedItem? in
            guard let itemID = try? ItemID.parse(rawID) else {
                return nil
            }
            return OwnedItem(
                itemID: itemID,
                acquiredAt: now,
                applied: appliedIDs.contains(rawID),
                revision: revision
            )
        }
        guard ownedItems.count == ownedIDs.count else {
            return nil
        }
        return InventorySnapshot(
            catalog: resolvedCatalog,
            ownedItems: ownedItems
        )
    }

    private static func qaItem(
        _ rawID: String,
        _ name: String,
        _ category: ItemCategory,
        _ publicationState: PublicationState,
        _ revision: Revision,
        _ acquisitionCondition: String? = nil
    ) -> ShopItem? {
        guard let itemID = try? ItemID.parse(rawID) else {
            return nil
        }
        return ShopItem(
            id: itemID,
            name: name,
            category: category,
            assetPath: "items/\(rawID).png",
            acquisitionCondition: acquisitionCondition,
            publicationState: publicationState,
            revision: revision
        )
    }
}
