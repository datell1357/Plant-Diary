import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension InventoryView {
    var warehouse: some View {
        let items = warehouseItems
        return VStack(alignment: .leading, spacing: 0) {
            (Text("보유 아이템 ")
                .foregroundColor(PlanteriorPalette.textSecondary.color)
                + Text("\(items.count)개")
                .foregroundColor(PlanteriorPalette.accent.color)
                .bold())
                .font(PlanteriorTypography.caption)
                .lineLimit(nil)
                .fixedSize(horizontal: false, vertical: true)
                .frame(
                    minHeight: effectiveSizeCategory.isAccessibilityCategory
                        ? InventoryReferenceMetrics.accessibilityCountMinimumHeight
                        : InventoryReferenceMetrics.countTrackHeight,
                    alignment: .center
                )
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
                .accessibilityIdentifier("storage.count")
            if items.isEmpty {
                Text("보유한 아이템이 없어요.")
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .padding(.horizontal, PlanteriorSpacing.extraLarge)
                    .accessibilityIdentifier("storage.empty")
            } else {
                VStack(spacing: 0) {
                    warehouseGrid(Array(items.prefix(8)))
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("storage.resting.grid")
                    if items.count > 8 {
                        Color.clear
                            .frame(height: PlanteriorSpacing.section * 6)
                            .accessibilityHidden(true)
                        warehouseGrid(Array(items.dropFirst(8)))
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("storage.overflow.grid")
                    }
                }
                .padding(
                    .top,
                    effectiveSizeCategory.isAccessibilityCategory
                        ? InventoryReferenceMetrics.accessibilityCountToGridSpacing
                        : PlanteriorSpacing.none
                )
                .padding(.horizontal, PlanteriorSpacing.large)
            }
        }
    }

    private func warehouseGrid(_ items: [ShopItem]) -> some View {
        LazyVGrid(
            columns: storageColumns,
            alignment: .leading,
            spacing: InventoryReferenceMetrics.gridSpacing
        ) {
            ForEach(items, id: \.id) { item in
                warehouseCard(item)
            }
        }
    }

    var warehouseItems: [ShopItem] {
        let ownedIDs = Set(repository.ownedItems.map(\.itemID))
        return repository.catalog.filter {
            ownedIDs.contains($0.id) &&
                (category == nil || $0.category == category)
        }
    }

    var shopEntries: [InventoryCatalogEntry] {
        repository.entries(
            category: category,
            metConditions: metConditions
        )
        .filter {
            !seasonalOnly || StorageItemPresentation.isSeasonal($0.item)
        }
        .sorted {
            let first = StorageItemPresentation.shopOrder($0.item)
            let second = StorageItemPresentation.shopOrder($1.item)
            return sortDescending ? first > second : first < second
        }
    }

    var shopPage: InventoryCatalogPage {
        InventoryCatalogPolicy.page(
            entries: shopEntries,
            after: nil,
            limit: visibleItemLimit
        )
    }

    var metConditions: Set<String> {
        var conditions: Set<String> = collection.plants.isEmpty
            ? []
            : ["registered-plant"]
        progression.snapshot?.earnedMilestoneIDs.forEach {
            conditions.insert($0.rawValue)
        }
        return conditions
    }

    func eligibility(for item: ShopItem) -> InventoryAcquisitionEligibility {
        repository.entries(
            category: item.category,
            metConditions: metConditions
        ).first { $0.item.id == item.id }?.eligibility ?? .eligible
    }

    func isOwned(_ item: ShopItem) -> Bool {
        repository.ownedItems.contains { $0.itemID == item.id }
    }

    func isApplied(_ item: ShopItem) -> Bool {
        repository.ownedItems.first { $0.itemID == item.id }?.applied == true
    }
}
