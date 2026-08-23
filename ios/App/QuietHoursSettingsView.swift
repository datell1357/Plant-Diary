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
        VStack(spacing: 0) {
            topBar
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    toggleCard
                    Text("설정한 시간 동안 물\u{00A0}주기, 영양제 주기 등 일상적인 식물\u{00A0}관리\u{00A0}알림 및 푸시가 발송되지 않습니다.")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .fixedSize(horizontal: false, vertical: true)
                    VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                        Text("시간 범위 설정")
                            .font(PlanteriorTypography.caption.weight(.semibold))
                            .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        timeCard
                    }
                    warningCard
                }
                .padding(.horizontal, 20)
                .padding(.top, PlanteriorSpacing.medium)
                .padding(.bottom, PlanteriorSpacing.large)
            }
            .accessibilityIdentifier("quiet-hours.screen")
            saveBar
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var topBar: some View {
        PlanteriorTopBar("알림 금지 시간 설정", leading: {
            if showsCloseButton {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .frame(
                            width: PlanteriorControl.minimumTarget,
                            height: PlanteriorControl.minimumTarget
                        )
                }
                .buttonStyle(.plain)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityLabel("뒤로")
                .accessibilityIdentifier("quiet-hours.back")
            }
        }, trailing: {
            EmptyView()
        })
    }

    private var toggleCard: some View {
        PlanteriorGroupedSurface {
            HStack(spacing: PlanteriorSpacing.medium) {
                PlanteriorIconWell(systemImage: "clock")
                Toggle("알림 금지 시간 사용", isOn: $enabled)
                    .font(PlanteriorTypography.body.weight(.medium))
                    .tint(PlanteriorPalette.accent.color)
                    .accessibilityIdentifier("quiet-hours.enabled")
            }
            .padding(.horizontal, PlanteriorSpacing.large)
            .frame(minHeight: 56)
        }
    }

    private var timeCard: some View {
        PlanteriorGroupedSurface {
            timePicker("시작 시간", selection: $startDate, id: "quiet-hours.start")
            Divider().padding(.leading, PlanteriorSpacing.large)
            timePicker("종료 시간", selection: $endDate, id: "quiet-hours.end")
        }
        .opacity(enabled ? 1 : 0.55)
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
                .accessibilityValue(
                    QuietHoursPresentation.localTime(from: selection.wrappedValue)?
                        .rawValue ?? ""
                )
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
            .padding(.horizontal, PlanteriorSpacing.large)
            .frame(minHeight: 52)
            .disabled(!enabled)
            .accessibilityValue(
                QuietHoursPresentation.localTime(from: selection.wrappedValue)?
                    .rawValue ?? ""
            )
            .accessibilityIdentifier(id)
        }
    }

    private var saveBar: some View {
        PlanteriorPrimaryButton("저장하기", action: save)
            .accessibilityIdentifier("quiet-hours.save")
            .padding(.horizontal, 20)
            .padding(.vertical, 11)
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
