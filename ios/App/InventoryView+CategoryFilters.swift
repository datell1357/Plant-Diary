import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension InventoryView {
    var storageColumns: [GridItem] {
        if effectiveSizeCategory.isAccessibilityCategory {
            return [GridItem(.flexible())]
        }
        return Array(
            repeating: GridItem(
                .fixed(InventoryReferenceMetrics.gridCardWidth),
                spacing: InventoryReferenceMetrics.gridSpacing
            ),
            count: 3
        )
    }

    var seasonalButton: some View {
        filterButton(
            "시즌 한정",
            selected: seasonalOnly,
            width: InventoryReferenceMetrics.seasonalFilterWidth,
            identifier: "seasonal"
        ) {
            category = nil
            seasonalOnly.toggle()
            visibleItemLimit = Self.initialVisibleItemLimit
        }
    }

    var allWarehouseCategoryButton: some View {
        filterButton(
            "전체",
            selected: warehouseCategory == nil,
            width: InventoryReferenceMetrics.standardFilterWidth,
            fillsAvailableWidth: true,
            identifier: "all"
        ) {
            warehouseCategory = nil
            visibleItemLimit = Self.initialVisibleItemLimit
        }
    }

    func warehouseCategoryButton(
        _ filter: InventoryRoomFilter
    ) -> some View {
        filterButton(
            filter.title,
            selected: warehouseCategory == filter,
            width: InventoryReferenceMetrics.standardFilterWidth,
            fillsAvailableWidth: true,
            identifier: filter.rawValue
        ) {
            warehouseCategory = warehouseCategory == filter ? nil : filter
            visibleItemLimit = Self.initialVisibleItemLimit
        }
    }

    func categoryButton(
        _ title: String,
        category selectedCategory: ItemCategory?,
        identifier: String
    ) -> some View {
        filterButton(
            title,
            selected: !seasonalOnly && category == selectedCategory,
            width: InventoryReferenceMetrics.standardFilterWidth,
            identifier: identifier
        ) {
            category = selectedCategory
            seasonalOnly = false
            visibleItemLimit = Self.initialVisibleItemLimit
        }
    }

    func filterButton(
        _ title: String,
        selected: Bool,
        width: CGFloat,
        fillsAvailableWidth: Bool = false,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(title)
                .font(PlanteriorTypography.caption.weight(.semibold))
                .lineLimit(1)
                .fixedSize(
                    horizontal: effectiveSizeCategory.isAccessibilityCategory,
                    vertical: false
                )
                .foregroundStyle(
                    selected
                        ? PlanteriorPalette.textOnAccent.color
                        : PlanteriorPalette.textSecondary.color
                )
                .frame(
                    minWidth: effectiveSizeCategory.isAccessibilityCategory
                        ? InventoryReferenceMetrics.accessibilityFilterMinimumWidth
                        : width,
                    maxWidth: fillsAvailableWidth ? .infinity : nil,
                    minHeight: InventoryReferenceMetrics.filterHeight
                )
                .background(
                    selected
                        ? PlanteriorPalette.accent.color
                        : PlanteriorPalette.surface.color
                )
                .clipShape(Capsule())
                .overlay {
                    if !selected {
                        Capsule().stroke(
                            PlanteriorPalette.border.color,
                            lineWidth: PlanteriorControl.hairline
                        )
                    }
                }
        }
        .buttonStyle(.plain)
        .frame(
            minWidth: effectiveSizeCategory.isAccessibilityCategory
                ? InventoryReferenceMetrics.accessibilityFilterMinimumWidth
                : width,
            minHeight: PlanteriorControl.minimumTarget
        )
        .accessibilityIdentifier("storage.category.\(identifier)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
