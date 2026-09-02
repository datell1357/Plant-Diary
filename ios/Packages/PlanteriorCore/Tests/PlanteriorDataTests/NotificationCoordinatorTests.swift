@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct NotificationCoordinatorTests {
    @Test
    func appliesOverrideAndEmitsDueAndNextDayOnce() throws {
        let dueDate = try CalendarDate.parse("2026-08-11")
        let defaultTime = try LocalTime.parse("09:00")
        let overrideTime = try LocalTime.parse("08:30")
        let enabledPlant = try PersonalPlantID.parse("enabled")
        let disabledPlant = try PersonalPlantID.parse("disabled")
        let coordinator = NotificationCoordinator()
        let schedules = try coordinator.schedules(NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .registered,
            global: NotificationPreference(
                enabled: true,
                time: defaultTime
            ),
            perPlant: [
                enabledPlant: PlantNotificationOverride(
                    enabled: true,
                    time: overrideTime
                ),
                disabledPlant: PlantNotificationOverride(
                    enabled: false,
                    time: nil
                )
            ],
            dueDates: [
                enabledPlant: dueDate,
                disabledPlant: dueDate
            ],
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        ))

        #expect(schedules.count == 2)
        #expect(schedules.map(\.kind) == [.dueDay, .nextDay])
        #expect(schedules.allSatisfy { $0.time == overrideTime })
        #expect(schedules.map(\.date.rawValue) == ["2026-08-11", "2026-08-12"])
        let deduplicated = try coordinator.schedules(NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .registered,
            global: NotificationPreference(enabled: true, time: defaultTime),
            perPlant: [:],
            dueDates: [enabledPlant: dueDate],
            completedPlantIDs: [],
            existingDeduplicationKeys: Set(schedules.map(\.deduplicationKey))
        ))
        #expect(deduplicated.isEmpty)
    }

    @Test
    func denialAndCompletionProduceNoSchedule() throws {
        let plantID = try PersonalPlantID.parse("plant-a")
        let dueDate = try CalendarDate.parse("2026-08-11")
        let time = try LocalTime.parse("09:00")
        let coordinator = NotificationCoordinator()

        let denied = try coordinator.schedules(NotificationScheduleRequest(
            authorization: .denied,
            endpoint: .registered,
            global: NotificationPreference(enabled: true, time: time),
            perPlant: [:],
            dueDates: [plantID: dueDate],
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        ))
        let endpointUnavailable = try coordinator.schedules(
            NotificationScheduleRequest(
                authorization: .authorized,
                endpoint: .unavailable,
                global: NotificationPreference(enabled: true, time: time),
                perPlant: [:],
                dueDates: [plantID: dueDate],
                completedPlantIDs: [],
                existingDeduplicationKeys: []
            )
        )
        let completed = try coordinator.schedules(NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .registered,
            global: NotificationPreference(enabled: true, time: time),
            perPlant: [:],
            dueDates: [plantID: dueDate],
            completedPlantIDs: [plantID],
            existingDeduplicationKeys: []
        ))

        #expect(denied.isEmpty)
        #expect(endpointUnavailable.isEmpty)
        #expect(completed.isEmpty)
    }

    @Test
    func localSchedulingRequiresAuthorizationButNotServerRegistration() throws {
        let plantID = try PersonalPlantID.parse("plant-a")
        let dueDate = try CalendarDate.parse("2099-08-11")
        let time = try LocalTime.parse("09:00")
        let coordinator = NotificationCoordinator()
        let request = NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .unavailable,
            global: NotificationPreference(enabled: true, time: time),
            perPlant: [:],
            dueDates: [plantID: dueDate],
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        )

        let local = try coordinator.localSchedules(request)
        let server = try coordinator.schedules(request)

        #expect(local.map(\.kind) == [.dueDay, .nextDay])
        #expect(server.isEmpty)
    }

    @Test
    func perPlantEnabledOverrideSupersedesDisabledGlobalDefault() throws {
        let plantID = try PersonalPlantID.parse("plant-a")
        let dueDate = try CalendarDate.parse("2026-08-11")
        let time = try LocalTime.parse("09:00")
        let coordinator = NotificationCoordinator()

        let schedules = try coordinator.schedules(NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .registered,
            global: NotificationPreference(enabled: false, time: time),
            perPlant: [
                plantID: PlantNotificationOverride(
                    enabled: true,
                    time: nil
                )
            ],
            dueDates: [plantID: dueDate],
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        ))

        #expect(schedules.map(\.kind) == [.dueDay, .nextDay])
    }

    @Test
    func localPlannerSkipsPastTriggersOrdersEarliestFirstAndCapsPendingLimit() throws {
        // Given
        let coordinator = NotificationCoordinator()
        let time = try LocalTime.parse("09:00")
        var dueDates: [PersonalPlantID: CalendarDate] = [
            try PersonalPlantID.parse("past"):
                try CalendarDate.parse("2020-01-01")
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
