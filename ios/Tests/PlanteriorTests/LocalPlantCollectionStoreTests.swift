import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct LocalPlantCollectionStoreTests {
    @Test
    func homeReconciliationDoesNotRecreateCompletedWateringNotifications() throws {
        let suiteName = "LocalPlantCollectionCompletionTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let schedules = LocalNotificationScheduleStore(defaults: defaults)
        let store = collectionStore(defaults: defaults, schedules: schedules)
        schedules.mount(accountID: "account-a")
        store.mount(accountID: "account-a")
        let baseline = try CalendarDate.parse("2026-08-01")
        store.plants = [wateringDraft(lastWateredOn: baseline)]
        let plantID = try PersonalPlantID.parse("local-0")
        let preference = try NotificationPreference(
            enabled: true,
            time: LocalTime.parse("09:00")
        )
        let today = try CalendarDate.parse("2026-08-11")

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
        let nextDueDate = try CalendarDate.parse("2026-08-21")
        try schedules.reconcile(notificationRequest(
            preference: preference,
            plantID: plantID,
            dueDate: nextDueDate,
            completedPlantIDs: store.completedPlantIDs
        ))
        #expect(schedules.scheduledCount == 0)

        store.mount(accountID: "account-b")
        #expect(store.completedPlantIDs.isEmpty)
        let remounted = collectionStore(defaults: defaults, schedules: schedules)
        remounted.mount(accountID: "account-a")
        try schedules.reconcile(notificationRequest(
            preference: preference,
            plantID: plantID,
            dueDate: nextDueDate,
            completedPlantIDs: remounted.completedPlantIDs
        ))
        #expect(schedules.scheduledCount == 2)
    }

    @Test
    func resolvesRouteTargetByExactIdentityAcrossReorderAndDeletion() throws {
        let suiteName = "LocalPlantCollectionStoreRoutesTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(
                defaults: defaults
            )
        )
        let first = try identifiedDraft(named: "첫 번째", id: "local-0")
        let second = try identifiedDraft(named: "두 번째", id: "local-1")

        store.plants = [second, first]

        #expect(store.index(forRouteTarget: "local-0") == 1)

        store.plants.remove(at: 1)

        #expect(store.index(forRouteTarget: "local-0") == nil)
        #expect(store.index(forRouteTarget: "local-1") == 0)
    }

    @Test
    func weatherPlantIdentitySurvivesEarlierPlantDeletion() throws {
        let suiteName = "LocalPlantCollectionStoreTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(
                defaults: defaults
            )
        )
        store.save(draft(named: "첫 번째"))
        store.save(draft(named: "두 번째"))
        let secondID = try #require(store.weatherPlantID(at: 1))
        let secondPresentationID = try #require(store.presentationIdentity(at: 1))
        #expect(secondPresentationID == secondID.rawValue)

        store.remove(at: 0)

        #expect(store.weatherPlantID(at: 0) == secondID)
        #expect(store.presentationIdentity(at: 0) == secondPresentationID)
        let restored = LocalPlantCollectionStore(
            defaults: defaults,
            notificationSchedules: LocalNotificationScheduleStore(
                defaults: defaults
            )
        )
        #expect(restored.weatherPlantID(at: 0) == secondID)
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
        lastWateredOn: CalendarDate
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

    private func draft(named name: String) -> PlantRegistrationDraft {
        PlantRegistrationDraft(
            plantID: nil,
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: nil,
            registrationMethod: .manual
        )
    }

    private func identifiedDraft(
        named name: String,
        id: String
    ) throws -> PlantRegistrationDraft {
        try PlantRegistrationDraft(
            plantID: PlantContentID.parse(id),
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: nil,
            registrationMethod: .manual
        )
    }
}
