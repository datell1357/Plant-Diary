import PlanteriorDesignSystem
import SwiftUI

extension HomeDashboardView {
    var homeHeader: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
            if effectiveSizeCategory.isAccessibilityCategory {
                HStack {
                    profileAvatar
                    Spacer()
                    notificationButton
                }
                greetingStack
            } else {
                HStack(spacing: HomeReferenceMetrics.headerRowSpacing) {
                    profileAvatar
                    greetingStack
                    Spacer(minLength: PlanteriorSpacing.small)
                    notificationButton
                }
            }
            titleTrack
        }
        .padding(.horizontal, PlanteriorSpacing.extraSmall)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home.header")
    }

    @ViewBuilder
    var signingInIndicator: some View {
        if authenticationState == .signingIn {
            HStack(spacing: PlanteriorSpacing.small) {
                ProgressView()
                    .accessibilityHidden(true)
                Text("로그인 중")
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("home.auth.signing-in")
            }
        }
    }
}
