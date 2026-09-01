import PlanteriorDesignSystem
import SwiftUI

extension InventoryView {
    @ViewBuilder
    var categoryFilters: some View {
        if effectiveSizeCategory.isAccessibilityCategory {
            if mode == .warehouse {
                VStack(spacing: PlanteriorSpacing.small) {
                    HStack(spacing: PlanteriorSpacing.small) {
                        allWarehouseCategoryButton
                            .frame(maxWidth: .infinity)
                        warehouseCategoryButton(.wall)
                            .frame(maxWidth: .infinity)
                    }
                    HStack(spacing: PlanteriorSpacing.small) {
                        warehouseCategoryButton(.floor)
                            .frame(maxWidth: .infinity)
                        warehouseCategoryButton(.furniture)
                            .frame(maxWidth: .infinity)
                    }
                    HStack(spacing: PlanteriorSpacing.small) {
                        warehouseCategoryButton(.decoration)
                            .frame(maxWidth: .infinity)
                        Color.clear
                            .frame(maxWidth: .infinity)
                            .accessibilityHidden(true)
                    }
                }
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
                .padding(.vertical, PlanteriorSpacing.small)
            } else {
                VStack(spacing: PlanteriorSpacing.small) {
                    HStack(spacing: PlanteriorSpacing.small) {
                        categoryButton("전체", category: nil, identifier: "all")
                            .frame(maxWidth: .infinity)
                        categoryButton(
                            "배경",
                            category: .background,
                            identifier: "background"
                        )
                        .frame(maxWidth: .infinity)
                    }
                    HStack(spacing: PlanteriorSpacing.small) {
                        categoryButton(
                            "가구",
                            category: .furniture,
                            identifier: "furniture"
                        )
                        .frame(maxWidth: .infinity)
                        categoryButton(
                            "장식",
                            category: .decoration,
                            identifier: "decoration"
                        )
                        .frame(maxWidth: .infinity)
                    }
                    seasonalButton.frame(maxWidth: .infinity)
                }
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
                .padding(.vertical, PlanteriorSpacing.small)
            }
        } else if mode == .warehouse {
            HStack(spacing: PlanteriorSpacing.small) {
                allWarehouseCategoryButton
                    .frame(maxWidth: .infinity)
                ForEach(InventoryRoomFilter.allCases, id: \.self) { filter in
                    warehouseCategoryButton(filter)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, PlanteriorSpacing.extraLarge)
            .frame(height: InventoryReferenceMetrics.filterTrackHeight)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: PlanteriorSpacing.small) {
                    categoryButton("전체", category: nil, identifier: "all")
                    categoryButton("배경", category: .background, identifier: "background")
                    categoryButton("가구", category: .furniture, identifier: "furniture")
                    categoryButton("장식", category: .decoration, identifier: "decoration")
                    seasonalButton
                }
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
            }
            .frame(height: InventoryReferenceMetrics.filterTrackHeight)
        }
    }
}
