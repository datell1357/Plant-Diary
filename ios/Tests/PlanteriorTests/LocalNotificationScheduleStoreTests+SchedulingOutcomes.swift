@testable import Planterior
import PlanteriorData
import UserNotifications
import XCTest

@MainActor
extension LocalNotificationScheduleStoreTests {
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
        // Given
        let prefix = LocalNotificationScheduleStore.ownedPrefix(accountID: "account-a")
        let identifier = "\(prefix)plant-a|2099-08-11|due"
        let content = UNMutableNotificationContent()
        content.title = "Earlier reminder"
        let staleRequest = UNNotificationRequest(
            identifier: identifier,
            content: content,
            trigger: UNCalendarNotificationTrigger(
                dateMatching: DateComponents(hour: 8, minute: 30),
                repeats: false
            )
        )
        let center = LocalNotificationCenterFake(
            requests: [Self.request(identifier: "unrelated.weather")],
            failingIdentifiers: [identifier]
        )
        let store = try makeStore(key: "failed-replacement", center: center)
        store.mount(accountID: "account-a")
        try await store.waitForPendingOperations()
        center.seed(staleRequest)

        // When
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()

        // Then
        XCTAssertFalse(center.requests.contains { $0.identifier == identifier })
        XCTAssertEqual(
            Set(center.requests.map(\.identifier)),
            [
                "unrelated.weather",
                "\(prefix)plant-a|2099-08-12|next"
            ]
        )
        XCTAssertEqual(store.scheduledCount, 1)
    }
}
