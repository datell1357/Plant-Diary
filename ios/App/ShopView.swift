import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct ShopView: View {
    @Environment(\.sizeCategory) private var sizeCategory
    let entries: [InventoryCatalogEntry]
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
                    spacing: 12
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
            repeating: GridItem(.fixed(173), spacing: 12),
            count: 2
        )
    }

    private func shopCard(_ entry: InventoryCatalogEntry) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Button {
                showDetail(entry.item)
            } label: {
                VStack(alignment: .leading, spacing: 6) {
                    Image(StorageItemPresentation.asset(for: entry.item))
                        .resizable()
                        .scaledToFill()
                        .frame(width: 153, height: 110)
                        .clipped()
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
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("shop.row.\(entry.item.id.rawValue)")
            .accessibilityLabel(entry.item.name)
            .accessibilityValue(
                "\(StorageItemPresentation.categoryName(entry.item.category)), " +
                    eligibilityText(entry)
            )

            acquireStatus(entry)
        }
        .padding(9)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                .stroke(
                    PlanteriorPalette.border.color,
                    lineWidth: PlanteriorControl.hairline
                )
        }
        .frame(maxWidth: sizeCategory.isAccessibilityCategory ? .infinity : 173)
        .frame(height: 180)
        .overlay(alignment: .topLeading) {
            promotionalBadge(entry)
                .padding(.leading, PlanteriorSpacing.large)
                .padding(.top, 96)
        }
    }

    private func acquireStatus(
        _ entry: InventoryCatalogEntry
    ) -> some View {
        Button {
            acquire(entry.item)
        } label: {
            Text(eligibilityText(entry))
                .font(PlanteriorTypography.microLabel)
                .lineLimit(1)
                .foregroundStyle(statusForeground(entry))
                .padding(.horizontal, PlanteriorSpacing.small)
                .frame(height: 20)
                .background(statusBackground(entry))
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .frame(
            minWidth: PlanteriorControl.minimumTarget,
            minHeight: PlanteriorControl.minimumTarget
        )
        .contentShape(Rectangle())
        .padding(.vertical, -12)
        .disabled(entry.eligibility != .eligible)
        .accessibilityIdentifier(
            "shop.acquire.\(entry.item.id.rawValue)"
        )
        .accessibilityLabel("\(entry.item.name) 획득")
        .accessibilityValue(eligibilityText(entry))
    }

    @ViewBuilder
    private func promotionalBadge(
        _ entry: InventoryCatalogEntry
    ) -> some View {
        let badge = StorageItemPresentation.shopBadge(for: entry.item)
        if !badge.isEmpty {
            Text(badge)
                .font(PlanteriorTypography.microLabel)
                .lineLimit(1)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .padding(.horizontal, PlanteriorSpacing.small)
                .frame(height: 20)
                .background(PlanteriorPalette.subtle.color)
                .clipShape(Capsule())
                .allowsHitTesting(false)
                .accessibilityLabel("\(entry.item.name) 프로모션, \(badge)")
                .accessibilityIdentifier(
                    "shop.promo.\(entry.item.id.rawValue)"
                )
        }
    }

    private func eligibilityText(_ entry: InventoryCatalogEntry) -> String {
        StorageItemPresentation.eligibilityText(entry.eligibility)
    }

    private func statusForeground(_ entry: InventoryCatalogEntry) -> Color {
        switch entry.eligibility {
        case .alreadyOwned:
            PlanteriorPalette.accent.color
        case .eligible:
            PlanteriorPalette.textOnAccent.color
        case .conditionNotMet:
            PlanteriorPalette.warning.color
        }
    }

    private func statusBackground(_ entry: InventoryCatalogEntry) -> Color {
        switch entry.eligibility {
        case .alreadyOwned:
            PlanteriorPalette.successSurface.color
        case .eligible:
            PlanteriorPalette.accent.color
        case .conditionNotMet:
            PlanteriorPalette.warningSurface.color
        }
    }
}
