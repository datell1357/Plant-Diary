import PlanteriorData
import SwiftUI

extension InventoryView {
    var warehouse: some View {
        let ownedIDs = Set(repository.ownedItems.map(\.itemID))
        let items = repository.catalog.filter {
            ownedIDs.contains($0.id) &&
                (category == nil || $0.category == category)
        }
        return Group {
            if items.isEmpty {
                Text("보유한 아이템이 없어요.")
                    .accessibilityIdentifier("storage.empty")
            } else {
                ForEach(items, id: \.id) { item in
                    warehouseRow(item)
                }
            }
        }
    }

    var shopEntries: [InventoryCatalogEntry] {
        let entries = repository.entries(
            category: category,
            metConditions: metConditions
        )
        return sortDescending ? Array(entries.reversed()) : entries
    }

    var shopPage: InventoryCatalogPage {
        InventoryCatalogPolicy.page(
            entries: shopEntries,
            after: nil,
            limit: visibleItemLimit
        )
    }

    var metConditions: Set<String> {
        collection.plants.isEmpty ? [] : ["registered-plant"]
    }
}
