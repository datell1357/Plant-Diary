import PlanteriorDesignSystem
import SwiftUI

extension HomeDashboardView {
    var miniHomeSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("나의 미니홈")
                .font(PlanteriorTypography.sectionTitle)
            Button(action: requestMiniHomeOpen) {
                PlanteriorCard {
                    HStack {
                        Text(
                            store.miniHome.map {
                                "\($0.name) · 저장됨"
                            }
                                ?? "아직 저장된 미니홈이 없어요."
                        )
                        .accessibilityIdentifier("home.minhome.label")
                        Spacer()
                        Image(systemName: "chevron.right")
                            .accessibilityHidden(true)
                    }
                }
            }
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .accessibilityIdentifier("home.minhome.preview")
        }
    }

    func requestMiniHomeOpen() {
        if isInitialLoadComplete {
            openMiniHome()
        } else {
            pendingMiniHomeOpen = true
        }
    }
}
