@testable import Planterior
import UserNotifications
import XCTest

@MainActor
extension LocalNotificationScheduleStoreTests {
    func testAccountRemountRestoresPersistedIncomingSchedulesImmediately() async throws {
        let center = LocalNotificationCenterFake()
        let store = try makeStore(key: "account-restore", center: center)
        store.mount(accountID: "account-b")
        try store.reconcile(request(time: "08:30"))
        try await store.waitForPendingOperations()
        let persistedBIdentifiers = Set(center.requests.map(\.identifier))

        store.mount(accountID: "account-a")
        try store.reconcile(request(time: "09:00"))
        try await store.waitForPendingOperations()
        XCTAssertTrue(center.requests.allSatisfy {
            $0.identifier.hasPrefix(
                LocalNotificationScheduleStore.ownedPrefix(accountID: "account-a")
            )
        })

        store.mount(accountID: "account-b")
        try await store.waitForPendingOperations()

        XCTAssertEqual(Set(center.requests.map(\.identifier)), persistedBIdentifiers)
        XCTAssertTrue(center.requests.allSatisfy {
            guard let trigger = $0.trigger as? UNCalendarNotificationTrigger else {
                return false
            }
            return trigger.dateComponents.hour == 8
                && trigger.dateComponents.minute == 30
        })
    }
}
