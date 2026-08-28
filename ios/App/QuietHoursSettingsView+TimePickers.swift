import PlanteriorDesignSystem
import SwiftUI

extension QuietHoursSettingsView {
    var timeCard: some View {
        PlanteriorGroupedSurface {
            timePicker("시작 시간", selection: $startDate, id: "quiet-hours.start")
            Divider().padding(.leading, PlanteriorSpacing.large)
            timePicker("종료 시간", selection: $endDate, id: "quiet-hours.end")
        }
        .opacity(enabled ? 1 : 0.55)
    }

    @ViewBuilder
    func timePicker(
        _ title: String,
        selection: Binding<Date>,
        id: String
    ) -> some View {
        if sizeCategory.isAccessibilityCategory {
            accessibilityTimePicker(title, selection: selection, id: id)
        } else {
            standardTimePicker(title, selection: selection, id: id)
        }
    }

    private func accessibilityTimePicker(
        _ title: String,
        selection: Binding<Date>,
        id: String
    ) -> some View {
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
            .accessibilityValue(timeValue(selection))
            .accessibilityIdentifier(id)
        }
        .padding(.vertical, PlanteriorSpacing.small)
        .disabled(!enabled)
    }

    private func standardTimePicker(
        _ title: String,
        selection: Binding<Date>,
        id: String
    ) -> some View {
        DatePicker(
            title,
            selection: selection,
            displayedComponents: .hourAndMinute
        )
        .datePickerStyle(.compact)
        .frame(minHeight: PlanteriorControl.minimumTarget)
        .padding(.horizontal, PlanteriorSpacing.large)
        .padding(.vertical, PlanteriorSpacing.extraSmall)
        .disabled(!enabled)
        .accessibilityValue(timeValue(selection))
        .accessibilityIdentifier(id)
        .overlay(alignment: .trailing) {
            Image(systemName: "chevron.right")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textTertiary.color)
                .accessibilityHidden(true)
                .frame(
                    width: SettingsReferenceMetrics
                        .quietHoursDisclosureGlyphSize.width,
                    height: SettingsReferenceMetrics
                        .quietHoursDisclosureGlyphSize.height
                )
                .padding(.trailing, PlanteriorSpacing.large)
                .allowsHitTesting(false)
        }
        .frame(minHeight: SettingsReferenceMetrics.rootRowHeight)
    }

    private func timeValue(_ selection: Binding<Date>) -> String {
        QuietHoursPresentation.localTime(from: selection.wrappedValue)?
            .rawValue ?? ""
    }
}
