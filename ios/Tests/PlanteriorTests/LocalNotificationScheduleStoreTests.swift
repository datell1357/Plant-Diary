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
}
