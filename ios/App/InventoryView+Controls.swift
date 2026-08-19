import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension InventoryView {
    var categoryFilters: some View {
        LazyVGrid(columns: categoryColumns, spacing: 8) {
            categoryButton("전체", category: nil)
            categoryButton("배경", category: .background)
            categoryButton("가구", category: .furniture)
            categoryButton("소품", category: .decoration)
        }
    }

    private var categoryColumns: [GridItem] {
        let count = effectiveSizeCategory.isAccessibilityCategory ? 2 : 4
        return Array(
            repeating: GridItem(.flexible(), spacing: 8),
            count: count
        )
    }

    var modeSelector: some View {
        HStack(spacing: 8) {
            modeButton("보유", mode: .warehouse)
            modeButton("상점", mode: .shop)
        }
    }

    private func categoryButton(
        _ title: String,
        category selectedCategory: ItemCategory?
    ) -> some View {
        Button(title) {
            category = selectedCategory
            visibleItemLimit = 2
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: PlanteriorControl.minimumTarget)
        .background(
            category == selectedCategory
                ? PlanteriorPalette.accent.color
                : PlanteriorPalette.subtle.color
        )
        .foregroundStyle(
            category == selectedCategory
                ? PlanteriorPalette.textOnAccent.color
                : PlanteriorPalette.textPrimary.color
        )
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .accessibilityIdentifier(
            "storage.category." +
                (selectedCategory?.rawValue.lowercased() ?? "all")
        )
        .accessibilityAddTraits(
            category == selectedCategory ? .isSelected : []
        )
    }

    private func modeButton(
        _ title: String,
        mode selectedMode: InventoryMode
    ) -> some View {
        Button(title) {
            mode = selectedMode
            visibleItemLimit = 2
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: PlanteriorControl.minimumTarget)
        .background(
            mode == selectedMode
                ? PlanteriorPalette.accent.color
                : PlanteriorPalette.subtle.color
        )
        .foregroundStyle(
            mode == selectedMode
                ? PlanteriorPalette.textOnAccent.color
                : PlanteriorPalette.textPrimary.color
        )
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .accessibilityIdentifier(
            selectedMode == .warehouse
                ? "storage.mode.warehouse"
                : "storage.mode.shop"
        )
        .accessibilityAddTraits(
            mode == selectedMode ? .isSelected : []
        )
    }
}
