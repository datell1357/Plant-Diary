import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct InventoryItemDetailView: View {
    let item: ShopItem
    let eligibility: InventoryAcquisitionEligibility
    let isOwned: Bool
    let isApplied: Bool
    let message: String?
    let acquire: () -> Void
    let togglePlacement: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
                hero
                titleBlock
                statusCard
                primaryAction
                preview
            }
            .padding(.horizontal, PlanteriorSpacing.large)
            .padding(.vertical, PlanteriorSpacing.small)
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("아이템 상세")
        .navigationBarTitleDisplayMode(.inline)
        .planteriorInlineNavigationChrome()
        .toolbar(.visible, for: .navigationBar)
        .accessibilityIdentifier("storage.detail.\(item.id.rawValue)")
        .safeAreaInset(edge: .bottom) {
            if let message {
                Text(message)
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .padding(PlanteriorSpacing.medium)
                    .frame(maxWidth: .infinity)
                    .background(PlanteriorPalette.subtle.color)
                    .accessibilityIdentifier("storage.message")
            }
        }
    }

    private var hero: some View {
        ZStack(alignment: .topLeading) {
            Image(StorageItemPresentation.heroAsset(for: item))
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .aspectRatio(362 / 220, contentMode: .fit)
                .background(PlanteriorPalette.subtle.color)
                .clipShape(
                    RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge)
                )
                .accessibilityLabel("\(item.name) 대표 이미지")
                .accessibilityIdentifier(
                    "storage.detail.hero.\(item.id.rawValue)"
                )
            PlanteriorStatusPill(
                LocalizedStringKey(
                    StorageItemPresentation.categoryName(item.category)
                ),
                variant: .accent
            )
            .padding(PlanteriorSpacing.medium)
        }
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            Text(item.name)
                .font(PlanteriorTypography.pageTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("storage.detail.title")
            Text(StorageItemPresentation.description(for: item))
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
            Text(StorageItemPresentation.conditionDescription(for: item))
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("storage.detail.condition")
                .accessibilityValue(item.acquisitionCondition ?? "none")
        }
    }

    private var statusCard: some View {
        PlanteriorCard(variant: isApplied ? .success : .standard) {
            HStack(alignment: .firstTextBaseline, spacing: PlanteriorSpacing.small) {
                Image(systemName: statusIcon)
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .accessibilityHidden(true)
                Text("현재 적용 상태")
                    .font(PlanteriorTypography.cardTitle)
                Spacer(minLength: PlanteriorSpacing.small)
                Text(statusText)
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .multilineTextAlignment(.trailing)
                    .accessibilityIdentifier("storage.detail.status")
            }
        }
    }

    private var primaryAction: some View {
        PlanteriorPrimaryButton(LocalizedStringKey(actionTitle)) {
            if isOwned {
                togglePlacement()
            } else {
                acquire()
            }
        }
        .disabled(!isOwned && eligibility != .eligible)
        .opacity(!isOwned && eligibility != .eligible ? 0.55 : 1)
        .accessibilityIdentifier(actionIdentifier)
        .accessibilityValue(statusText)
    }

    private var preview: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
            Text("미니홈 적용 예시")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                HStack(alignment: .center, spacing: PlanteriorSpacing.medium) {
                    Image(.storageContext)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 48, height: 48)
                        .clipShape(
                            RoundedRectangle(cornerRadius: PlanteriorRadius.small)
                        )
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                        Text("내 방 벽면에 쉽게")
                            .font(PlanteriorTypography.cardTitle)
                        Text("미니홈 편집에서 위치를 조정할 수 있어요.")
                            .font(PlanteriorTypography.caption)
                            .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    }
                }
            }
        }
    }

    private var statusText: String {
        if isApplied {
            return "적용 중"
        }
        if isOwned {
            return "보관 중"
        }
        return StorageItemPresentation.eligibilityText(eligibility)
    }

    private var statusIcon: String {
        isOwned ? "checkmark.circle.fill" : "lock.circle"
    }

    private var actionTitle: String {
        if isApplied {
            return "미니홈에서 제거하기"
        }
        if isOwned {
            return "미니홈에 적용하기"
        }
        if eligibility == .eligible {
            return "창고에 추가하기"
        }
        return "획득 조건 확인 필요"
    }

    private var actionIdentifier: String {
        let action = isOwned ? (isApplied ? "remove" : "apply") : "acquire"
        return "storage.detail.\(action).\(item.id.rawValue)"
    }
}
