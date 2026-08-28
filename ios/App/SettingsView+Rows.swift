import PlanteriorDesignSystem
import SwiftUI

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
        icon: SettingsIcon,
        isOn: Binding<Bool>,
        id: String
    ) -> some View {
        SettingsToggle(
            title: title,
            icon: icon,
            isOn: isOn,
            identifier: id
        )
        .font(PlanteriorTypography.body)
        .padding(.horizontal, PlanteriorSpacing.large)
        .padding(.vertical, PlanteriorSpacing.extraSmall)
        .frame(minHeight: SettingsReferenceMetrics.rootRowHeight)
    }

    func permissionRow(_ title: String, value: String, id: String) -> some View {
        rowLabel(
            title,
            icon: .system("checkmark.shield"),
            value: value,
            disclosure: false
        )
        .accessibilityIdentifier(id)
    }

    func actionRow(
        _ title: String,
        icon: SettingsIcon,
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
        icon: SettingsIcon,
        value: String? = nil,
        disclosure: Bool = true
    ) -> some View {
        Group {
            if sizeCategory.isAccessibilityCategory {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                    HStack(spacing: PlanteriorSpacing.medium) {
                        SettingsIconWell(icon: icon)
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
            } else {
                HStack(spacing: PlanteriorSpacing.medium) {
                    SettingsIconWell(icon: icon)
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
