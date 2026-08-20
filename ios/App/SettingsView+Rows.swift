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
                .padding(.leading, PlanteriorSpacing.large)
            VStack(spacing: 0, content: content)
                .background(PlanteriorPalette.surface.color)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
                .overlay {
                    RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                        .stroke(PlanteriorPalette.border.color, lineWidth: 1)
                }
        }
    }

    func toggleRow(
        _ title: String,
        icon: String,
        isOn: Binding<Bool>,
        id: String
    ) -> some View {
        HStack(spacing: PlanteriorSpacing.medium) {
            iconWell(icon)
            Toggle(title, isOn: isOn)
                .tint(PlanteriorPalette.accent.color)
                .accessibilityIdentifier(id)
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .frame(minHeight: PlanteriorControl.rowHeight)
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
        HStack(spacing: PlanteriorSpacing.medium) {
            iconWell(icon)
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
                Image(systemName: "chevron.right")
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textTertiary.color)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .frame(minHeight: PlanteriorControl.rowHeight)
        .contentShape(Rectangle())
    }

    func iconWell(_ name: String) -> some View {
        Image(systemName: name)
            .foregroundStyle(PlanteriorPalette.accent.color)
            .frame(width: 32, height: 32)
            .background(PlanteriorPalette.accentSurface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.small))
            .accessibilityHidden(true)
    }

    var rowDivider: some View {
        Divider().padding(.leading, 60)
    }
}
