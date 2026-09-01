import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import UserNotifications
import XCTest

@MainActor
final class LocalNotificationScheduleStoreTests: XCTestCase {
    func testAuthorizedReconcileSchedulesStableAccountScopedRequests() async throws {
        // Given
        let center = LocalNotificationCenterFake()
        let store = try makeStore(key: "authorized", center: center)
        store.mount(accountID: "account-a")

        // When
        try store.reconcile(request(time: "09:00", endpoint: .unavailable))
        try await store.waitForPendingOperations()

        // Then
        let requests = center.requests
        XCTAssertEqual(requests.count, 2)
        XCTAssertTrue(requests.allSatisfy { $0.identifier.contains("account-a") })
        XCTAssertEqual(Set(requests.map(\.identifier)).count, 2)
        XCTAssertTrue(requests.allSatisfy {
            $0.content.userInfo["route"] as? String == "plant-care"
                && $0.content.userInfo["accountID"] as? String == "account-a"
                && $0.content.userInfo["plantID"] as? String == "plant-a"
                && $0.content.userInfo["care"] as? String == "watering"
        })
    }

    func testDeniedAndNotDeterminedSuppressScheduling() async throws {
        for (index, authorization) in [
            NotificationAuthorizationState.denied,
            .notDetermined
        ].enumerated() {
            // Given
            let center = LocalNotificationCenterFake()
            let store = try makeStore(key: "suppressed-\(index)", center: center)
            store.mount(accountID: "account-a")

            // When
            try store.reconcile(request(
                time: "09:00",
                authorization: authorization
            ))
            try await store.waitForPendingOperations()

            // Then
            XCTAssertTrue(center.requests.isEmpty)
        }
    }

    func testReconcileUpdatesRequestsWithoutChangingStableIdentifiers() async throws {
        // Given
        let center = LocalNotificationCenterFake()
        let store = try makeStore(key: "update", center: center)
        store.mount(accountID: "account-a")
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()
        let originalIdentifiers = Set(center.requests.map(\.identifier))

        // When
        try store.reconcile(request(time: "08:30"))
        try await store.waitForPendingOperations()

        // Then
        XCTAssertEqual(Set(center.requests.map(\.identifier)), originalIdentifiers)
        XCTAssertTrue(center.requests.allSatisfy {
            guard let trigger = $0.trigger as? UNCalendarNotificationTrigger else {
                return false
            }
            return trigger.dateComponents.hour == 8
                && trigger.dateComponents.minute == 30
        })
    }

    func testReconcileRemovesOnlyStaleOwnedRequests() async throws {
        // Given
        let center = LocalNotificationCenterFake(requests: [
            Self.request(identifier: "unrelated.weather"),
            Self.request(identifier: "planterior.watering.account-a.stale")
        ])
        let store = try makeStore(key: "owned", center: center)
        store.mount(accountID: "account-a")

        // When
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()

        // Then
        let identifiers = Set(center.requests.map(\.identifier))
        XCTAssertTrue(identifiers.contains("unrelated.weather"))
        XCTAssertFalse(identifiers.contains("planterior.watering.account-a.stale"))
        XCTAssertEqual(identifiers.filter { $0.contains("planterior.watering.account-a") }.count, 2)
    }

    func testAccountRemountRemovesPriorAccountWithoutCollidingWithNewAccount() async throws {
        // Given
        let center = LocalNotificationCenterFake()
        let store = try makeStore(key: "accounts", center: center)
        store.mount(accountID: "account-a")
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()
        let accountAIdentifiers = Set(center.requests.map(\.identifier))

        // When
        store.mount(accountID: "account-b")
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()

        // Then
        let accountBIdentifiers = Set(center.requests.map(\.identifier))
        XCTAssertTrue(accountAIdentifiers.allSatisfy { $0.contains("account-a") })
        XCTAssertTrue(accountBIdentifiers.allSatisfy { $0.contains("account-b") })
        XCTAssertTrue(accountAIdentifiers.isDisjoint(with: accountBIdentifiers))
    }

    func testDisableAndPlantCancellationRemoveOwnedRequestsOnly() async throws {
        // Given
        let center = LocalNotificationCenterFake(requests: [
            Self.request(identifier: "unrelated.pending")
        ])
        let store = try makeStore(key: "cancel", center: center)
        store.mount(accountID: "account-a")
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()

        // When
        store.cancel(for: try PersonalPlantID.parse("plant-a"))
        try await store.waitForPendingOperations()

        // Then
        XCTAssertEqual(center.requests.map(\.identifier), ["unrelated.pending"])

        // When
        try store.reconcile(request(time: "09:00"))
        store.suspendDeliveryForCurrentAccount()
        try await store.waitForPendingOperations()

        // Then
        XCTAssertEqual(center.requests.map(\.identifier), ["unrelated.pending"])
    }

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

    private func makeStore(
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

    private func request(
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
                    CalendarDate.parse("2026-08-11")
            ],
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        )
    }

    private static func request(identifier: String) -> UNNotificationRequest {
        UNNotificationRequest(
            identifier: identifier,
            content: UNMutableNotificationContent(),
            trigger: nil
        )
    }
}

@MainActor
final class LocalNotificationCenterFake: LocalNotificationCenterScheduling {
    private(set) var requests: [UNNotificationRequest]

    init(requests: [UNNotificationRequest] = []) {
        self.requests = requests
    }

    func pendingRequests() async -> [UNNotificationRequest] {
        requests
    }

    func add(_ request: UNNotificationRequest) async throws {
        requests.removeAll { $0.identifier == request.identifier }
        requests.append(request)
    }

    func removePendingRequests(withIdentifiers identifiers: [String]) async {
        requests.removeAll { identifiers.contains($0.identifier) }
    }
}
