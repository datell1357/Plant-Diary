import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing
import UserNotifications

private struct LocalPlantCollectionCompletionContext {
    let defaults: UserDefaults
    let center: LocalNotificationCenterFake
    let schedules: LocalNotificationScheduleStore
    let store: LocalPlantCollectionStore
    let preference: NotificationPreference
    let plantID: PersonalPlantID
}

@MainActor
extension LocalPlantCollectionScheduleTests {
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
        let plantID = try PersonalPlantID.parse("local-0")
        store.plants = try [wateringDraft(lastWateredOn: CalendarDate.parse("2099-08-01"))]
        store.weatherPlantIDs = [plantID]
        let preference = try NotificationPreference(
            enabled: true,
            time: LocalTime.parse("09:00")
        )

        try await assertCompletionDoesNotRecreateNotifications(
            context: LocalPlantCollectionCompletionContext(
                defaults: defaults,
                center: center,
                schedules: schedules,
                store: store,
                preference: preference,
                plantID: plantID
            )
        )
    }

    private func assertCompletionDoesNotRecreateNotifications(
        context: LocalPlantCollectionCompletionContext
    ) async throws {
        let today = try CalendarDate.parse("2099-08-11")
        try context.schedules.reconcile(notificationRequest(
            preference: context.preference,
            plantID: context.plantID,
            dueDate: today
        ))
        _ = try context.store.recordWateredToday(at: 0, today: today, intervalDays: 10)
        context.store.mount(accountID: "account-a")
        #expect(context.store.completedPlantIDs == [context.plantID])
        let nextDueDate = try CalendarDate.parse("2099-08-21")
        try context.schedules.reconcile(notificationRequest(
            preference: context.preference,
            plantID: context.plantID,
            dueDate: nextDueDate,
            completedPlantIDs: context.store.completedPlantIDs
        ))
        try await context.schedules.waitForPendingOperations()
        #expect(context.schedules.scheduledCount == 0)

        context.store.mount(accountID: "account-b")
        context.schedules.mount(accountID: "account-b")
        #expect(context.store.completedPlantIDs.isEmpty)
        let remounted = collectionStore(
            defaults: context.defaults,
            schedules: context.schedules
        )
        remounted.mount(accountID: "account-a")
        context.schedules.mount(accountID: "account-a")
        #expect(remounted.completedPlantIDs.isEmpty)
        try context.schedules.reconcile(notificationRequest(
            preference: context.preference,
            plantID: context.plantID,
            dueDate: nextDueDate,
            completedPlantIDs: remounted.completedPlantIDs
        ))
        try await context.schedules.waitForPendingOperations()
        #expect(context.schedules.scheduledCount == 2)
        #expect(context.center.requests.count == 2)
    }
}
