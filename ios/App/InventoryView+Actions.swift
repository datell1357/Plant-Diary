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
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                ZStack(alignment: .topLeading) {
                    Image(StorageItemPresentation.asset(for: item))
                        .resizable()
                        .scaledToFill()
                        .frame(maxWidth: .infinity)
                        .aspectRatio(1, contentMode: .fit)
                        .background(PlanteriorPalette.subtle.color)
                        .clipShape(
                            RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                        )
                        .accessibilityLabel("\(item.name) 이미지")
                        .accessibilityIdentifier(
                            "storage.image.\(item.id.rawValue)"
                        )
                    if applied {
                        PlanteriorStatusPill("적용 중", variant: .accent)
                            .padding(PlanteriorSpacing.extraSmall)
                            .accessibilityLabel("적용 상태")
                            .accessibilityValue("적용 중")
                    }
                }
                Text(item.name)
                    .font(PlanteriorTypography.caption.weight(.medium))
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("storage.row.\(item.id.rawValue)")
        .accessibilityLabel(item.name)
        .accessibilityValue(
            "\(StorageItemPresentation.categoryName(item.category)), " +
                (applied ? "적용 중" : "보유 중")
        )
    }

    func acquire(_ item: ShopItem) {
        switch repository.acquire(
            itemID: item.id,
            metConditions: metConditions
        ) {
        case .acquired:
            message = "창고에 추가했어요 · \(item.name)"
        case .conditionNotMet:
            message = "획득 조건을 아직 충족하지 못했어요."
        case .alreadyOwned:
            message = "이미 보유한 아이템이에요."
        case .failed:
            message = "획득하지 못했어요. 다시 시도해 주세요."
        case .unavailable:
            message = "아이템 획득 서비스가 아직 연결되지 않았어요."
        }
    }

    func togglePlacement(_ item: ShopItem) {
        let outcome = InventoryPlacementService().toggle(
            item: item,
            inventory: repository,
            accountID: accountScopeID,
            now: now
        )
        switch outcome {
        case .applied:
            message = "미니홈에 적용했어요 · \(item.name)"
        case .removed:
            message = "미니홈에서 제거했어요 · \(item.name)"
        case .unowned:
            message = "보유하지 않은 아이템은 적용할 수 없어요."
        case .limitReached:
            message = "이 카테고리의 배치 한도에 도달했어요."
        case .failed:
            message = "변경을 저장하지 못했어요."
        case .unavailable:
            message = "아이템 적용 서비스가 아직 연결되지 않았어요."
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
        return auth.accountID?.rawValue
    }

    var progressionAccountID: AccountID? {
        guard let accountScopeID else { return nil }
        return try? AccountID.parse(accountScopeID)
    }
}
