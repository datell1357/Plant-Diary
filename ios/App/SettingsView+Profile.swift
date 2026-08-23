import PlanteriorDesignSystem
import SwiftUI

extension SettingsView {
    var profileCard: some View {
        PlanteriorCard {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: PlanteriorSpacing.large) {
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
        Text("🌿")
            .font(.system(size: 28))
            .frame(width: 60, height: 60)
            .background(PlanteriorPalette.accentSurface.color)
            .clipShape(Circle())
            .accessibilityHidden(true)
    }

    private var profileName: some View {
        Text(auth.accountProfile?.displayName ?? "Planterior 사용자")
            .font(PlanteriorTypography.heroGreeting)
            .accessibilityIdentifier("settings.profile.name")
    }

    private var profileBadge: some View {
        PlanteriorStatusPill("초보 식집사", variant: .accent)
    }

    @ViewBuilder
    private var profileEmail: some View {
        if let email = auth.accountProfile?.email {
            Text(verbatim: email)
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityLabel(email)
                .accessibilityIdentifier("settings.profile.email")
        }
    }
}
