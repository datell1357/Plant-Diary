import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import UserNotifications
import XCTest

@MainActor
extension LocalNotificationScheduleStoreTests {
    func makeStore(
        key: String,
        quietHours: QuietHoursPreference? = nil,
        center: LocalNotificationCenterFake = LocalNotificationCenterFake()
    ) throws -> LocalNotificationScheduleStore {
        let suiteName = "LocalNotificationScheduleStoreTests.\(key)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let effectiveQuietHours = try quietHours ?? QuietHoursPreference(
            enabled: false,
            start: LocalTime.parse("22:00"),
            end: LocalTime.parse("07:00")
        )
        return LocalNotificationScheduleStore(
            defaults: defaults,
            key: key,
            quietHours: { effectiveQuietHours },
            notificationCenter: center
        )
    }

    func request(
        time: String,
        authorization: NotificationAuthorizationState = .authorized,
        endpoint: NotificationEndpointState = .registered
    ) throws -> NotificationScheduleRequest {
        try NotificationScheduleRequest(
            authorization: authorization,
            endpoint: endpoint,
            global: NotificationPreference(
                enabled: true,
                time: LocalTime.parse(time)
            ),
            perPlant: [:],
            dueDates: [
                PersonalPlantID.parse("plant-a"):
                    CalendarDate.parse("2099-08-11")
            ],
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        )
    }

    static func request(identifier: String) -> UNNotificationRequest {
        UNNotificationRequest(
            identifier: identifier,
            content: UNMutableNotificationContent(),
            trigger: nil
        )
    }
}
