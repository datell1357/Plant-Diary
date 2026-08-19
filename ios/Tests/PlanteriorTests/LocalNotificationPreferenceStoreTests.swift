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
}
