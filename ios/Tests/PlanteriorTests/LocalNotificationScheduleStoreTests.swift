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
}
