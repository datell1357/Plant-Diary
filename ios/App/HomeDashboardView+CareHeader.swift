import PlanteriorDesignSystem
import SwiftUI

extension HomeDashboardView {
    var careHeader: some View {
        HStack(alignment: .firstTextBaseline, spacing: PlanteriorSpacing.small) {
            Text("오늘의 식물 관리")
                .font(PlanteriorTypography.sectionTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityIdentifier("home.care.header")
            Text(careBadgeText)
                .font(PlanteriorTypography.microLabel)
                .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                .padding(.horizontal, PlanteriorSpacing.small)
                .padding(.vertical, PlanteriorSpacing.extraSmall)
                .background(PlanteriorPalette.accent.color)
                .clipShape(Capsule())
                .accessibilityIdentifier("home.care.badge")
            Spacer(minLength: PlanteriorSpacing.small)
            if showsCareScheduleAction {
                Button(action: requestMiniHomeOpen) {
                    Text("일정 더보기")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.accent.color)
                        .frame(minHeight: PlanteriorControl.minimumTarget)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.care.more")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isHeader)
    }
}
