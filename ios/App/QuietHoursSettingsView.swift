import PlanteriorDesignSystem
import SwiftUI

struct QuietHoursSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.sizeCategory) private var sizeCategory
    @State private var enabled: Bool
    @State private var startDate: Date
    @State private var endDate: Date
    let showsCloseButton: Bool
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
        self.showsCloseButton = showsCloseButton
        self.onSaved = onSaved
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
                toggleCard
                Text("설정한 시간 동안 물 주기, 영양제 주기 등 일상적인 식물 관리 알림 및 푸시가 발송되지 않습니다.")
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .fixedSize(horizontal: false, vertical: true)
                Text("시간 범위 설정")
                    .font(PlanteriorTypography.caption.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .padding(.leading, PlanteriorSpacing.large)
                timeCard
                warningCard
            }
            .padding(PlanteriorSpacing.large)
            .padding(.bottom, PlanteriorSpacing.large)
        }
        .accessibilityIdentifier("quiet-hours.screen")
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("알림 금지 시간 설정")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsCloseButton {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.left")
                    }
                    .accessibilityLabel("뒤로")
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            saveBar
        }
    }

    private var toggleCard: some View {
        PlanteriorCard {
            HStack(spacing: PlanteriorSpacing.medium) {
                iconWell("clock")
                Toggle("알림 금지 시간 사용", isOn: $enabled)
                    .font(PlanteriorTypography.body.weight(.medium))
                    .tint(PlanteriorPalette.accent.color)
                    .accessibilityIdentifier("quiet-hours.enabled")
            }
        }
    }

    private var timeCard: some View {
        PlanteriorCard {
            VStack(spacing: 0) {
                timePicker("시작 시간", selection: $startDate, id: "quiet-hours.start")
                Divider().padding(.leading, PlanteriorControl.iconWellSize + 12)
                timePicker("종료 시간", selection: $endDate, id: "quiet-hours.end")
            }
        }
        .opacity(enabled ? 1 : 0.65)
    }

    private var warningCard: some View {
        PlanteriorCard(variant: .warning) {
            HStack(alignment: .top, spacing: PlanteriorSpacing.medium) {
                Image(systemName: "lightbulb")
                    .foregroundStyle(PlanteriorPalette.warning.color)
                    .frame(width: 20, height: 20)
                    .accessibilityHidden(true)
                Text("태풍, 한파, 폭염 등 식물 생존에 직접적 영향을 미치는 기상 특보 및 재난 알림은 시간 설정과 관계없이 즉시 발송됩니다.")
                    .font(PlanteriorTypography.caption.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private func timePicker(
        _ title: String,
        selection: Binding<Date>,
        id: String
    ) -> some View {
        if sizeCategory.isAccessibilityCategory {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                Text(title)
                    .font(PlanteriorTypography.body)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("\(id).label")
                DatePicker(
                    title,
                    selection: selection,
                    displayedComponents: .hourAndMinute
                )
                .labelsHidden()
                .datePickerStyle(.compact)
                .frame(
                    maxWidth: .infinity,
                    minHeight: PlanteriorControl.minimumTarget,
                    alignment: .leading
                )
                .accessibilityLabel(title)
                .accessibilityIdentifier(id)
            }
            .padding(.vertical, PlanteriorSpacing.small)
            .disabled(!enabled)
        } else {
            DatePicker(
                title,
                selection: selection,
                displayedComponents: .hourAndMinute
            )
            .datePickerStyle(.compact)
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .disabled(!enabled)
            .accessibilityIdentifier(id)
        }
    }

    private func iconWell(_ systemName: String) -> some View {
        Image(systemName: systemName)
            .foregroundStyle(PlanteriorPalette.accent.color)
            .frame(width: 32, height: 32)
            .background(PlanteriorPalette.accentSurface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.small))
            .accessibilityHidden(true)
    }

    private var saveBar: some View {
        PlanteriorPrimaryButton("저장하기", action: save)
            .accessibilityIdentifier("quiet-hours.save")
            .padding(PlanteriorSpacing.large)
            .background(PlanteriorPalette.canvas.color)
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
        onSaved()
        dismiss()
    }
}
