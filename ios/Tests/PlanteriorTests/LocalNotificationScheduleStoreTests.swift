import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import XCTest

@MainActor
final class LocalNotificationScheduleStoreTests: XCTestCase {
    func testReconcileIsIdempotent() throws {
        let suiteName = "LocalNotificationScheduleStoreTests"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = LocalNotificationScheduleStore(
            defaults: defaults,
            key: "scheduled"
        )
        let plantID = try PersonalPlantID.parse("plant-a")
        let dueDate = try CalendarDate.parse("2026-08-11")
        let time = try LocalTime.parse("09:00")
        let request = NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .registered,
            global: NotificationPreference(enabled: true, time: time),
            perPlant: [:],
            dueDates: [plantID: dueDate],
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        )

        try store.reconcile(request)
        try store.reconcile(request)

        let scheduledCount = store.scheduledCount
        XCTAssertEqual(scheduledCount, 2)
    }

    func testEnabledOvernightQuietHoursSuppressDeliveryInsideInterval() throws {
        let quietHours = try QuietHoursPreference(
            enabled: true,
            start: LocalTime.parse("22:00"),
            end: LocalTime.parse("07:00")
        )
        let store = try makeStore(
            key: "overnight",
            quietHours: quietHours
        )

        try store.reconcile(request(time: "22:00"))
        XCTAssertEqual(store.scheduledCount, 0)
        try store.reconcile(request(time: "06:59"))
        XCTAssertEqual(store.scheduledCount, 0)
        try store.reconcile(request(time: "07:00"))
        XCTAssertEqual(store.scheduledCount, 2)
    }

    func testDisabledQuietHoursDoNotSuppressDelivery() throws {
        let quietHours = try QuietHoursPreference(
            enabled: false,
            start: LocalTime.parse("22:00"),
            end: LocalTime.parse("07:00")
        )
        let store = try makeStore(key: "disabled", quietHours: quietHours)

        try store.reconcile(request(time: "23:00"))

        XCTAssertEqual(store.scheduledCount, 2)
    }

    private func makeStore(
        key: String,
        quietHours: QuietHoursPreference
    ) throws -> LocalNotificationScheduleStore {
        let suiteName = "LocalNotificationScheduleStoreTests.\(key)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        return LocalNotificationScheduleStore(
            defaults: defaults,
            key: key,
            quietHours: { quietHours }
        )
    }

    private func request(time: String) throws -> NotificationScheduleRequest {
        try NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: .registered,
            global: NotificationPreference(
                enabled: true,
                time: LocalTime.parse(time)
            ),
            perPlant: [:],
            dueDates: [
                PersonalPlantID.parse("plant-a"):
                    CalendarDate.parse("2026-08-11")
            ],
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        )
    }
}
