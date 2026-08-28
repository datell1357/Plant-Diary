import PlanteriorDesignSystem
import SwiftUI

extension QuietHoursSettingsView {
    private static let informationLeadingCopy =
        "설정 완료 시 해당 시간 동안 물\u{00A0}주기, 영양제 주기 등"
    private static let informationTrailingCopy =
        "일상적인 식물\u{00A0}관리\u{00A0}알림 및 푸시가 발송되지 않습니다."

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

    var informationCopy: some View {
        VStack(
            alignment: .leading,
            spacing: sizeCategory.isAccessibilityCategory
                ? PlanteriorSpacing.medium
                : 0
        ) {
            if sizeCategory.isAccessibilityCategory {
                Text(Self.informationLeadingCopy)
                    .accessibilityIdentifier("quiet-hours.information.leading")
                Text(Self.informationTrailingCopy)
                    .accessibilityIdentifier("quiet-hours.information.trailing")
            } else {
                Text(
                    Self.informationLeadingCopy
                        + " "
                        + Self.informationTrailingCopy
                )
            }
        }
        .font(PlanteriorTypography.caption)
        .foregroundStyle(
            sizeCategory.isAccessibilityCategory
                ? PlanteriorPalette.textPrimary.color
                : PlanteriorPalette.textSecondary.color
        )
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.bottom, PlanteriorSpacing.small)
        .frame(
            minHeight: sizeCategory.isAccessibilityCategory
                ? nil
                : SettingsReferenceMetrics.quietHoursInformationMinimumHeight
        )
    }

    var warningCard: some View {
        SettingsWarningCard(
            isAccessibilityCategory: sizeCategory.isAccessibilityCategory
        )
    }
}

struct SettingsWarningCard: View {
    let isAccessibilityCategory: Bool

    static let localizedCopy =
        "태풍, 한파, 폭염\u{00A0}등 식물 생존에 직접적 영향을 미치는 "
            + "기상 특보 및 재난 알림은 시간 설정과 관계없이 즉시 발송됩니다."
    static let accessibilityCopy = localizedCopy
    static let referenceVisualCopy = KoreanTypography.binding(
        localizedCopy,
        phrases: ["기상", "발송됩니다."]
    )

    var body: some View {
        HStack(
            alignment: .top,
            spacing: SettingsReferenceMetrics.warningContentSpacing
        ) {
            Image(systemName: "lightbulb")
                .font(SettingsReferenceMetrics.warningIconTypography)
                .foregroundStyle(PlanteriorPalette.warning.color)
                .frame(
                    width: SettingsReferenceMetrics.warningIconWidth,
                    height: PlanteriorSpacing.extraLarge
                )
            Text(
                isAccessibilityCategory
                    ? Self.localizedCopy
                    : Self.referenceVisualCopy
            )
            .font(SettingsReferenceMetrics.warningTypography)
            .foregroundStyle(PlanteriorPalette.warning.color)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityHidden(true)
        .padding(SettingsReferenceMetrics.warningContentInset)
        .frame(
            maxWidth: .infinity,
            minHeight: SettingsReferenceMetrics.warningHeight,
            maxHeight: isAccessibilityCategory
                ? nil
                : SettingsReferenceMetrics.warningHeight,
            alignment: .leading
        )
        .background { warningBackground }
        .accessibilityRepresentation {
            Text(Self.accessibilityCopy)
                .accessibilityIdentifier("quiet-hours.warning")
        }
    }

    private var warningBackground: some View {
        Canvas { context, size in
            let inset = PlanteriorControl.hairline / 2
            let bounds = CGRect(origin: .zero, size: size)
                .insetBy(dx: inset, dy: inset)
            let path = Path(
                roundedRect: bounds,
                cornerRadius: PlanteriorRadius.large
            )
            context.fill(
                path,
                with: .color(PlanteriorPalette.warningSurface.color)
            )
            context.stroke(
                path,
                with: .color(PlanteriorPalette.border.color),
                lineWidth: PlanteriorControl.hairline
            )
        }
        .accessibilityHidden(true)
    }
}
