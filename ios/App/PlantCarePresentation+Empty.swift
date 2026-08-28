import PlanteriorDesignSystem
import SwiftUI

extension PlantCollectionView {
    var trueEmptyState: some View {
        VStack(spacing: PlanteriorSpacing.none) {
            Image(.collectionEmptyAvatar)
                .resizable()
                .scaledToFit()
                .frame(
                    width: sizeCategory.isAccessibilityCategory
                        ? CollectionReferenceMetrics.emptyIllustrationAccessibilitySide
                        : CollectionReferenceMetrics.emptyIllustrationDefaultSide,
                    height: sizeCategory.isAccessibilityCategory
                        ? CollectionReferenceMetrics.emptyIllustrationAccessibilitySide
                        : CollectionReferenceMetrics.emptyIllustrationDefaultSide
                )
                .padding(
                    sizeCategory.isAccessibilityCategory
                        ? PlanteriorSpacing.none
                        : CollectionReferenceMetrics.emptyIllustrationInset
                )
                .background(PlanteriorPalette.subtle.color)
                .clipShape(Circle())
                .accessibilityLabel("빈 화분 캐릭터")
                .accessibilityIdentifier("collection.empty.illustration")
            Text("아직 등록된 식물이 없어요 🥺")
                .font(PlanteriorTypography.sectionTitle)
                .frame(
                    minHeight: CollectionReferenceMetrics.emptyTitleMinimumHeight
                )
                .padding(
                    .top,
                    sizeCategory.isAccessibilityCategory
                        ? PlanteriorSpacing.small
                        : CollectionReferenceMetrics.emptyTitleTopInset
                )
                .accessibilityIdentifier("collection.empty.title")
            Text("첫 번째 반려식물을 등록하고 성장기를 남겨보세요!")
                .font(PlanteriorTypography.supporting)
                .accessibilityLabel("첫 번째 반려식물을 등록하고 성장기를 남겨보세요!")
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .multilineTextAlignment(.center)
                .frame(
                    minHeight: CollectionReferenceMetrics.emptyBodyMinimumHeight
                )
                .padding(.top, CollectionReferenceMetrics.emptyBodyTopInset)
                .offset(y: CollectionReferenceMetrics.emptyBodyOpticalOffset)
                .accessibilityIdentifier("collection.empty.body")
            Button(action: openCamera) {
                Label("사진으로 식별하기", systemImage: "camera")
                    .font(PlanteriorTypography.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: CollectionReferenceMetrics.emptyPrimaryHeight)
                    .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                    .background(PlanteriorPalette.accent.color)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
            }
            .buttonStyle(.plain)
            .padding(.top, CollectionReferenceMetrics.emptyPrimaryTopInset)
            .accessibilityIdentifier("collection.empty.camera")
            NavigationLink {
                PlantRegistrationView()
            } label: {
                Label("직접 등록하기", systemImage: "square.and.pencil")
                    .font(PlanteriorTypography.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .frame(height: PlanteriorControl.minimumTarget)
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .background(PlanteriorPalette.surface.color)
                    .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                    .overlay {
                        RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                            .stroke(PlanteriorPalette.accent.color, lineWidth: 1)
                    }
            }
            .padding(.top, PlanteriorSpacing.medium)
            .accessibilityIdentifier("collection.empty.manual")
        }
        .padding(.horizontal, PlanteriorSpacing.small)
        .padding(
            .top,
            sizeCategory.isAccessibilityCategory
                ? 0
                : PlanteriorSpacing.board
        )
    }

    @ViewBuilder
    func careStatusPill(
        _ status: PlantCareStatus,
        index: Int
    ) -> some View {
        if status.needsAttention {
            let style = PlanteriorStatusVariant.attention
            Text(status.title)
                .font(PlanteriorTypography.microLabel)
                .foregroundStyle(style.foreground.color)
                .padding(.horizontal, PlanteriorSpacing.medium)
                .padding(.vertical, PlanteriorSpacing.extraSmall)
                .background(style.background.color)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.small))
                .accessibilityIdentifier("collection.status.\(index)")
                .accessibilityValue("주의")
        } else {
            PlanteriorStatusPill(
                LocalizedStringKey(status.title),
                variant: status.variant
            )
            .accessibilityIdentifier("collection.status.\(index)")
        }
    }
}
