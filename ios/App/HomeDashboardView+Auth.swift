import PlanteriorDesignSystem
import SwiftUI

extension HomeDashboardView {
    @ViewBuilder
    var authenticationContent: some View {
        switch authenticationState {
        case .loggedOut:
            PlanteriorCard {
                VStack(alignment: .leading, spacing: 12) {
                    Text("로그인하면 오늘의 돌봄을 확인할 수 있어요.")
                        .accessibilityIdentifier("home.auth.logged-out")
                    identifyButton
                }
            }
        case .signingIn:
            PlanteriorCard {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 8) {
                        ProgressView()
                            .accessibilityHidden(true)
                        Text("로그인 중")
                            .foregroundStyle(
                                PlanteriorPalette.textSecondary.color
                            )
                            .accessibilityIdentifier(
                                "home.auth.signing-in"
                            )
                    }
                    identifyButton
                }
            }
        case .authenticated:
            Text("오늘도 식물과 좋은 하루 보내세요.")
                .font(PlanteriorTypography.screenTitle)
                .accessibilityIdentifier("home.greeting")
            identifyButton
        }
    }

    var identifyButton: some View {
        PlanteriorPrimaryButton("식물 촬영 시작", action: openCamera)
            .accessibilityIdentifier("home.identify")
    }
}
