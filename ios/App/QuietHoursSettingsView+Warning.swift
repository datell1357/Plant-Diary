import PlanteriorDesignSystem
import SwiftUI

extension QuietHoursSettingsView {
    var topBar: some View {
        PlanteriorTopBar("알림 금지 시간 설정", leading: {
            SettingsBackButton(identifier: "quiet-hours.back") {
                dismiss()
            }
        }, trailing: {
            EmptyView()
        })
        .settingsReferenceTopBar()
    }

    var warningCard: some View {
        SettingsWarningCard(
            isAccessibilityCategory: sizeCategory.isAccessibilityCategory
        )
    }
}

struct SettingsWarningCard: View {
    let isAccessibilityCategory: Bool

    private static let accessibilityCopy =
        "태풍, 한파, 폭염 등 식물 생존에 직접적 영향을 미치는 "
            + "기상 특보 및 재난 알림은 시간 설정과 관계없이 즉시 발송됩니다."
    private static let referenceCopy =
        "태풍, 한파, 폭염 등 식물 생존에 직접적 영향을 미치는\n"
            + "기상 특보 및 재난 알림은 시간 설정과 관계없이\n"
            + "즉시 발송됩니다."

    var body: some View {
        HStack(alignment: .top, spacing: PlanteriorSpacing.small) {
            Image(systemName: "lightbulb")
                .foregroundStyle(PlanteriorPalette.warningText.color)
                .frame(
                    width: SettingsReferenceMetrics.warningIconWidth,
                    height: PlanteriorSpacing.extraLarge
                )
                .accessibilityHidden(true)
            Text(
                isAccessibilityCategory
                    ? Self.accessibilityCopy
                    : Self.referenceCopy
            )
            .font(PlanteriorTypography.caption.weight(.semibold))
            .foregroundStyle(PlanteriorPalette.warningText.color)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("quiet-hours.warning-copy")
        }
        .padding(PlanteriorSpacing.large)
        .frame(
            maxWidth: .infinity,
            minHeight: SettingsReferenceMetrics.warningHeight,
            maxHeight: isAccessibilityCategory
                ? nil
                : SettingsReferenceMetrics.warningHeight,
            alignment: .leading
        )
        .background(PlanteriorPalette.warningSurface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                .stroke(
                    PlanteriorPalette.border.color,
                    lineWidth: PlanteriorControl.hairline
                )
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("quiet-hours.warning")
    }
}
