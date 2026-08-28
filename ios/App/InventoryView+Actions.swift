import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension InventoryView {
    func warehouseCard(_ item: ShopItem) -> some View {
        let applied = isApplied(item)
        return Button {
            selectedItem = item
        } label: {
            warehouseCardContent(item, applied: applied)
        }
        .buttonStyle(.plain)
        .frame(
            maxWidth: effectiveSizeCategory.isAccessibilityCategory
                ? .infinity
                : InventoryReferenceMetrics.gridCardWidth
        )
        .frame(height: InventoryReferenceMetrics.gridCardHeight)
        .accessibilityIdentifier("storage.row.\(item.id.rawValue)")
        .accessibilityLabel(item.name)
        .accessibilityValue(
            "\(StorageItemPresentation.categoryName(item.category)), " +
                (applied ? "적용 중" : "보유 중")
        )
    }

    func acquire(_ item: ShopItem) {
        Task { @MainActor in
            let outcome = await repository.acquire(
                itemID: item.id,
                metConditions: metConditions
            )
            message = acquisitionMessage(for: outcome, item: item)
        }
    }

    func togglePlacement(_ item: ShopItem) {
        Task {
            let outcome = await InventoryPlacementService().toggle(
                item: item,
                inventory: repository,
                miniHome: miniHomeStore
            )
            message = placementMessage(for: outcome, item: item)
        }
    }

    var accountScopeID: String? {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_AUTHENTICATED"] == "1" {
                return ProcessInfo.processInfo.environment[
                    "QA_INVENTORY_ACCOUNT_ID"
                ] ?? "qa-account"
            }
        #endif
        return miniHomeStore.accountID ?? auth.accountID?.rawValue
    }

    var progressionAccountID: AccountID? {
        guard let accountScopeID else { return nil }
        return try? AccountID.parse(accountScopeID)
    }

    private func warehouseCardContent(
        _ item: ShopItem,
        applied: Bool
    ) -> some View {
        VStack(
            alignment: .leading,
            spacing: InventoryReferenceMetrics.cardTextSpacing
        ) {
            ZStack(alignment: .topLeading) {
                Image(StorageItemPresentation.asset(for: item))
                    .resizable()
                    .scaledToFill()
                    .frame(
                        width: InventoryReferenceMetrics.cardImageSize.width,
                        height: InventoryReferenceMetrics.cardImageSize.height
                    )
                    .clipped()
                    .background(PlanteriorPalette.subtle.color)
                    .clipShape(
                        RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                    )
                    .accessibilityLabel("\(item.name) 이미지")
                    .accessibilityIdentifier("storage.image.\(item.id.rawValue)")
                if applied {
                    appliedBadge(item)
                }
            }
            Text(item.name)
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(PlanteriorSpacing.small)
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
    }

    private func appliedBadge(_ item: ShopItem) -> some View {
        Text("적용 중")
            .font(InventoryReferenceMetrics.appliedBadgeFont)
            .foregroundStyle(PlanteriorPalette.textOnAccent.color)
            .padding(.horizontal, InventoryReferenceMetrics.appliedBadgeInset)
            .frame(height: InventoryReferenceMetrics.appliedBadgeHeight)
            .background(PlanteriorPalette.accent.color)
            .clipShape(
                RoundedRectangle(
                    cornerRadius: InventoryReferenceMetrics.appliedBadgeRadius
                )
            )
            .padding(InventoryReferenceMetrics.appliedBadgeInset)
            .accessibilityLabel("적용 상태")
            .accessibilityValue("적용 중")
            .accessibilityIdentifier("storage.applied.\(item.id.rawValue)")
    }

    private func acquisitionMessage(
        for outcome: InventoryAcquisitionOutcome,
        item: ShopItem
    ) -> String {
        switch outcome {
        case .acquired: "창고에 추가했어요 · \(item.name)"
        case .conditionNotMet: "획득 조건을 아직 충족하지 못했어요."
        case .alreadyOwned: "이미 보유한 아이템이에요."
        case .failed(.notAuthenticated): "로그인 후 아이템을 획득할 수 있어요."
        case .failed(.itemUnavailable): "현재 획득할 수 없는 아이템이에요."
        case .failed: "획득하지 못했어요. 다시 시도해 주세요."
        }
    }

    private func placementMessage(
        for outcome: InventoryPlacementOutcome,
        item: ShopItem
    ) -> String {
        switch outcome {
        case .applied: "미니홈에 적용했어요 · \(item.name)"
        case .removed: "미니홈에서 제거했어요 · \(item.name)"
        case .unowned: "보유하지 않은 아이템은 적용할 수 없어요."
        case .limitReached: "이 카테고리의 배치 한도에 도달했어요."
        case .failed(.notAuthenticated): "로그인 후 미니홈에 적용할 수 있어요."
        case .failed(.roomUnavailable): "미니홈을 준비하지 못했어요."
        case .failed: "변경을 저장하지 못했어요."
        }
    }
}
