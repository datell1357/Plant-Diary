import PlanteriorDesignSystem
import SwiftUI

extension SettingsView {
    var profileCard: some View {
        PlanteriorCard {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: PlanteriorSpacing.medium) {
                    profileAvatar
                    VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                        HStack(spacing: PlanteriorSpacing.small) {
                            profileName
                            profileBadge
                        }
                        profileEmail
                    }
                }
                VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
                    profileAvatar
                    profileName
                    profileBadge
                    profileEmail
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("settings.profile-card")
    }

    private var profileAvatar: some View {
        Image(systemName: "leaf.fill")
            .font(.title2)
            .foregroundStyle(PlanteriorPalette.accent.color)
            .frame(width: 56, height: 56)
            .background(PlanteriorPalette.accentSurface.color)
            .clipShape(Circle())
            .accessibilityHidden(true)
    }

    private var profileName: some View {
        Text("민지").font(PlanteriorTypography.heroGreeting)
    }

    private var profileBadge: some View {
        PlanteriorStatusPill("초보 식집사", variant: .accent)
    }

    private var profileEmail: some View {
        Text("minji@email.com")
            .font(PlanteriorTypography.supporting)
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .accessibilityLabel("이메일 minji 골뱅이 email 점 com")
    }
}
