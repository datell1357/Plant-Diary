@testable import PlanteriorData
import PlanteriorDomain
import Testing

extension NotificationCoordinatorTests {
    @Test
    func localPlannerSkipsPastTriggersOrdersEarliestFirstAndCapsPendingLimit() throws {
        // Given
        let coordinator = NotificationCoordinator()
        let time = try LocalTime.parse("09:00")
        var dueDates: [PersonalPlantID: CalendarDate] = try [
            PersonalPlantID.parse("past"):
                CalendarDate.parse("2020-01-01")
        ]
        for index in 0 ..< 61 {
            let plantID = try PersonalPlantID.parse(
                String(format: "future-%02d", 60 - index)
            )
            dueDates[plantID] = try CalendarDate.parse(
                String(format: "2099-03-%02d", (index % 28) + 1)
            )
        }
        let request = NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .unavailable,
            global: NotificationPreference(enabled: true, time: time),
            perPlant: [:],
            dueDates: dueDates,
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        )

        // When
        let schedules = try coordinator.localSchedules(request)

        // Then
        #expect(schedules.count == 60)
        #expect(schedules.allSatisfy { $0.plantID.rawValue != "past" })
        #expect(zip(schedules, schedules.dropFirst()).allSatisfy { lhs, rhs in
            lhs.date.rawValue < rhs.date.rawValue
                || (lhs.date == rhs.date && lhs.time.rawValue <= rhs.time.rawValue)
        })
    }

    @Test
    func resolvesNotificationRoutesWithoutLeakingDeletedTargets() throws {
        let plantID = try PersonalPlantID.parse("plant-a")
        let router = NotificationRouter()

        #expect(
            router.resolve(
                plantID: plantID,
                authenticated: false,
                targetAvailable: true
            ) == .requiresAuthentication
        )
        #expect(
            router.resolve(
                plantID: plantID,
                authenticated: true,
                targetAvailable: false
            ) == .unavailable
        )
        #expect(
            router.resolve(
                plantID: plantID,
                authenticated: true,
                targetAvailable: true
            ) == .plant(plantID)
        )
    }
}
