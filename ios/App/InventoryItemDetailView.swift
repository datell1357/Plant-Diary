import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct InventoryItemDetailView: View {
    let item: ShopItem
    let isOwned: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Image(systemName: iconName)
                    .font(.system(size: 64))
                    .foregroundStyle(PlanteriorPalette.accent.color)
                Text(item.name)
                    .font(PlanteriorTypography.screenTitle)
                Text(categoryName)
                Text(conditionDescription)
                    .accessibilityIdentifier("storage.detail.condition")
                    .accessibilityValue(
                        item.acquisitionCondition ?? "none"
                    )
                Text(isOwned ? "보유 중" : "미보유")
                Spacer()
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PlanteriorPalette.canvas.color)
            .navigationTitle("아이템 정보")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("닫기") { dismiss() }
                }
            }
        }
        .accessibilityIdentifier("storage.detail.\(item.id.rawValue)")
    }

    private var iconName: String {
        switch item.category {
        case .background: "photo"
        case .furniture: "chair.lounge"
        case .decoration: "lamp.table"
        }
    }

    private var categoryName: String {
        switch item.category {
        case .background: "배경"
        case .furniture: "가구"
        case .decoration: "소품"
        }
    }

    private var conditionDescription: String {
        switch item.acquisitionCondition {
        case "registered-plant":
            "등록한 식물이 있어야 획득할 수 있어요."
        case .some:
            "획득 조건을 확인해 주세요."
        case nil:
            "별도 획득 조건이 없어요."
        }
    }
}
