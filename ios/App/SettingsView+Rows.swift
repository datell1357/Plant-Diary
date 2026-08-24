import PlanteriorDesignSystem
import SwiftUI

/// Screen-local measurements from the supplied 402x874 Settings references.
/// Shared tokens still own reusable spacing, color, type, radius, and controls.
enum SettingsReferenceMetrics {
    /// iOS 26 reports a 62pt top safe area on this reference device; Figma
    /// places app chrome at y=44, so only top chrome/body receives this delta.
    static let topSafeAreaCorrection: CGFloat = -18
    static let rootGroupSpacing: CGFloat = 22
    static let rootRowHeight: CGFloat = 52
    static let profileAvatarSize: CGFloat = 60
    static let rootDividerLeading = profileAvatarSize
    static let backGlyphSize: CGFloat = 18
    static let regionSearchHeight = PlanteriorControl.minimumTarget
    static let regionCurrentLocationHeight: CGFloat = 74
    static let regionRecentGap: CGFloat = 11
    static let regionRowHeight: CGFloat = 52
    static let warningHeight: CGFloat = 80
    static let saveButtonHeight: CGFloat = 48
    static let saveBarVerticalInset: CGFloat = 11
}

struct SettingsTopBarFrame: View {
    let identifier: String

    var body: some View {
        Rectangle()
            .fill(PlanteriorPalette.canvas.color)
            .accessibilityElement()
            .accessibilityIdentifier(identifier)
    }
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

extension SettingsView {
    func settingsGroup(
        _ title: String,
        @ViewBuilder content: () -> some View
    ) -> some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            Text(title)
                .font(PlanteriorTypography.caption.weight(.semibold))
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
            PlanteriorGroupedSurface { content() }
        }
    }

    func toggleRow(
        _ title: String,
        icon: String,
        isOn: Binding<Bool>,
        id: String
    ) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: PlanteriorSpacing.medium) {
                PlanteriorIconWell(systemImage: icon)
                Toggle(title, isOn: isOn)
                    .tint(PlanteriorPalette.accent.color)
                    .accessibilityIdentifier(id)
            }
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                Label(title, systemImage: icon)
                    .font(PlanteriorTypography.body)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                Toggle("알림 사용", isOn: isOn)
                    .tint(PlanteriorPalette.accent.color)
                    .accessibilityLabel(title)
                    .accessibilityIdentifier(id)
            }
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .padding(.vertical, PlanteriorSpacing.small)
        .frame(minHeight: SettingsReferenceMetrics.rootRowHeight)
    }

    func permissionRow(_ title: String, value: String, id: String) -> some View {
        rowLabel(title, icon: "checkmark.shield", value: value, disclosure: false)
            .accessibilityIdentifier(id)
    }

    func actionRow(
        _ title: String,
        icon: String,
        id: String,
        disclosure: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            rowLabel(title, icon: icon, disclosure: disclosure)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }

    func rowLabel(
        _ title: String,
        icon: String,
        value: String? = nil,
        disclosure: Bool = true
    ) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: PlanteriorSpacing.medium) {
                PlanteriorIconWell(systemImage: icon)
                Text(title)
                    .font(PlanteriorTypography.body)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                Spacer(minLength: PlanteriorSpacing.small)
                if let value {
                    Text(value)
                        .font(PlanteriorTypography.supporting)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .multilineTextAlignment(.trailing)
                }
                if disclosure {
                    disclosureIndicator
                }
            }
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                HStack(spacing: PlanteriorSpacing.medium) {
                    PlanteriorIconWell(systemImage: icon)
                    Text(title)
                        .font(PlanteriorTypography.body)
                        .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    Spacer(minLength: PlanteriorSpacing.small)
                    if disclosure {
                        disclosureIndicator
                    }
                }
                if let value {
                    Text(value)
                        .font(PlanteriorTypography.supporting)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .padding(.vertical, PlanteriorSpacing.small)
        .frame(minHeight: SettingsReferenceMetrics.rootRowHeight)
        .contentShape(Rectangle())
    }

    private var disclosureIndicator: some View {
        Image(systemName: "chevron.right")
            .font(PlanteriorTypography.caption)
            .foregroundStyle(PlanteriorPalette.textTertiary.color)
            .accessibilityHidden(true)
    }

    var rowDivider: some View {
        Divider().padding(
            .leading,
            SettingsReferenceMetrics.rootDividerLeading
        )
    }
}
