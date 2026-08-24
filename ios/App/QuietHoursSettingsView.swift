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
                VStack(
                    alignment: .leading,
                    spacing: PlanteriorSpacing.extraLarge
                ) {
                    toggleCard
                    Text("설정한 시간 동안 물\u{00A0}주기, 영양제 주기 등 일상적인 식물\u{00A0}관리\u{00A0}알림 및 푸시가 발송되지 않습니다.")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.bottom, PlanteriorSpacing.small)
                    VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                        Text("시간 범위 설정")
                            .font(PlanteriorTypography.caption.weight(.semibold))
                            .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        timeCard
                    }
                    warningCard
                }
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
                .padding(.top, PlanteriorSpacing.large)
                .padding(.bottom, PlanteriorSpacing.large)
            }
            .accessibilityIdentifier("quiet-hours.screen")
            .settingsReferenceBody()
            saveBar
        }
        .settingsReferenceChrome()
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var topBar: some View {
        PlanteriorTopBar("알림 금지 시간 설정", leading: {
            SettingsBackButton(identifier: "quiet-hours.back") {
                dismiss()
            }
        }, trailing: {
            EmptyView()
        })
        .background {
            SettingsTopBarFrame(identifier: "quiet-hours.top-bar")
        }
        .settingsReferenceTopBar()
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
            .frame(minHeight: PlanteriorControl.rowHeight)
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
            HStack(alignment: .top, spacing: PlanteriorSpacing.small) {
                Image(systemName: "lightbulb")
                    .foregroundStyle(PlanteriorPalette.warning.color)
                    .frame(
                        width: PlanteriorSpacing.extraLarge,
                        height: PlanteriorSpacing.extraLarge
                    )
                    .accessibilityHidden(true)
                Text(
                    sizeCategory.isAccessibilityCategory
                        ? "태풍, 한파, 폭염 등 식물 생존에 직접적 영향을 미치는 기\u{2060}상 특보 및 재난 알림은 시간 설정과 관계없이 즉시 발송됩니다."
                        : "태풍, 한파, 폭염 등 식물 생존에 직접적 영향을 미치는\n기\u{2060}상 특보 및 재난 알림은 시간 설정과 관계없이\n즉시 발송됩니다."
                )
                    .font(PlanteriorTypography.caption.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.warning.color)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(
                maxWidth: .infinity,
                minHeight: SettingsReferenceMetrics.saveButtonHeight,
                alignment: .leading
            )
        }
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                .stroke(
                    PlanteriorPalette.warning.color,
                    lineWidth: PlanteriorControl.hairline
                )
        }
        .frame(
            minHeight: SettingsReferenceMetrics.warningHeight,
            maxHeight: sizeCategory.isAccessibilityCategory
                ? nil
                : SettingsReferenceMetrics.warningHeight
        )
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("quiet-hours.warning")
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
            .frame(minHeight: SettingsReferenceMetrics.rootRowHeight)
            .disabled(!enabled)
            .accessibilityValue(
                QuietHoursPresentation.localTime(from: selection.wrappedValue)?
                    .rawValue ?? ""
            )
            .accessibilityIdentifier(id)
        }
    }

    private var saveBar: some View {
        Button(action: save) {
            Text("저장하기")
                .font(PlanteriorTypography.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .frame(height: SettingsReferenceMetrics.saveButtonHeight)
        }
        .buttonStyle(.plain)
        .foregroundStyle(PlanteriorPalette.textOnAccent.color)
        .background(PlanteriorPalette.accent.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
        .accessibilityIdentifier("quiet-hours.save")
        .padding(.horizontal, PlanteriorSpacing.extraLarge)
        .padding(
            .vertical,
            SettingsReferenceMetrics.saveBarVerticalInset
        )
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
        onSaved()
        dismiss()
    }
}
