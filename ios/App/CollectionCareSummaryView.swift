import Foundation
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct CollectionCareSummaryView: View {
    @ObservedObject private var collection = LocalPlantCollectionStore.shared
    private let calendar = PlantCareCalendar()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
                Text("등록 식물 \(summary.total)개")
                    .font(PlanteriorTypography.pageTitle)
                    .accessibilityIdentifier("collection.summary.total")
                Text("저장된 물 주기 일정에서 계산한 오늘의 돌봄 현황이에요.")
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                summaryRow(
                    title: "오늘 돌봄 \(summary.dueToday)개",
                    detail: "오늘 물 주기가 예정된 식물",
                    icon: "drop.fill",
                    identifier: "collection.summary.due"
                )
                summaryRow(
                    title: "지연 \(summary.overdue)개",
                    detail: "예정일이 지난 식물",
                    icon: "exclamationmark.triangle.fill",
                    identifier: "collection.summary.overdue"
                )
                summaryRow(
                    title: "예정 \(summary.upcoming)개",
                    detail: "다음 물 주기일을 기다리는 식물",
                    icon: "calendar",
                    identifier: "collection.summary.upcoming"
                )
                summaryRow(
                    title: "설정 필요 \(summary.unconfigured)개",
                    detail: "물 주기 기준이 아직 없는 식물",
                    icon: "slider.horizontal.3",
                    identifier: "collection.summary.unconfigured"
                )
            }
            .padding(PlanteriorSpacing.large)
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("돌봄 요약")
        .navigationBarTitleDisplayMode(.inline)
        .planteriorInlineNavigationChrome()
        .accessibilityIdentifier("collection.summary.screen")
    }

    private func summaryRow(
        title: String,
        detail: String,
        icon: String,
        identifier: String
    ) -> some View {
        PlanteriorCard {
            HStack(alignment: .top, spacing: PlanteriorSpacing.medium) {
                PlanteriorIconWell(systemImage: icon)
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text(title)
                        .font(PlanteriorTypography.cardTitle)
                        .accessibilityIdentifier(identifier)
                    Text(detail)
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
                Spacer(minLength: 0)
            }
        }
    }

    private var summary: CollectionCareSummary {
        guard let today else {
            return CollectionCareSummary(
                total: collection.plants.count,
                overdue: 0,
                dueToday: 0,
                upcoming: 0,
                unconfigured: collection.plants.count
            )
        }
        return collection.careSummary(today: today)
    }

    private var today: CalendarDate? {
        #if DEBUG
            if let value = ProcessInfo.processInfo.environment["QA_WATERING_TODAY"] {
                if let date = try? CalendarDate.parse(value) {
                    return date
                }
            }
        #endif
        return try? calendar.calendarDate(from: Date())
    }
}
