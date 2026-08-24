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
            Button {
                mode = mode == .warehouse ? .shop : .warehouse
                category = nil
                seasonalOnly = false
                visibleItemLimit = Self.initialVisibleItemLimit
            } label: {
                Image(systemName: mode == .warehouse ? "archivebox" : "shippingbox")
                    .font(PlanteriorTypography.supporting.weight(.semibold))
                    .frame(width: 32, height: 32)
                    .background(PlanteriorPalette.surface.color)
                    .clipShape(Circle())
                    .overlay {
                        Circle().stroke(
                            PlanteriorPalette.border.color,
                            lineWidth: PlanteriorControl.hairline
                        )
                    }
            }
            .buttonStyle(.plain)
            .frame(
                width: PlanteriorControl.minimumTarget,
                height: PlanteriorControl.minimumTarget
            )
            .foregroundStyle(PlanteriorPalette.textPrimary.color)
            .accessibilityLabel(mode == .warehouse ? "아이템 상점 열기" : "나의 창고 열기")
            .accessibilityIdentifier(
                mode == .warehouse
                    ? "storage.mode.shop"
                    : "storage.mode.warehouse"
            )
        }
        .padding(.horizontal, PlanteriorSpacing.extraLarge)
        .frame(height: 44)
    }

    var shopCredit: some View {
        HStack(spacing: PlanteriorSpacing.extraSmall) {
            Text("보유 크레딧")
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityIdentifier("shop.credit.label")
            storageCreditImage
                .resizable()
                .scaledToFit()
                .frame(width: 20, height: 20)
                .accessibilityLabel("크레딧")
                .accessibilityIdentifier("shop.credit.icon")
            Text("1,250")
                .foregroundStyle(PlanteriorPalette.warning.color)
                .accessibilityIdentifier("shop.credit.amount")
        }
        .font(PlanteriorTypography.supporting.weight(.semibold))
        .frame(width: 179, height: 38)
        .background(PlanteriorPalette.warningSurface.color)
        .clipShape(Capsule())
        .overlay {
            Capsule().stroke(
                PlanteriorPalette.border.color,
                lineWidth: PlanteriorControl.hairline
            )
        }
        .padding(.top, 7)
    }

    private var storageCreditImage: Image {
        if let image = UIImage(named: "FigmaStorageCredit") {
            return Image(uiImage: image)
        }
        return Image(systemName: "circle")
    }

    var categoryFilters: some View {
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
        .frame(height: 53)
    }

    var storageColumns: [GridItem] {
        if effectiveSizeCategory.isAccessibilityCategory {
            return [GridItem(.flexible())]
        }
        return Array(
            repeating: GridItem(.fixed(110), spacing: 10),
            count: 3
        )
    }

    private var seasonalButton: some View {
        filterButton(
            "시즌 한정",
            selected: seasonalOnly,
            width: 72,
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
            width: 56,
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
                .foregroundStyle(
                    selected
                        ? PlanteriorPalette.textOnAccent.color
                        : PlanteriorPalette.textSecondary.color
                )
                .frame(width: width, height: 31)
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
        .frame(width: width, height: PlanteriorControl.minimumTarget)
        .accessibilityIdentifier("storage.category.\(identifier)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
