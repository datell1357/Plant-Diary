import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension ShopView {
    func shopCard(_ entry: InventoryCatalogEntry) -> some View {
        let imageSize = InventoryReferenceMetrics.shopCardImageSize
        let titleSpacing = InventoryReferenceMetrics.shopCardImageToTitleSpacing
        let cardWidth = sizeCategory.isAccessibilityCategory
            ? CGFloat.infinity
            : InventoryReferenceMetrics.shopCardWidth
        return VStack(alignment: .leading, spacing: 3) {
            Button {
                showDetail(entry.item)
            } label: {
                VStack(alignment: .leading, spacing: titleSpacing) {
                    Image(StorageItemPresentation.asset(for: entry.item))
                        .resizable()
                        .scaledToFill()
                        .frame(width: imageSize.width, height: imageSize.height)
                        .clipped()
                        .background(PlanteriorPalette.subtle.color)
                        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                        .accessibilityLabel("\(entry.item.name) 이미지")
                        .accessibilityIdentifier("shop.image.\(entry.item.id.rawValue)")
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

            cardMetadata(entry)
        }
        .padding(InventoryReferenceMetrics.shopCardInset)
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
        .frame(maxWidth: cardWidth)
        .frame(height: InventoryReferenceMetrics.shopCardHeight)
    }

    @ViewBuilder
    func cardMetadata(_ entry: InventoryCatalogEntry) -> some View {
        if StorageItemPresentation.shopBadge(for: entry.item).isEmpty {
            acquireStatus(entry)
        } else {
            promotionalBadge(entry)
        }
    }

    func acquireStatus(
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

    func promotionalBadge(
        _ entry: InventoryCatalogEntry
    ) -> some View {
        let badge = StorageItemPresentation.shopBadge(for: entry.item)
        return Button {
            acquire(entry.item)
        } label: {
            HStack(spacing: PlanteriorSpacing.extraSmall) {
                shopBadgeImage(for: entry.item)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 14, height: 14)
                    .accessibilityHidden(true)
                Text(badge)
                    .font(PlanteriorTypography.microLabel)
                    .lineLimit(1)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityLabel("\(entry.item.name) 프로모션, \(badge)")
                    .accessibilityIdentifier(
                        "shop.promo.\(entry.item.id.rawValue)"
                    )
            }
            .padding(.horizontal, PlanteriorSpacing.small)
            .frame(height: 20)
            .background(PlanteriorPalette.subtle.color)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .frame(
            minWidth: PlanteriorControl.minimumTarget,
            minHeight: PlanteriorControl.minimumTarget,
            alignment: .leading
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

    func shopBadgeImage(for item: ShopItem) -> Image {
        let name = StorageItemPresentation.shopBadgeAssetName(for: item)
        if let image = UIImage(named: name) {
            return Image(uiImage: image)
        }
        return Image(systemName: "circle")
    }

    func eligibilityText(_ entry: InventoryCatalogEntry) -> String {
        StorageItemPresentation.eligibilityText(entry.eligibility)
    }

    func statusForeground(_ entry: InventoryCatalogEntry) -> Color {
        switch entry.eligibility {
        case .alreadyOwned:
            PlanteriorPalette.accent.color
        case .eligible:
            PlanteriorPalette.textOnAccent.color
        case .conditionNotMet:
            PlanteriorPalette.warning.color
        }
    }

    func statusBackground(_ entry: InventoryCatalogEntry) -> Color {
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
