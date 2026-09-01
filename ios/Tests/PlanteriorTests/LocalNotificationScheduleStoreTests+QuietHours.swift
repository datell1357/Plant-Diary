import Foundation
@testable import Planterior
import PlanteriorDomain
import XCTest

@MainActor
extension LocalNotificationScheduleStoreTests {
    func testQuietHoursRefreshRemovesAndRestoresOwnedRequests() async throws {
        // Given
        var quietHours = try QuietHoursPreference(
            enabled: false,
            start: LocalTime.parse("22:00"),
            end: LocalTime.parse("07:00")
        )
        let center = LocalNotificationCenterFake()
        let suiteName = "LocalNotificationScheduleStoreTests.quiet-refresh"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = LocalNotificationScheduleStore(
            defaults: defaults,
            key: "quiet-refresh",
            quietHours: { quietHours },
            notificationCenter: center
        )
        store.mount(accountID: "account-a")
        try store.reconcile(request(time: "22:30"))
        try await store.waitForPendingOperations()
        XCTAssertEqual(center.requests.count, 2)

        // When
        quietHours = try QuietHoursPreference(
            enabled: true,
            start: LocalTime.parse("22:00"),
            end: LocalTime.parse("07:00")
        )
        store.refreshDeliveryForCurrentAccount()
        try await store.waitForPendingOperations()

        // Then
        XCTAssertTrue(center.requests.isEmpty)

        // When
        quietHours = try QuietHoursPreference(
            enabled: false,
            start: LocalTime.parse("22:00"),
            end: LocalTime.parse("07:00")
        )
        store.refreshDeliveryForCurrentAccount()
        try await store.waitForPendingOperations()

        // Then
        XCTAssertEqual(center.requests.count, 2)
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
}
