import PlanteriorDesignSystem
import SwiftUI

/// Screen-local measurements from the supplied 402x874 Settings references.
/// Shared tokens still own reusable spacing, color, type, radius, and controls.
enum SettingsReferenceMetrics {
    /// iOS 26 reports a 62pt top safe area on this reference device; Figma
    /// places app chrome at y=44, so only top chrome/body receives this delta.
    static let topSafeAreaCorrection: CGFloat = -18
    static let rootGroupSpacing: CGFloat = 22
    /// Keeps the complete AX5 Quiet Hours row below the initial tab-material
    /// boundary instead of painting only its leading portion behind the bar.
    static let rootAccessibilityAlertOffset = PlanteriorSpacing.medium
    static let rootRowHeight: CGFloat = 52
    static let locationGlyphSize = CGSize(width: 14, height: 18)
    static let regionLocationGlyphSize = CGSize(width: 12, height: 15)
    static let profileAvatarSize: CGFloat = 60
    static let profileAvatarGlyph = "🌿"
    static let rootDividerLeading = profileAvatarSize
    static let backGlyphSize: CGFloat = 18
    static let regionSearchHeight = PlanteriorControl.minimumTarget
    static let regionCurrentLocationHeight: CGFloat = 74
    static let regionRecentGap: CGFloat = 11
    static let regionRowHeight: CGFloat = 52
    static let quietHoursInformationMinimumHeight: CGFloat = 38.333
    static let quietHoursSectionHeadingMinimumHeight: CGFloat = 14.333
    static let warningHeight: CGFloat = 80
    static let warningIconWidth: CGFloat = 18
    static let warningContentInset = PlanteriorSpacing.large
    static let warningContentSpacing = PlanteriorSpacing.small
    static let warningTypography = Font.system(size: 13, weight: .bold)
    static let warningIconTypography = Font.system(size: 14, weight: .medium)
    static let quietHoursDisclosureGlyphSize = CGSize(width: 16, height: 16)
    static let saveButtonHeight: CGFloat = 48
    static let saveBarVerticalInset: CGFloat = 11
}

struct SettingsBackButton: View {
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Rectangle().fill(PlanteriorPalette.canvas.color)
                Image(systemName: "chevron.left")
                    .font(
                        .system(
                            size: SettingsReferenceMetrics.backGlyphSize,
                            weight: .semibold
                        )
                    )
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .accessibilityHidden(true)
            }
            .frame(
                width: PlanteriorControl.minimumTarget,
                height: PlanteriorControl.minimumTarget
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(
            width: PlanteriorControl.minimumTarget,
            height: PlanteriorControl.minimumTarget
        )
        .contentShape(Rectangle())
        .accessibilityLabel("뒤로")
        .accessibilityIdentifier(identifier)
    }
}

extension View {
    func settingsReferenceChrome() -> some View {
        background(PlanteriorPalette.canvas.color)
    }

    func settingsReferenceTopBar() -> some View {
        offset(y: SettingsReferenceMetrics.topSafeAreaCorrection)
    }

    func settingsReferenceBody() -> some View {
        padding(.top, SettingsReferenceMetrics.topSafeAreaCorrection)
    }
}
