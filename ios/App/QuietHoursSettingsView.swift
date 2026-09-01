import PlanteriorDesignSystem
import SwiftUI

struct QuietHoursSettingsView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(\.sizeCategory) var sizeCategory
    @State var enabled: Bool
    @State var startDate: Date
    @State var endDate: Date
    let onSaved: () -> Void

    init(
        showsCloseButton: Bool = false,
        onSaved: @escaping () -> Void = {}
    ) {
        let preference = LocalNotificationPreferenceStore.shared.quietHours
        _enabled = State(initialValue: preference.enabled)
        _startDate = State(
            initialValue: QuietHoursPresentation.date(from: preference.start)
        )
        _endDate = State(
            initialValue: QuietHoursPresentation.date(from: preference.end)
        )
        self.onSaved = onSaved
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            screenContent
        }
        .settingsReferenceChrome()
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    @ViewBuilder
    private var screenContent: some View {
        if sizeCategory.isAccessibilityCategory {
            ScrollView {
                VStack(spacing: PlanteriorSpacing.none) {
                    formContent
                    saveBar
                }
                .safeAreaPadding(.bottom, PlanteriorSpacing.large)
            }
            .accessibilityIdentifier("quiet-hours.screen")
            .settingsReferenceBody()
        } else {
            VStack(spacing: PlanteriorSpacing.none) {
                ScrollView { formContent }
                    .accessibilityIdentifier("quiet-hours.screen")
                    .settingsReferenceBody()
                saveBar
            }
        }
    }

    private var formContent: some View {
        VStack(
            alignment: .leading,
            spacing: PlanteriorSpacing.extraLarge
        ) {
            toggleCard
            informationCopy
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                Text("시간 범위 설정")
                    .font(PlanteriorTypography.caption.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .frame(
                        minHeight: sizeCategory.isAccessibilityCategory
                            ? nil
                            : SettingsReferenceMetrics
                            .quietHoursSectionHeadingMinimumHeight,
                        alignment: .leading
                    )
                timeCard
            }
            warningCard
        }
        .padding(.horizontal, PlanteriorSpacing.extraLarge)
        .padding(.top, PlanteriorSpacing.large)
        .padding(.bottom, PlanteriorSpacing.large)
    }

    private var toggleCard: some View {
        PlanteriorGroupedSurface {
            SettingsToggle(
                title: "알림 금지 시간 사용",
                icon: .system("clock"),
                isOn: $enabled,
                identifier: "quiet-hours.enabled"
            )
            .font(PlanteriorTypography.body.weight(.medium))
            .padding(.horizontal, PlanteriorSpacing.large)
            .frame(minHeight: PlanteriorControl.rowHeight)
        }
    }

    private var saveBar: some View {
        Button(action: save) {
            Text("저장하기")
                .font(PlanteriorTypography.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .frame(minHeight: SettingsReferenceMetrics.saveButtonHeight)
        }
        .buttonStyle(.plain)
        .foregroundStyle(PlanteriorPalette.textOnAccent.color)
        .background(PlanteriorPalette.accent.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
        .accessibilityIdentifier("quiet-hours.save")
        .padding(.horizontal, PlanteriorSpacing.extraLarge)
        .padding(.vertical, SettingsReferenceMetrics.saveBarVerticalInset)
        .background(PlanteriorPalette.surface.color)
        .overlay(alignment: .top) {
            Divider()
        }
    }

    private func save() {
        guard let start = QuietHoursPresentation.localTime(from: startDate),
              let end = QuietHoursPresentation.localTime(from: endDate)
        else {
            return
        }
        LocalNotificationPreferenceStore.shared.setQuietHours(
            enabled: enabled,
            start: start,
            end: end
        )
        LocalNotificationScheduleStore.shared
            .refreshDeliveryForCurrentAccount()
        onSaved()
        dismiss()
    }
}
