import Foundation
@testable import Planterior
import PlanteriorDomain
import XCTest

@MainActor
extension LocalNotificationScheduleStoreTests {
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
        try store.cancel(for: PersonalPlantID.parse("plant-a"))
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

    func testMalformedPersistedScheduleIsIgnoredWithoutRemovingUnrelatedRequests() async throws {
        // Given
        let suiteName = "LocalNotificationScheduleStoreTests.malformed"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let key = "notifications.account-a.scheduled"
        defaults.set(
            Data(
                """
                [{"plantID":"plant-a","date":"invalid","time":"09:00",\
                "kind":"due","deduplicationKey":"invalid-date"}]
                """.utf8
            ),
            forKey: key
        )
        let center = LocalNotificationCenterFake(requests: [
            Self.request(identifier: "unrelated.pending"),
            Self.request(identifier: "planterior.watering.account-a.stale")
        ])
        let store = LocalNotificationScheduleStore(
            defaults: defaults,
            notificationCenter: center
        )
        store.mount(accountID: "account-a")

        // When
        store.refreshDeliveryForCurrentAccount()
        try await store.waitForPendingOperations()

        // Then
        XCTAssertEqual(center.requests.map(\.identifier), ["unrelated.pending"])
    }
}
