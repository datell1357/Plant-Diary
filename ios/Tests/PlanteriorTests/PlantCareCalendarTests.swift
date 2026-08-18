import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

struct PlantCareCalendarTests {
    @Test
    func resolvesTodayInTheUsersCalendarTimeZone() throws {
        let timeZone = try #require(TimeZone(identifier: "Asia/Seoul"))
        let instant = try #require(
            ISO8601DateFormatter().date(from: "2026-08-10T15:30:00Z")
        )
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let subject = PlantCareCalendar(calendar: calendar)

        let date = try subject.calendarDate(from: instant)
        let expected = try CalendarDate.parse("2026-08-11")

        #expect(date == expected)
    }
}
