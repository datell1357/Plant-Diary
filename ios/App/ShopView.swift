import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct ShopView: View {
    @Environment(\.sizeCategory) var sizeCategory
    let entries: [InventoryCatalogEntry]
    let rowSpacing: CGFloat
    let acquire: (ShopItem) -> Void
    let showDetail: (ShopItem) -> Void

    var body: some View {
        Group {
            if entries.isEmpty {
                Text("조건에 맞는 공개 아이템이 없어요.")
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .padding(.horizontal, PlanteriorSpacing.extraLarge)
                    .accessibilityIdentifier("shop.empty")
            } else {
                LazyVGrid(
                    columns: columns,
                    alignment: .leading,
                    spacing: rowSpacing
                ) {
                    ForEach(entries, id: \.item.id) { entry in
                        shopCard(entry)
                    }
                }
                .padding(.horizontal, PlanteriorSpacing.large)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("아이템 상점 상품 목록")
        .accessibilityIdentifier("shop.ready")
    }

    private var columns: [GridItem] {
        if sizeCategory.isAccessibilityCategory {
            return [GridItem(.flexible())]
        }
        return Array(
            repeating: GridItem(
                .fixed(InventoryReferenceMetrics.shopCardWidth),
                spacing: InventoryReferenceMetrics.shopGridColumnSpacing
            ),
            count: 2
        )
    }
}
