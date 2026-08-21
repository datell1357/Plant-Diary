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
        .frame(minHeight: PlanteriorControl.rowHeight)
        .contentShape(Rectangle())
    }

    private var disclosureIndicator: some View {
        Image(systemName: "chevron.right")
            .font(PlanteriorTypography.caption)
            .foregroundStyle(PlanteriorPalette.textTertiary.color)
            .accessibilityHidden(true)
    }

    var rowDivider: some View {
        Divider().padding(.leading, 60)
    }
}
