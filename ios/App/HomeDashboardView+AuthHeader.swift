import PlanteriorDesignSystem
import SwiftUI

extension HomeDashboardView {
    var profileAvatar: some View {
        Image(.homeAvatar)
            .resizable()
            .scaledToFill()
            .frame(
                width: HomeReferenceMetrics.avatarSide,
                height: HomeReferenceMetrics.avatarSide
            )
            .clipShape(Circle())
            .accessibilityIdentifier("home.avatar")
            .accessibilityHidden(true)
    }

    var greetingStack: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
            Text("안녕하세요, \(profileName)님!")
                .font(PlanteriorTypography.sectionTitle.weight(.bold))
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityIdentifier("home.greeting")
                .accessibilityAddTraits(.isHeader)
            greetingMetadata
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The bell keeps its 40pt Figma avatar-sized well, but the reported hit
    /// target must be the 44pt minimum. Nesting frames around the clipped
    /// circle collapsed the button's frame back to 40; giving the label an
    /// explicit 44pt content shape - the `roomTitleButton` pattern - keeps the
    /// visual well and the target independent.
    var notificationButton: some View {
        Button(action: openCareSettings) {
            Image(systemName: "bell.badge")
                .font(PlanteriorTypography.supporting)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .frame(
                    width: HomeReferenceMetrics.avatarSide,
                    height: HomeReferenceMetrics.avatarSide
                )
                .background(PlanteriorPalette.surface.color)
                .clipShape(Circle())
                .frame(
                    minWidth: PlanteriorControl.minimumTarget,
                    minHeight: PlanteriorControl.minimumTarget
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("알림 설정")
        .accessibilityIdentifier("home.notifications")
    }

    @ViewBuilder
    private var greetingMetadata: some View {
        if effectiveSizeCategory.isAccessibilityCategory {
            HStack(alignment: .firstTextBaseline, spacing: PlanteriorSpacing.extraSmall) {
                greetingMetadataText
                    .fixedSize(horizontal: false, vertical: true)
                weatherGlyph
            }
        } else {
            HStack(spacing: PlanteriorSpacing.extraSmall) {
                greetingMetadataText
                    .lineLimit(1)
                    .minimumScaleFactor(HomeReferenceMetrics.metadataMinimumScale)
                weatherGlyph
            }
        }
    }

    private var greetingMetadataText: some View {
        Text(greetingMeta)
            .font(PlanteriorTypography.caption)
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .accessibilityIdentifier("home.greeting.meta")
    }

    @ViewBuilder
    private var weatherGlyph: some View {
        if authenticationState == .authenticated {
            Image(systemName: "sun.max.fill")
                .font(HomeReferenceMetrics.weatherGlyphFont)
                .foregroundStyle(PlanteriorPalette.accent.color)
                .frame(
                    width: HomeReferenceMetrics.weatherGlyphSide,
                    height: HomeReferenceMetrics.weatherGlyphSide
                )
                .accessibilityLabel("맑음")
                .accessibilityIdentifier("home.greeting.weather-glyph")
        }
    }

    /// Large keeps its 20pt track; AX uses the title's intrinsic height.
    @ViewBuilder
    var titleTrack: some View {
        if effectiveSizeCategory.isAccessibilityCategory {
            titleRow
        } else {
            titleRow
                .frame(height: PlanteriorSpacing.extraLarge, alignment: .leading)
        }
    }

    /// AX reflows the signed-out action below the room title.
    @ViewBuilder
    private var titleRow: some View {
        if effectiveSizeCategory.isAccessibilityCategory {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                roomTitleButton
                if authenticationState != .authenticated {
                    loginLink
                }
            }
        } else {
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
    }

    private var roomTitleButton: some View {
        Button(action: requestRename) {
            roomTitleLabel
                .frame(minHeight: PlanteriorControl.minimumTarget, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("home.room.title")
        .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private var roomTitleLabel: some View {
        if effectiveSizeCategory.isAccessibilityCategory {
            Text("\(roomTitle) 🏡")
                .font(PlanteriorTypography.sectionTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .lineLimit(nil)
                .padding(.vertical, PlanteriorSpacing.extraSmall)
        } else {
            Text("\(roomTitle) 🏡")
                .font(PlanteriorTypography.sectionTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
        }
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
}
