import Foundation
import PlanteriorDomain

public enum WateringScheduleStatus: Equatable, Sendable {
    case unavailable
    case overdue(nextDate: CalendarDate)
    case due(nextDate: CalendarDate)
    case upcoming(nextDate: CalendarDate)
}

public enum WateringCompletionResult: Equatable, Sendable {
    case recorded(CalendarDate)
    case alreadyRecorded(CalendarDate)
}

public enum WateringScheduleError: Error, Equatable, Sendable {
    case futureLastWateredDate
    case invalidInterval
    case scheduleUnavailable
}

public struct WateringScheduleCoordinator: Sendable {
    private struct Schedule: Sendable {
        var lastWateredDate: CalendarDate
        let intervalDays: Int
    }

    private let today: CalendarDate
    private var schedules: [PersonalPlantID: Schedule] = [:]

    public init(today: CalendarDate) {
        self.today = today
    }

    public mutating func setSchedule(
        plantID: PersonalPlantID,
        lastWateredDate: CalendarDate,
        intervalDays: Int
    ) throws {
        guard lastWateredDate.rawValue <= today.rawValue else {
            throw WateringScheduleError.futureLastWateredDate
        }
        guard intervalDays > 0 else {
            throw WateringScheduleError.invalidInterval
        }
        schedules[plantID] = Schedule(
            lastWateredDate: lastWateredDate,
            intervalDays: intervalDays
        )
    }

    public func status(for plantID: PersonalPlantID) -> WateringScheduleStatus {
        guard
            let schedule = schedules[plantID],
            let nextDate = try? addingDays(
                schedule.intervalDays,
                to: schedule.lastWateredDate
            )
        else {
            return .unavailable
        }
        if nextDate.rawValue < today.rawValue {
            return .overdue(nextDate: nextDate)
        }
        if nextDate == today {
            return .due(nextDate: nextDate)
        }
        return .upcoming(nextDate: nextDate)
    }

    public mutating func recordWateredToday(
        for plantID: PersonalPlantID
    ) throws -> WateringCompletionResult {
        guard var schedule = schedules[plantID] else {
            throw WateringScheduleError.scheduleUnavailable
        }
        guard schedule.lastWateredDate != today else {
            return .alreadyRecorded(today)
        }
        schedule.lastWateredDate = today
        schedules[plantID] = schedule
        return .recorded(today)
    }

    private func addingDays(
        _ days: Int,
        to calendarDate: CalendarDate
    ) throws -> CalendarDate {
        let components = calendarDate.rawValue
            .split(separator: "-")
            .compactMap { Int($0) }
        guard components.count == 3 else {
            throw DomainValidationError.invalidCalendarDate
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        let dateComponents = DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: components[0],
            month: components[1],
            day: components[2]
        )
        guard
            let date = calendar.date(from: dateComponents),
            let nextDate = calendar.date(byAdding: .day, value: days, to: date)
        else {
            throw DomainValidationError.invalidCalendarDate
        }
        let nextComponents = calendar.dateComponents(
            [.year, .month, .day],
            from: nextDate
        )
        guard
            let year = nextComponents.year,
            let month = nextComponents.month,
            let day = nextComponents.day
        else {
            throw DomainValidationError.invalidCalendarDate
        }
        return try CalendarDate.parse(
            String(format: "%04d-%02d-%02d", year, month, day)
        )
    }
}
