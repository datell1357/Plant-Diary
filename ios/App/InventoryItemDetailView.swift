import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct InventoryItemDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var isFavorite = false
    let item: ShopItem
    let eligibility: InventoryAcquisitionEligibility
    let isOwned: Bool
    let isApplied: Bool
    let message: String?
    let acquire: () -> Void
    let togglePlacement: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ScrollView {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
                    hero
                    titleBlock
                        .padding(.bottom, 14)
                    statusCard
                        .padding(.bottom, 3)
                    primaryAction
                    preview
                }
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
            }
            .accessibilityIdentifier("storage.detail.\(item.id.rawValue)")
        }
        .padding(.top, PlanteriorControl.minimumTarget)
        .ignoresSafeArea(edges: .top)
        .background(PlanteriorPalette.canvas.color)
        .padding(.bottom, PlanteriorLayout.tabBarHeight)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
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

    private var topBar: some View {
        HStack(spacing: 0) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
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
                    .font(.system(size: 17, weight: .semibold))
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

    private var hero: some View {
        ZStack(alignment: .topLeading) {
            Image(StorageItemPresentation.heroAsset(for: item))
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .frame(height: 220)
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
                .frame(height: 22)
                .background(Color.black.opacity(0.72))
                .clipShape(Capsule())
                .padding(.horizontal, PlanteriorSpacing.medium)
                .padding(.top, PlanteriorSpacing.large)
                .accessibilityIdentifier("storage.detail.category")
        }
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(item.name)
                .font(.title2.weight(.bold))
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("storage.detail.title")
            Text(StorageItemPresentation.description(for: item))
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .lineSpacing(4)
            if item.acquisitionCondition != nil {
                Text(StorageItemPresentation.conditionDescription(for: item))
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("storage.detail.condition")
                    .accessibilityValue(item.acquisitionCondition ?? "none")
            }
        }
    }

    private var statusCard: some View {
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
        .frame(height: 50)
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

    private var primaryAction: some View {
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
                .frame(height: 48)
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

    private var preview: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            Text("미니홈피 적용 예시")
                .font(PlanteriorTypography.sectionTitle)
            HStack(spacing: PlanteriorSpacing.medium) {
                Image(.storageContext)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 48, height: 48)
                    .clipShape(
                        RoundedRectangle(cornerRadius: PlanteriorRadius.small)
                    )
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
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
            .frame(height: 72)
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

    private var statusText: String {
        if isApplied {
            return "적용 중"
        }
        if isOwned {
            return "보관 중"
        }
        return StorageItemPresentation.eligibilityText(eligibility)
    }

    private var actionTitle: String {
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

    private var actionIdentifier: String {
        let action = isOwned ? (isApplied ? "remove" : "apply") : "acquire"
        return "storage.detail.\(action).\(item.id.rawValue)"
    }
}
