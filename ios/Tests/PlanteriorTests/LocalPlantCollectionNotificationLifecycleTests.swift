import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing
import UserNotifications

@MainActor
struct LocalPlantCollectionScheduleTests {
    @Test
    func deletingPlantCancelsItsPendingRequestsAndPreservesUnrelatedRequests() async throws {
        let suiteName = "LocalPlantCollectionDeletionNotificationTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let unrelated = UNNotificationRequest(
            identifier: "unrelated.pending",
            content: UNMutableNotificationContent(),
            trigger: nil
        )
        let center = LocalNotificationCenterFake(requests: [unrelated])
        let quietHours = try QuietHoursPreference(
            enabled: false,
            start: LocalTime.parse("22:00"),
            end: LocalTime.parse("07:00")
        )
        let schedules = LocalNotificationScheduleStore(
            defaults: defaults,
            quietHours: { quietHours },
            notificationCenter: center
        )
        schedules.mount(accountID: "account-a")
        let store = collectionStore(defaults: defaults, schedules: schedules)
        let plantID = try PersonalPlantID.parse("local-0")
        store.plants = try [
            wateringDraft(lastWateredOn: CalendarDate.parse("2099-08-01"))
        ]
        store.weatherPlantIDs = [plantID]
        try schedules.reconcile(notificationRequest(
            preference: NotificationPreference(
                enabled: true,
                time: LocalTime.parse("09:00")
            ),
            plantID: plantID,
            dueDate: CalendarDate.parse("2099-08-11")
        ))
        try await schedules.waitForPendingOperations()
        #expect(center.requests.count == 3)

        store.remove(at: 0)
        try await schedules.waitForPendingOperations()

        #expect(center.requests.map(\.identifier) == ["unrelated.pending"])
    }

    @Test
    func homeReconciliationDoesNotRecreateCompletedWateringNotifications() async throws {
        let suiteName = "LocalPlantCollectionCompletionTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let center = LocalNotificationCenterFake()
        let quietHours = try QuietHoursPreference(
            enabled: false,
            start: LocalTime.parse("22:00"),
            end: LocalTime.parse("07:00")
        )
        let schedules = LocalNotificationScheduleStore(
            defaults: defaults,
            quietHours: { quietHours },
            notificationCenter: center
        )
        let store = collectionStore(defaults: defaults, schedules: schedules)
        schedules.mount(accountID: "account-a")
        store.mount(accountID: "account-a")
        let baseline = try CalendarDate.parse("2099-08-01")
        store.plants = [wateringDraft(lastWateredOn: baseline)]
        let plantID = try PersonalPlantID.parse("local-0")
        store.weatherPlantIDs = [plantID]
        let preference = try NotificationPreference(
            enabled: true,
            time: LocalTime.parse("09:00")
        )
        let today = try CalendarDate.parse("2099-08-11")

        try schedules.reconcile(notificationRequest(
            preference: preference,
            plantID: plantID,
            dueDate: today
        ))
        _ = try store.recordWateredToday(
            at: 0,
            today: today,
            intervalDays: 10
        )
        store.mount(accountID: "account-a")
        #expect(store.completedPlantIDs == [plantID])
        let nextDueDate = try CalendarDate.parse("2099-08-21")
        try schedules.reconcile(notificationRequest(
            preference: preference,
            plantID: plantID,
            dueDate: nextDueDate,
            completedPlantIDs: store.completedPlantIDs
        ))
        try await schedules.waitForPendingOperations()
        #expect(schedules.scheduledCount == 0)

        store.mount(accountID: "account-b")
        schedules.mount(accountID: "account-b")
        #expect(store.completedPlantIDs.isEmpty)
        let remounted = collectionStore(defaults: defaults, schedules: schedules)
        remounted.mount(accountID: "account-a")
        schedules.mount(accountID: "account-a")
        #expect(remounted.completedPlantIDs.isEmpty)
        try schedules.reconcile(notificationRequest(
            preference: preference,
            plantID: plantID,
            dueDate: nextDueDate,
            completedPlantIDs: remounted.completedPlantIDs
        ))
        try await schedules.waitForPendingOperations()
        #expect(schedules.scheduledCount == 2)
        #expect(center.requests.count == 2)
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
        dueDate: CalendarDate,
        completedPlantIDs: Set<PersonalPlantID> = []
    ) -> NotificationScheduleRequest {
        NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .registered,
            global: preference,
            perPlant: [:],
            dueDates: [plantID: dueDate],
            completedPlantIDs: completedPlantIDs,
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
