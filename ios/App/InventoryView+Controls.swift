import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension InventoryView {
    var storageHeader: some View {
        HStack(alignment: .center, spacing: PlanteriorSpacing.small) {
            Text(mode == .warehouse ? "나의 창고" : "아이템 상점")
                .font(PlanteriorTypography.pageTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier(
                    mode == .warehouse ? "storage.title" : "shop.title"
                )
            Spacer(minLength: PlanteriorSpacing.small)
            if mode == .shop {
                Button {
                    sortDescending.toggle()
                    visibleItemLimit = 2
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                        .frame(
                            width: PlanteriorControl.minimumTarget,
                            height: PlanteriorControl.minimumTarget
                        )
                        .background(PlanteriorPalette.surface.color)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityLabel(
                    sortDescending ? "이름 오름차순" : "이름 내림차순"
                )
                .accessibilityIdentifier("shop.sort")
            }
        }
    }

    var categoryFilters: some View {
        LazyVGrid(columns: categoryColumns, spacing: PlanteriorSpacing.small) {
            categoryButton("전체", category: nil)
            categoryButton("배경", category: .background)
            categoryButton("가구", category: .furniture)
            categoryButton("소품", category: .decoration)
        }
    }

    var storageColumns: [GridItem] {
        let count = effectiveSizeCategory.isAccessibilityCategory ? 1 : 3
        return Array(
            repeating: GridItem(.flexible(), spacing: PlanteriorSpacing.medium),
            count: count
        )
    }

    private var categoryColumns: [GridItem] {
        let count = effectiveSizeCategory.isAccessibilityCategory ? 2 : 4
        return Array(
            repeating: GridItem(.flexible(), spacing: PlanteriorSpacing.small),
            count: count
        )
    }

    var modeSelector: some View {
        HStack(spacing: PlanteriorSpacing.small) {
            modeButton("창고", mode: .warehouse)
            modeButton("상점", mode: .shop)
        }
        .padding(PlanteriorSpacing.extraSmall)
        .background(PlanteriorPalette.subtle.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
    }

    private func categoryButton(
        _ title: String,
        category selectedCategory: ItemCategory?
    ) -> some View {
        Button(title) {
            category = selectedCategory
            visibleItemLimit = 2
        }
        .buttonStyle(StorageChipStyle(selected: category == selectedCategory))
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
        .buttonStyle(StorageChipStyle(selected: mode == selectedMode))
        .accessibilityIdentifier(
            selectedMode == .warehouse
                ? "storage.mode.warehouse"
                : "storage.mode.shop"
        )
        .accessibilityAddTraits(mode == selectedMode ? .isSelected : [])
    }
}

private struct StorageChipStyle: ButtonStyle {
    let selected: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PlanteriorTypography.caption.weight(.semibold))
            .frame(maxWidth: .infinity)
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .foregroundStyle(
                selected
                    ? PlanteriorPalette.textOnAccent.color
                    : PlanteriorPalette.textSecondary.color
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
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}
