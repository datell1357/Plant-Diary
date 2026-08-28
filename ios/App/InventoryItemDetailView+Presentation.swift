import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension InventoryItemDetailView {
    var topBar: some View {
        HStack(spacing: 0) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(InventoryReferenceMetrics.detailBackGlyph)
                    .frame(
                        width: PlanteriorControl.minimumTarget,
                        height: PlanteriorControl.minimumTarget
                    )
            }
            .buttonStyle(.plain)
            .frame(
                width: PlanteriorControl.minimumTarget,
                height: PlanteriorControl.minimumTarget
            )
            .contentShape(Rectangle())
            .foregroundStyle(PlanteriorPalette.textPrimary.color)
            .accessibilityLabel("뒤로")
            .accessibilityIdentifier("storage.detail.back")

            Text("아이템 상세")
                .font(PlanteriorTypography.pageTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .lineLimit(1)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("storage.detail.chrome.title")

            Spacer(minLength: PlanteriorSpacing.small)

            Button {
                isFavorite.toggle()
            } label: {
                Image(systemName: isFavorite ? "star.fill" : "star")
                    .font(InventoryReferenceMetrics.detailFavoriteGlyph)
                    .frame(
                        width: InventoryReferenceMetrics.detailFavoriteSide,
                        height: InventoryReferenceMetrics.detailFavoriteSide
                    )
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
            .contentShape(Rectangle())
            .foregroundStyle(PlanteriorPalette.textPrimary.color)
            .accessibilityLabel(isFavorite ? "즐겨찾기 해제" : "즐겨찾기")
            .accessibilityIdentifier(
                "storage.detail.favorite.\(item.id.rawValue)"
            )
        }
        .padding(.horizontal, PlanteriorLayout.contentGutter)
        .frame(height: PlanteriorLayout.topBarHeight)
        .background(PlanteriorPalette.canvas.color)
    }

    var hero: some View {
        ZStack(alignment: .topLeading) {
            Image(StorageItemPresentation.heroAsset(for: item))
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .frame(height: InventoryReferenceMetrics.detailHeroHeight)
                .clipped()
                .background(PlanteriorPalette.subtle.color)
                .clipShape(
                    RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge)
                )
                .accessibilityLabel("\(item.name) 대표 이미지")
                .accessibilityIdentifier(
                    "storage.detail.hero.\(item.id.rawValue)"
                )
            Text(StorageItemPresentation.detailCategoryName(item.category))
                .font(PlanteriorTypography.microLabel)
                .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                .padding(.horizontal, PlanteriorSpacing.medium)
                .frame(height: InventoryReferenceMetrics.detailCategoryHeight)
                .background(
                    PlanteriorPalette.mediaScrim.color.opacity(
                        PlanteriorOpacity.mediaBadge
                    )
                )
                .clipShape(Capsule())
                .padding(.horizontal, PlanteriorSpacing.medium)
                .padding(.top, PlanteriorSpacing.large)
                .accessibilityIdentifier("storage.detail.category")
        }
    }

    var titleBlock: some View {
        VStack(
            alignment: .leading,
            spacing: InventoryReferenceMetrics.detailTitleSpacing
        ) {
            Text(item.name)
                .font(InventoryReferenceMetrics.detailTitleFont)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("storage.detail.title")
            Text(StorageItemPresentation.description(for: item))
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .lineSpacing(InventoryReferenceMetrics.detailBodyLineSpacing)
            if item.acquisitionCondition != nil {
                Text(StorageItemPresentation.conditionDescription(for: item))
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("storage.detail.condition")
                    .accessibilityValue(item.acquisitionCondition ?? "none")
            }
        }
    }

    var statusCard: some View {
        HStack(spacing: PlanteriorSpacing.small) {
            Image(systemName: isOwned ? "checkmark.circle" : "lock.circle")
                .font(PlanteriorTypography.supporting.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text("현재 적용 상태")
                .font(PlanteriorTypography.cardTitle)
            Spacer(minLength: PlanteriorSpacing.small)
            Text(statusText)
                .font(PlanteriorTypography.supporting.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityIdentifier("storage.detail.status")
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .frame(maxWidth: .infinity)
        .frame(height: InventoryReferenceMetrics.detailStatusHeight)
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

    var primaryAction: some View {
        Button {
            if isOwned {
                togglePlacement()
            } else {
                acquire()
            }
        } label: {
            Text(actionTitle)
                .font(PlanteriorTypography.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .frame(height: InventoryReferenceMetrics.detailActionHeight)
        }
        .buttonStyle(.plain)
        .foregroundStyle(PlanteriorPalette.textOnAccent.color)
        .background(PlanteriorPalette.accent.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
        .disabled(!isOwned && eligibility != .eligible)
        .opacity(!isOwned && eligibility != .eligible ? 0.55 : 1)
        .accessibilityIdentifier(actionIdentifier)
        .accessibilityValue(statusText)
    }
}
