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

    func collectionStore(
        defaults: UserDefaults,
        schedules: LocalNotificationScheduleStore
    ) -> LocalPlantCollectionStore {
        LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: schedules
        )
    }

    func notificationRequest(
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

    func wateringDraft(
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
