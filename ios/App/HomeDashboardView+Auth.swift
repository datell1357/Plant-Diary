import PlanteriorDesignSystem
import SwiftUI

extension HomeDashboardView {
    /// Figma `top-area-wrapper` §6.2: 40pt avatar, greeting stack, notification
    /// button, then the tappable room-title row. The body below is identical in
    /// every auth state — signed-out never hides it (§8.3).
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
                HStack(spacing: 10) {
                    profileAvatar
                    greetingStack
                    Spacer(minLength: PlanteriorSpacing.small)
                    notificationButton
                }
            }
            // The title's visible line owns a 20pt track while its button keeps
            // the 44pt hit target and may extend beyond that layout track. This
            // preserves the Figma media cadence without negative section gaps.
            titleRow
                .frame(height: PlanteriorSpacing.extraLarge, alignment: .leading)
        }
        .padding(.horizontal, PlanteriorSpacing.extraSmall)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home.header")
    }

    private var profileAvatar: some View {
        Image(.homeAvatar)
            .resizable()
            .scaledToFill()
            .frame(width: 40, height: 40)
            .clipShape(Circle())
            .accessibilityIdentifier("home.avatar")
            .accessibilityHidden(true)
    }

    private var greetingStack: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
            Text("안녕하세요, \(profileName)님!")
                .font(PlanteriorTypography.sectionTitle.weight(.bold))
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityIdentifier("home.greeting")
                .accessibilityAddTraits(.isHeader)
            Text(greetingMeta)
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .accessibilityIdentifier("home.greeting.meta")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var notificationButton: some View {
        Button(action: openCareSettings) {
            Image(systemName: "bell.badge")
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .frame(width: 40, height: 40)
                .background(PlanteriorPalette.surface.color)
                .clipShape(Circle())
                .frame(
                    width: PlanteriorControl.minimumTarget,
                    height: PlanteriorControl.minimumTarget
                )
        }
        .buttonStyle(.plain)
        .frame(
            width: PlanteriorControl.minimumTarget,
            height: PlanteriorControl.minimumTarget
        )
        .accessibilityLabel("알림 설정")
        .accessibilityIdentifier("home.notifications")
    }

    /// §6.2 title row: the name opens the rename dialog; the signed-out variant
    /// puts the green start link on the trailing side.
    private var titleRow: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: PlanteriorSpacing.small) {
                roomTitleButton
                Spacer(minLength: PlanteriorSpacing.small)
                if authenticationState != .authenticated {
                    loginLink
                }
            }
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                roomTitleButton
                if authenticationState != .authenticated {
                    loginLink
                }
            }
        }
    }

    private var roomTitleButton: some View {
        Button(action: requestRename) {
            Text("\(roomTitle) 🏡")
                .font(PlanteriorTypography.sectionTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .frame(minHeight: PlanteriorControl.minimumTarget, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("home.room.title")
        .accessibilityAddTraits(.isHeader)
    }

    private var loginLink: some View {
        Button(action: openCamera) {
            Text("로그인하고 시작하기")
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("home.login.link")
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
