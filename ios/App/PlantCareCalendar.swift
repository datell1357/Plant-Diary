import Foundation
import PlanteriorDomain

struct PlantCareCalendar {
    private let calendar: Calendar

    init(calendar userCalendar: Calendar = .current) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = userCalendar.timeZone
        self.calendar = calendar
    }

    func calendarDate(from date: Date) throws -> CalendarDate {
        let components = calendar.dateComponents(
            [.year, .month, .day],
            from: date
        )
        guard
            let year = components.year,
            let month = components.month,
            let day = components.day
        else {
            throw DomainValidationError.invalidCalendarDate
        }
        return try CalendarDate.parse(
            String(format: "%04d-%02d-%02d", year, month, day)
        )
    }

    func date(from calendarDate: CalendarDate) -> Date? {
        let components = calendarDate.rawValue
            .split(separator: "-")
            .compactMap { Int($0) }
        guard components.count == 3 else {
            return nil
        }
        return calendar.date(
            from: DateComponents(
                calendar: calendar,
                timeZone: calendar.timeZone,
                year: components[0],
                month: components[1],
                day: components[2]
            )
        )
    }
}
