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
    }

    private var profileAvatar: some View {
        Image(systemName: "leaf.fill")
            .font(.system(size: 28))
            .foregroundStyle(PlanteriorPalette.accent.color)
            .frame(
                width: SettingsReferenceMetrics.profileAvatarSize,
                height: SettingsReferenceMetrics.profileAvatarSize
            )
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
