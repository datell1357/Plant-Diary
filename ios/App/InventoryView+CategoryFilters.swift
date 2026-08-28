import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension InventoryView {
    @ViewBuilder
    var categoryFilters: some View {
        if effectiveSizeCategory.isAccessibilityCategory {
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
                if mode == .shop {
                    seasonalButton.frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, PlanteriorSpacing.extraLarge)
            .padding(.vertical, PlanteriorSpacing.small)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: PlanteriorSpacing.small) {
                    categoryButton("전체", category: nil, identifier: "all")
                    categoryButton("배경", category: .background, identifier: "background")
                    categoryButton("가구", category: .furniture, identifier: "furniture")
                    categoryButton("장식", category: .decoration, identifier: "decoration")
                    if mode == .shop {
                        seasonalButton
                    }
                }
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
            }
            .frame(height: InventoryReferenceMetrics.filterTrackHeight)
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
