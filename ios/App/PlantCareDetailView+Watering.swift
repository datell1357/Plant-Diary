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
        case .recorded:
            "물 주기 완료를 기록했어요."
        case .alreadyRecorded:
            "오늘 물 주기는 이미 기록했어요."
        case .failed:
            "물 주기 완료를 기록하지 못했어요."
        case .unavailableDate:
            "현재 날짜를 확인하지 못했어요."
        }
    }

    var isFailure: Bool {
        switch self {
        case .recorded, .alreadyRecorded:
            false
        case .failed, .unavailableDate:
            true
        }
    }
}

extension PlantCareDetailView {
    var wateringButtonTitle: String {
        wateringFeedback?.title ?? "오늘 물 주기 완료"
    }

    var wateringButtonColor: Color {
        wateringFeedback?.isFailure == true
            ? PlanteriorPalette.textPrimary.color
            : PlanteriorPalette.accent.color
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
                Text("다음 물 주기 일정 없음")
                    .accessibilityIdentifier("watering.status")
            case let .overdue(nextDate):
                Text("다음 물 주기: \(nextDate.rawValue) · 관리가 지연됐어요")
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .bold()
                    .accessibilityIdentifier("watering.next-date")
            case let .due(nextDate):
                Text("다음 물 주기: \(nextDate.rawValue) · 오늘이에요")
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .accessibilityIdentifier("watering.next-date")
            case let .upcoming(nextDate):
                Text("다음 물 주기: \(nextDate.rawValue)")
                    .accessibilityIdentifier("watering.next-date")
            }
        } else {
            Text("다음 물 주기 일정 없음")
                .accessibilityIdentifier("watering.status")
        }
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
