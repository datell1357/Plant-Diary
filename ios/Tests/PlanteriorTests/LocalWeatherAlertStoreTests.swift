import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct LocalWeatherAlertStoreTests {
    @Test
    func persistsEpisodeDedupeAndGlobalPrecedence() throws {
        let suiteName = "LocalWeatherAlertStoreTests"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let plantID = try PersonalPlantID.parse("plant-a")
        let risks: Set<RiskType> = [.highTemperature, .dry]
        let store = LocalWeatherAlertStore(
            defaults: defaults,
            key: "weather-alerts"
        )

        store.setGlobalEnabled(false)
        #expect(
            store.reconcile(plantID: plantID, activeRisks: risks).isEmpty
        )
        store.setGlobalEnabled(true)
        #expect(
            store.reconcile(plantID: plantID, activeRisks: risks).isEmpty
        )
        _ = store.reconcile(plantID: plantID, activeRisks: [])
        store.setPlantEnabled(false, plantID: plantID)
        #expect(
            store.reconcile(plantID: plantID, activeRisks: risks).isEmpty
        )
        _ = store.reconcile(plantID: plantID, activeRisks: [])
        store.setPlantEnabled(true, plantID: plantID)
        let nextEpisode = store.reconcile(
            plantID: plantID,
            activeRisks: risks
        )
        let restored = LocalWeatherAlertStore(
            defaults: defaults,
            key: "weather-alerts"
        )

        #expect(nextEpisode == [.highTemperature, .dry])
        #expect(
            restored.reconcile(
                plantID: plantID,
                activeRisks: risks
            ).isEmpty
        )
    }

    @Test
    func keepsAlertPreferencesAccountScoped() {
        let suiteName = "LocalWeatherAlertStoreAccountTests"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            Issue.record("Unable to create isolated defaults")
            return
        }
        defaults.removePersistentDomain(forName: suiteName)
        let store = LocalWeatherAlertStore(defaults: defaults)

        store.mount(accountID: "account-a")
        store.setGlobalEnabled(false)
        store.mount(accountID: "account-b")
        #expect(store.globalEnabled)
        store.mount(accountID: "account-a")
        #expect(!store.globalEnabled)
    }
}
