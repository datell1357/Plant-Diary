import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

enum WateringFeedback {
    case recorded
    case alreadyRecorded
    case failed
    case unavailableDate

    var title: String {
        switch self {
        case .recorded: "물 주기 완료를 기록했어요."
        case .alreadyRecorded: "오늘 물 주기는 이미 기록했어요."
        case .failed: "물 주기 완료를 기록하지 못했어요."
        case .unavailableDate: "현재 날짜를 확인하지 못했어요."
        }
    }

    var isFailure: Bool {
        switch self {
        case .recorded, .alreadyRecorded: false
        case .failed, .unavailableDate: true
        }
    }
}

extension PlantCareDetailView {
    var wateringSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
            Text("물 주기 일정")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.medium) {
                    if let lastWateredOn {
                        DatePicker(
                            "마지막 물 주기",
                            selection: Binding(
                                get: { lastWateredOn },
                                set: { self.lastWateredOn = $0 }
                            ),
                            in: ...todayDate,
                            displayedComponents: .date
                        )
                        .accessibilityIdentifier("watering.last-date-picker")
                        Text("마지막 물 주기: \(calendarDate?.rawValue ?? "-")")
                            .font(PlanteriorTypography.caption)
                            .foregroundStyle(PlanteriorPalette.textSecondary.color)
                            .accessibilityIdentifier("watering.last-date")
                    } else {
                        Text("마지막 물 주기일을 설정하면 다음 일정을 계산해요.")
                            .font(PlanteriorTypography.supporting)
                            .foregroundStyle(PlanteriorPalette.textSecondary.color)
                            .accessibilityIdentifier("watering.missing-date")
                        PlanteriorSecondaryButton(
                            "마지막 물 주기 오늘로 설정",
                            action: setWateringBaselineToday
                        )
                        .accessibilityIdentifier("watering.set-today")
                    }
                    Stepper(
                        "물 주기 간격: \(wateringIntervalDays)일",
                        value: $wateringIntervalDays,
                        in: 1 ... 30
                    )
                    .frame(minHeight: PlanteriorControl.minimumTarget)
                    .accessibilityIdentifier("watering.interval")
                    wateringScheduleContent
                    PlanteriorPrimaryButton(
                        LocalizedStringKey(wateringButtonTitle),
                        action: recordWateredToday
                    )
                    .disabled(lastWateredOn == nil)
                    .opacity(lastWateredOn == nil ? 0.55 : 1)
                    .accessibilityIdentifier("watering.complete")
                    .accessibilityValue(wateringFeedback?.title ?? "기록 전")
                }
            }
        }
    }

    var wateringButtonTitle: String {
        wateringFeedback?.title ?? "오늘 물 주기 완료"
    }

    @ViewBuilder
    var wateringScheduleContent: some View {
        if let todayCalendarDate {
            switch collection.wateringStatus(
                at: index,
                lastWateredOn: calendarDate,
                today: todayCalendarDate,
                intervalDays: wateringIntervalDays
            ) {
            case .unavailable:
                scheduleText("다음 물 주기 일정 없음")
                    .accessibilityIdentifier("watering.status")
            case let .overdue(nextDate):
                scheduleText("다음 물 주기: \(nextDate.rawValue) · 관리가 지연됐어요")
                    .foregroundStyle(PlanteriorPalette.warning.color)
                    .accessibilityIdentifier("watering.next-date")
            case let .due(nextDate):
                scheduleText("다음 물 주기: \(nextDate.rawValue) · 오늘이에요")
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .accessibilityIdentifier("watering.next-date")
            case let .upcoming(nextDate):
                scheduleText("다음 물 주기: \(nextDate.rawValue)")
                    .accessibilityIdentifier("watering.next-date")
            }
        } else {
            scheduleText("다음 물 주기 일정 없음")
                .accessibilityIdentifier("watering.status")
        }
    }

    private func scheduleText(_ value: String) -> some View {
        Text(value)
            .font(PlanteriorTypography.cardTitle)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(PlanteriorSpacing.medium)
            .background(PlanteriorPalette.subtle.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
    }

    func recordWateredToday() {
        guard let todayCalendarDate else {
            wateringFeedback = .unavailableDate
            return
        }
        do {
            let result = try collection.recordWateredToday(
                at: index,
                today: todayCalendarDate,
                intervalDays: wateringIntervalDays
            )
            lastWateredOn = todayDate
            switch result {
            case .recorded:
                wateringFeedback = .recorded
            case .alreadyRecorded:
                wateringFeedback = .alreadyRecorded
            }
        } catch {
            wateringFeedback = .failed
        }
    }

    func setWateringBaselineToday() {
        guard let todayCalendarDate else {
            wateringFeedback = .unavailableDate
            return
        }
        do {
            try collection.setWateringBaseline(
                at: index,
                today: todayCalendarDate,
                intervalDays: wateringIntervalDays
            )
            lastWateredOn = todayDate
            wateringFeedback = nil
        } catch {
            wateringFeedback = .failed
        }
    }
}
