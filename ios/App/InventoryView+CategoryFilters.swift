import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

enum InventoryRoomFilter: String, CaseIterable {
    case wall
    case floor
    case furniture
    case decoration

    var title: String {
        switch self {
        case .wall: "벽지"
        case .floor: "바닥"
        case .furniture: "가구"
        case .decoration: "장식"
        }
    }

    func includes(_ item: ShopItem) -> Bool {
        self == Self.category(for: item)
    }

    private static func category(for item: ShopItem) -> Self {
        let itemID = item.id.rawValue
        if wallItemIDs.contains(itemID) {
            return .wall
        }
        if floorItemIDs.contains(itemID) {
            return .floor
        }
        switch item.category {
        case .background:
            return .wall
        case .furniture:
            return .furniture
        case .decoration:
            return .decoration
        }
    }

    private static let wallItemIDs = Set([
        "item-green-wall", "item-window-frame", "item-wall-art",
        "item-autumn-frame"
    ])
    private static let floorItemIDs = Set([
        "item-cozy-rug", "item-small-rug", "item-round-mat"
    ])
}

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
        } else {
            if mode == .warehouse {
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

    private var seasonalButton: some View {
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

    private var allWarehouseCategoryButton: some View {
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

    private func warehouseCategoryButton(
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

    private func categoryButton(
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

    private func filterButton(
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
