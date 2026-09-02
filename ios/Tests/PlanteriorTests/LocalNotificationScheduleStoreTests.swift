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
        let prefix = "planterior.local.watering."
            + "fc164f8250803ea8d41834f1de85821035d27d3747e83610789e0f8e5313b9c3."
        XCTAssertEqual(
            Set(requests.map(\.identifier)),
            [
                "\(prefix)plant-a|2099-08-11|due",
                "\(prefix)plant-a|2099-08-12|next"
            ]
        )
        XCTAssertEqual(Set(requests.map(\.identifier)).count, 2)
        XCTAssertTrue(requests.allSatisfy {
            $0.content.userInfo["route"] as? String == "plant-care"
                && $0.content.userInfo["plantID"] as? String == "plant-a"
                && Set($0.content.userInfo.keys) == ["route", "plantID"]
                && $0.content.title == "물 주기 알림"
                && $0.content.body == "오늘 물 주기 일정이 있어요."
        })
        XCTAssertTrue(requests.allSatisfy {
            guard let trigger = $0.trigger as? UNCalendarNotificationTrigger else {
                return false
            }
            return trigger.dateComponents.timeZone == .current
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
            Self.request(identifier: LocalNotificationScheduleStore
                .ownedPrefix(accountID: "account-a") + "stale")
        ])
        let store = try makeStore(key: "owned", center: center)
        store.mount(accountID: "account-a")

        // When
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()

        // Then
        let identifiers = Set(center.requests.map(\.identifier))
        XCTAssertTrue(identifiers.contains("unrelated.weather"))
        XCTAssertFalse(identifiers.contains(
            LocalNotificationScheduleStore.ownedPrefix(accountID: "account-a")
                + "stale"
        ))
        XCTAssertEqual(identifiers.filter {
            $0.hasPrefix(LocalNotificationScheduleStore.ownedPrefix(accountID: "account-a"))
        }.count, 2)
    }

    func testUnchangedReconcilePerformsNoRemoveOrAddOperations() async throws {
        // Given
        let center = LocalNotificationCenterFake()
        let store = try makeStore(key: "diff", center: center)
        store.mount(accountID: "account-a")
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()
        center.resetOperations()

        // When
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()

        // Then
        XCTAssertTrue(center.removedIdentifiers.isEmpty)
        XCTAssertTrue(center.addedIdentifiers.isEmpty)
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
        XCTAssertTrue(accountAIdentifiers.allSatisfy {
            $0.hasPrefix(LocalNotificationScheduleStore.ownedPrefix(accountID: "account-a"))
        })
        XCTAssertTrue(accountBIdentifiers.allSatisfy {
            $0.hasPrefix(LocalNotificationScheduleStore.ownedPrefix(accountID: "account-b"))
        })
        XCTAssertTrue(accountAIdentifiers.isDisjoint(with: accountBIdentifiers))
    }

    func testLogoutRemovesPriorAccountRequestsAndPreservesUnrelatedRequests() async throws {
        let center = LocalNotificationCenterFake(requests: [
            Self.request(identifier: "unrelated.weather")
        ])
        let store = try makeStore(key: "logout", center: center)
        store.mount(accountID: "account-a")
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()

        store.mount(accountID: "account-b")
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()
        store.mount(accountID: nil)
        try await store.waitForPendingOperations()

        XCTAssertEqual(center.requests.map(\.identifier), ["unrelated.weather"])
        XCTAssertEqual(store.scheduledCount, 0)
    }

    func testScheduledCountIncludesOnlySuccessfullyPendingRequests() async throws {
        let prefix = LocalNotificationScheduleStore.ownedPrefix(accountID: "account-a")
        let center = LocalNotificationCenterFake(
            failingIdentifiers: ["\(prefix)plant-a|2099-08-12|next"]
        )
        let store = try makeStore(key: "partial-failure", center: center)
        store.mount(accountID: "account-a")

        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()

        XCTAssertEqual(center.requests.count, 1)
        XCTAssertEqual(store.scheduledCount, 1)
    }

    func testFailedReplacementDoesNotCountExistingSameIdentifierWithStalePayload() async throws {
        let prefix = LocalNotificationScheduleStore.ownedPrefix(accountID: "account-a")
        let identifier = "\(prefix)plant-a|2099-08-11|due"
        let center = LocalNotificationCenterFake(
            failingIdentifiers: [identifier]
        )
        let store = try makeStore(key: "failed-replacement", center: center)
        store.mount(accountID: "account-a")
        try await store.waitForPendingOperations()
        center.seed(Self.request(identifier: identifier))

        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()

        XCTAssertEqual(center.requests.count, 2)
        XCTAssertEqual(store.scheduledCount, 1)
        XCTAssertEqual(
            center.requests.first { $0.identifier == identifier }?.content.title,
            ""
        )
    }

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

@MainActor
final class LocalNotificationCenterFake: LocalNotificationCenterScheduling {
    private(set) var requests: [UNNotificationRequest]
    private(set) var addedIdentifiers: [String] = []
    private(set) var removedIdentifiers: [String] = []
    private let failingIdentifiers: Set<String>

    init(
        requests: [UNNotificationRequest] = [],
        failingIdentifiers: Set<String> = []
    ) {
        self.requests = requests
        self.failingIdentifiers = failingIdentifiers
    }

    func pendingRequests() async -> [UNNotificationRequest] {
        requests
    }

    func add(_ request: UNNotificationRequest) async throws {
        addedIdentifiers.append(request.identifier)
        guard !failingIdentifiers.contains(request.identifier) else {
            throw LocalNotificationCenterFakeError.rejected
        }
        requests.removeAll { $0.identifier == request.identifier }
        requests.append(request)
    }

    func removePendingRequests(withIdentifiers identifiers: [String]) async {
        removedIdentifiers.append(contentsOf: identifiers)
        requests.removeAll { identifiers.contains($0.identifier) }
    }

    func resetOperations() {
        addedIdentifiers = []
        removedIdentifiers = []
    }

    func seed(_ request: UNNotificationRequest) {
        requests.append(request)
    }
}

private enum LocalNotificationCenterFakeError: Error {
    case rejected
}
