@testable import Planterior
import PlanteriorData
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
}
