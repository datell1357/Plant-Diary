@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct WateringScheduleCoordinatorTests {
    @Test
    func calculatesDueStateAndRecordsTodayIdempotently() throws {
        let plantID = try PersonalPlantID.parse("plant-a")
        let today = try CalendarDate.parse("2026-08-11")
        let lastWateredDate = try CalendarDate.parse("2026-08-01")
        var coordinator = WateringScheduleCoordinator(today: today)

        try coordinator.setSchedule(
            plantID: plantID,
            lastWateredDate: lastWateredDate,
            intervalDays: 10
        )
        let dueDate = try CalendarDate.parse("2026-08-11")
        let nextDate = try CalendarDate.parse("2026-08-21")
        let initialStatus = coordinator.status(for: plantID)
        let firstResult = try coordinator.recordWateredToday(for: plantID)
        let secondResult = try coordinator.recordWateredToday(for: plantID)
        let updatedStatus = coordinator.status(for: plantID)

        #expect(initialStatus == .due(nextDate: dueDate))
        #expect(firstResult == .recorded(today))
        #expect(secondResult == .alreadyRecorded(today))
        #expect(updatedStatus == .upcoming(nextDate: nextDate))
    }

    @Test
    func reportsUnavailableWithoutLastWateredDate() throws {
        let plantID = try PersonalPlantID.parse("plant-a")
        let today = try CalendarDate.parse("2026-08-11")
        var coordinator = WateringScheduleCoordinator(today: today)

        #expect(coordinator.status(for: plantID) == .unavailable)
        #expect(throws: WateringScheduleError.scheduleUnavailable) {
            try coordinator.recordWateredToday(for: plantID)
        }
    }

    @Test
    func rejectsFutureLastWateredDate() throws {
        let plantID = try PersonalPlantID.parse("plant-a")
        let today = try CalendarDate.parse("2026-08-11")
        let futureDate = try CalendarDate.parse("2026-08-12")
        var coordinator = WateringScheduleCoordinator(today: today)

        #expect(throws: WateringScheduleError.futureLastWateredDate) {
            try coordinator.setSchedule(
                plantID: plantID,
                lastWateredDate: futureDate,
                intervalDays: 10
            )
        }
    }

    @Test
    func keepsPlantSchedulesIndependent() throws {
        let firstPlantID = try PersonalPlantID.parse("plant-a")
        let secondPlantID = try PersonalPlantID.parse("plant-b")
        let today = try CalendarDate.parse("2026-08-11")
        var coordinator = WateringScheduleCoordinator(today: today)

        try coordinator.setSchedule(
            plantID: firstPlantID,
            lastWateredDate: CalendarDate.parse("2026-08-01"),
            intervalDays: 10
        )
        try coordinator.setSchedule(
            plantID: secondPlantID,
            lastWateredDate: CalendarDate.parse("2026-08-10"),
            intervalDays: 5
        )
        _ = try coordinator.recordWateredToday(for: firstPlantID)
        let secondNextDate = try CalendarDate.parse("2026-08-15")

        #expect(
            coordinator.status(for: secondPlantID)
                == .upcoming(nextDate: secondNextDate)
        )
    }
}
