import PlanteriorDesignSystem
import SwiftUI

extension InventoryItemDetailView {
    var preview: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            Text("미니홈피 적용 예시")
                .font(PlanteriorTypography.sectionTitle)
            HStack(spacing: PlanteriorSpacing.medium) {
                Image(.storageContext)
                    .resizable()
                    .scaledToFill()
                    .frame(
                        width: PlanteriorLayout.mediaThumbnailSize,
                        height: PlanteriorLayout.mediaThumbnailSize
                    )
                    .clipShape(
                        RoundedRectangle(cornerRadius: PlanteriorRadius.small)
                    )
                    .accessibilityHidden(true)
                VStack(
                    alignment: .leading,
                    spacing: InventoryReferenceMetrics.detailPreviewSpacing
                ) {
                    Text(StorageItemPresentation.contextTitle(for: item))
                        .font(PlanteriorTypography.cardTitle)
                        .accessibilityIdentifier("storage.detail.context.title")
                    Text(StorageItemPresentation.contextDescription(for: item))
                        .font(PlanteriorTypography.microLabel)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .lineLimit(2)
                }
            }
            .padding(.horizontal, PlanteriorSpacing.medium)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(height: InventoryReferenceMetrics.detailPreviewHeight)
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
    }

    var statusText: String {
        if isApplied {
            return "적용 중"
        }
        if isOwned {
            return "보관 중"
        }
        return StorageItemPresentation.eligibilityText(eligibility)
    }

    var actionTitle: String {
        if isApplied {
            return "미니홈에서 제거하기"
        }
        if isOwned {
            return "미니홈피에 적용하기"
        }
        if eligibility == .eligible {
            return "창고에 추가하기"
        }
        return "획득 조건 확인 필요"
    }

    var actionIdentifier: String {
        let action = isOwned ? (isApplied ? "remove" : "apply") : "acquire"
        return "storage.detail.\(action).\(item.id.rawValue)"
    }
}
