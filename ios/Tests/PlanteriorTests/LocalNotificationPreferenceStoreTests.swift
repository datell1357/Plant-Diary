import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct LocalNotificationPreferenceStoreTests {
    @Test
    func persistsGlobalDefaultAndPerPlantOverride() throws {
        let suiteName = "LocalNotificationPreferenceStoreTests"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let plantID = try PersonalPlantID.parse("plant-a")
        let globalTime = try LocalTime.parse("10:00")
        let overrideTime = try LocalTime.parse("08:30")
        let store = LocalNotificationPreferenceStore(
            defaults: defaults,
            key: "preferences"
        )

        store.setGlobal(enabled: false, time: globalTime)
        store.setOverride(
            plantID: plantID,
            enabled: true,
            time: overrideTime
        )
        let restored = LocalNotificationPreferenceStore(
            defaults: defaults,
            key: "preferences"
        )
        let restoredGlobal = try #require(restored.global)

        #expect(!restoredGlobal.enabled)
        #expect(restoredGlobal.time == globalTime)
        #expect(restored.overrides[plantID]?.enabled == true)
        #expect(restored.overrides[plantID]?.time == overrideTime)
    }

    @Test
    func persistsQuietHoursPerAccountAndHandlesOvernightBoundaries() throws {
        let suiteName = "LocalNotificationPreferenceStoreTests.quiet-hours"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let store = LocalNotificationPreferenceStore(
            defaults: defaults,
            key: "preferences"
        )
        let start = try LocalTime.parse("22:00")
        let end = try LocalTime.parse("07:00")

        store.mount(accountID: "account-a")
        store.setQuietHours(enabled: true, start: start, end: end)
        let accountA = store.quietHours

        #expect(accountA.enabled)
        #expect(try accountA.contains(LocalTime.parse("22:00")))
        #expect(try accountA.contains(LocalTime.parse("06:59")))
        #expect(try !accountA.contains(LocalTime.parse("07:00")))
        #expect(try !accountA.contains(LocalTime.parse("12:00")))

        store.mount(accountID: "account-b")
        #expect(!store.quietHours.enabled)
        store.setQuietHours(enabled: true, start: end, end: start)

        store.mount(accountID: "account-a")
        #expect(store.quietHours == accountA)
    }
}
