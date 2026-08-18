import Foundation
import PlanteriorDomain

extension PlantCareDetailView {
    var calendarDate: CalendarDate? {
        lastWateredOn.flatMap(calendarDate)
    }

    var todayCalendarDate: CalendarDate? {
        #if DEBUG
            let date = ProcessInfo.processInfo.environment["QA_WATERING_TODAY"]
                .flatMap { try? CalendarDate.parse($0) }
            if let date {
                return date
            }
        #endif
        return try? plantCalendar.calendarDate(from: Date())
    }

    var todayDate: Date {
        guard let todayCalendarDate else {
            return Date()
        }
        return plantCalendar.date(from: todayCalendarDate) ?? Date()
    }

    func calendarDate(_ date: Date) -> CalendarDate? {
        try? plantCalendar.calendarDate(from: date)
    }

    func date(_ calendarDate: CalendarDate) -> Date? {
        plantCalendar.date(from: calendarDate)
    }
}
