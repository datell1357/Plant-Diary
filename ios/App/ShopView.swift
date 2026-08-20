import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct ShopView: View {
    @Environment(\.sizeCategory) private var sizeCategory
    let entries: [InventoryCatalogEntry]
    let hasMore: Bool
    let loadMore: () -> Void
    let acquire: (ShopItem) -> Void
    let showDetail: (ShopItem) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
            if !entries.isEmpty {
                Text("공개 아이템 \(entries.count)개")
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("shop.ready")
            }
            if entries.isEmpty {
                Text("조건에 맞는 공개 아이템이 없어요.")
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("shop.empty")
            } else {
                LazyVGrid(columns: columns, spacing: PlanteriorSpacing.medium) {
                    ForEach(entries, id: \.item.id) { entry in
                        shopCard(entry)
                    }
                }
                if hasMore {
                    PlanteriorSecondaryButton("더 보기", action: loadMore)
                        .accessibilityIdentifier("shop.load-more")
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("shop.screen")
    }

    private var columns: [GridItem] {
        let count = sizeCategory.isAccessibilityCategory ? 1 : 2
        return Array(
            repeating: GridItem(.flexible(), spacing: PlanteriorSpacing.medium),
            count: count
        )
    }

    private func shopCard(_ entry: InventoryCatalogEntry) -> some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            shopItemButton(entry)
            acquireButton(entry)
        }
        .padding(PlanteriorSpacing.small)
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                .stroke(
                    PlanteriorPalette.border.color,
                    lineWidth: PlanteriorControl.hairline
                )
        }
    }

    private func shopItemButton(
        _ entry: InventoryCatalogEntry
    ) -> some View {
        Button {
            showDetail(entry.item)
        } label: {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                Image(StorageItemPresentation.asset(for: entry.item))
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity)
                    .aspectRatio(4 / 3, contentMode: .fit)
                    .background(PlanteriorPalette.subtle.color)
                    .clipShape(
                        RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                    )
                    .accessibilityLabel("\(entry.item.name) 이미지")
                    .accessibilityIdentifier(
                        "shop.image.\(entry.item.id.rawValue)"
                    )
                Text(entry.item.name)
                    .font(PlanteriorTypography.cardTitle)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                PlanteriorStatusPill(
                    LocalizedStringKey(statusText(entry)),
                    variant: statusVariant(entry.eligibility)
                )
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("shop.row.\(entry.item.id.rawValue)")
        .accessibilityLabel(entry.item.name)
        .accessibilityValue(
            "\(StorageItemPresentation.categoryName(entry.item.category)), " +
                statusText(entry)
        )
    }

    private func acquireButton(
        _ entry: InventoryCatalogEntry
    ) -> some View {
        Button("획득") { acquire(entry.item) }
            .buttonStyle(.borderless)
            .font(PlanteriorTypography.caption.weight(.semibold))
            .frame(maxWidth: .infinity)
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .foregroundStyle(PlanteriorPalette.accent.color)
            .background(PlanteriorPalette.accentSurface.color)
            .clipShape(
                RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
            )
            .disabled(entry.eligibility != .eligible)
            .opacity(entry.eligibility == .eligible ? 1 : 0.55)
            .accessibilityIdentifier(
                "shop.acquire.\(entry.item.id.rawValue)"
            )
            .accessibilityLabel("\(entry.item.name) 획득")
            .accessibilityValue(statusText(entry))
    }

    private func statusText(_ entry: InventoryCatalogEntry) -> String {
        StorageItemPresentation.eligibilityText(entry.eligibility)
    }

    private func statusVariant(
        _ eligibility: InventoryAcquisitionEligibility
    ) -> PlanteriorStatusVariant {
        switch eligibility {
        case .eligible: .tonal
        case .conditionNotMet: .warning
        case .alreadyOwned: .neutral
        }
    }
}
