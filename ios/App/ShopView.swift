import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct ShopView: View {
    let entries: [InventoryCatalogEntry]
    let hasMore: Bool
    let loadMore: () -> Void
    let acquire: (ShopItem) -> Void
    let showDetail: (ShopItem) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("공개 아이템")
                .font(PlanteriorTypography.sectionTitle)
            if !entries.isEmpty {
                Text("아이템 \(entries.count)개")
                    .foregroundStyle(
                        PlanteriorPalette.textSecondary.color
                    )
                    .accessibilityIdentifier("shop.ready")
            }
            if entries.isEmpty {
                Text("조건에 맞는 공개 아이템이 없어요.")
                    .accessibilityIdentifier("shop.empty")
            } else {
                ForEach(entries, id: \.item.id) { entry in
                    PlanteriorCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Button(entry.item.name) {
                                showDetail(entry.item)
                            }
                            .font(PlanteriorTypography.sectionTitle)
                            .frame(
                                minHeight: PlanteriorControl.minimumTarget
                            )
                            .foregroundStyle(
                                PlanteriorPalette.accent.color
                            )
                            .accessibilityIdentifier(
                                "shop.row.\(entry.item.id.rawValue)"
                            )
                            Text(conditionText(entry.eligibility))
                            Button("획득") {
                                acquire(entry.item)
                            }
                            .disabled(entry.eligibility != .eligible)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: PlanteriorControl.minimumTarget)
                            .background(PlanteriorPalette.accent.color)
                            .foregroundStyle(
                                PlanteriorPalette.textOnAccent.color
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .opacity(
                                entry.eligibility == .eligible ? 1 : 0.45
                            )
                            .accessibilityIdentifier(
                                "shop.acquire.\(entry.item.id.rawValue)"
                            )
                            .accessibilityLabel(
                                "\(entry.item.name) 획득"
                            )
                        }
                    }
                }
                if hasMore {
                    Button("더 보기", action: loadMore)
                        .frame(maxWidth: .infinity)
                        .frame(
                            minHeight: PlanteriorControl.minimumTarget
                        )
                        .accessibilityIdentifier("shop.load-more")
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("shop.screen")
    }

    private func conditionText(
        _ eligibility: InventoryAcquisitionEligibility
    ) -> String {
        switch eligibility {
        case .eligible: "획득 가능"
        case .conditionNotMet("registered-plant"):
            "조건 미충족 · 식물 등록 필요"
        case .conditionNotMet:
            "조건 미충족 · 조건 확인 필요"
        case .alreadyOwned: "보유 중"
        }
    }
}
