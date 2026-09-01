import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct LocalPlantCollectionWateringTests {
    @Test
    func undoWateringCompletionRestoresPriorDateAndNotificationSchedule() async throws {
        let suiteName = "LocalPlantCollectionUndoTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let center = LocalNotificationCenterFake()
        let schedules = LocalNotificationScheduleStore(
            defaults: defaults,
            notificationCenter: center
        )
        schedules.mount(accountID: "account-a")
        let store = collectionStore(defaults: defaults, schedules: schedules)
        let baseline = try CalendarDate.parse("2026-08-01")
        let today = try CalendarDate.parse("2026-08-11")
        let plantID = try PersonalPlantID.parse("local-0")
        store.plants = [wateringDraft(lastWateredOn: baseline)]
        store.weatherPlantIDs = [plantID]
        let preference = try NotificationPreference(
            enabled: true,
            time: LocalTime.parse("09:00")
        )
        try schedules.reconcile(notificationRequest(
            preference: preference,
            plantID: plantID,
            dueDate: today
        ))
        try await schedules.waitForPendingOperations()
        #expect(center.requests.count == 2)
        _ = try store.recordWateredToday(at: 0, today: today, intervalDays: 10)
        try await schedules.waitForPendingOperations()
        #expect(store.completedPlantIDs == [plantID])
        #expect(schedules.scheduledCount == 0)
        #expect(center.requests.isEmpty)

        try store.undoWateredToday(
            at: 0,
            restoringLastWateredOn: baseline,
            restoringIntervalDays: 10,
            today: today,
            notificationState: NotificationRuntimeState(
                authorization: .authorized,
                endpoint: .registered
            )
        )
        try await schedules.waitForPendingOperations()

        #expect(store.plants[0].lastWateredOn == baseline)
        #expect(store.completedPlantIDs.isEmpty)
        #expect(schedules.scheduledCount == 2)
        #expect(center.requests.count == 2)
        let restored = collectionStore(defaults: defaults, schedules: schedules)
        #expect(restored.plants[0].lastWateredOn == baseline)
    }

    @Test
    func undoFirstWateringRestoresMissingBaseline() throws {
        let suiteName = "LocalPlantCollectionFirstUndoTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let schedules = LocalNotificationScheduleStore(defaults: defaults)
        let store = collectionStore(defaults: defaults, schedules: schedules)
        let today = try CalendarDate.parse("2026-08-11")
        let plantID = try PersonalPlantID.parse("local-0")
        store.plants = [wateringDraft(lastWateredOn: nil)]
        store.weatherPlantIDs = [plantID]
        _ = try store.recordWateredToday(at: 0, today: today, intervalDays: 10)

        try store.undoWateredToday(
            at: 0,
            restoringLastWateredOn: nil,
            restoringIntervalDays: 10,
            today: today,
            notificationState: NotificationRuntimeState(
                authorization: .authorized,
                endpoint: .registered
            )
        )

        #expect(store.plants[0].lastWateredOn == nil)
        #expect(store.completedPlantIDs.isEmpty)
        #expect(schedules.scheduledCount == 0)
    }

    private func collectionStore(
        defaults: UserDefaults,
        schedules: LocalNotificationScheduleStore
    ) -> LocalPlantCollectionStore {
        LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: schedules
        )
    }

    private func notificationRequest(
        preference: NotificationPreference,
        plantID: PersonalPlantID,
        dueDate: CalendarDate
    ) -> NotificationScheduleRequest {
        NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .registered,
            global: preference,
            perPlant: [:],
            dueDates: [plantID: dueDate],
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        )
    }

    private func wateringDraft(
        lastWateredOn: CalendarDate?
    ) -> PlantRegistrationDraft {
        PlantRegistrationDraft(
            plantID: nil,
            displayName: "몬스테라",
            representativePhoto: nil,
            lastWateredOn: lastWateredOn,
            wateringIntervalDays: 10,
            registrationMethod: .manual
        )
    }
}
