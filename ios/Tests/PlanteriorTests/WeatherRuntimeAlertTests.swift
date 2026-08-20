import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct WeatherRuntimeAlertTests {
    @Test
    func dedupesRuntimeEpisodeAndClearsEvaluationOnAccountRemount() throws {
        let firstAccount = "runtime-alert-\(UUID())"
        let secondAccount = "runtime-alert-\(UUID())"
        let plantID = try PersonalPlantID.parse("weather-plant")
        let runtime = WeatherRuntime()
        LocalWeatherAlertStore.shared.mount(accountID: firstAccount)
        runtime.reloadAlertPreferences()
        runtime.latestEvaluation = try evaluation()

        runtime.reconcileAlerts(plants: [plantID])
        #expect(runtime.plannedAlertCount == 2)
        #expect(runtime.newlyPlannedAlertCount == 2)
        runtime.reconcileAlerts(plants: [plantID])
        #expect(runtime.plannedAlertCount == 2)
        #expect(runtime.newlyPlannedAlertCount == 0)

        runtime.prepareForAccountRemount()
        LocalWeatherAlertStore.shared.mount(accountID: secondAccount)
        runtime.reloadAlertPreferences()
        runtime.reconcileAlerts(plants: [plantID])
        #expect(runtime.plannedAlertCount == 0)
        runtime.latestEvaluation = try evaluation()
        runtime.reconcileAlerts(plants: [plantID])
        #expect(runtime.plannedAlertCount == 2)
        #expect(runtime.newlyPlannedAlertCount == 2)
    }

    @Test
    func accountRemountScopesManualRegionAndClearsLiveRegionState() {
        let accountA = "weather-a-\(UUID())"
        let accountB = "weather-b-\(UUID())"
        let defaults = UserDefaults.standard
        defer {
            defaults.removeObject(
                forKey: "weather.\(accountA).manual-region"
            )
            defaults.removeObject(
                forKey: "weather.\(accountB).manual-region"
            )
        }
        let runtime = WeatherRuntime()
        runtime.mount(accountID: accountA)
        runtime.setManualRegion("account-a-region")
        runtime.locationRegionCode = "37.57,126.98"

        runtime.mount(accountID: accountB)

        #expect(runtime.manualRegionCode == nil)
        #expect(runtime.locationRegionCode == nil)
        #expect(runtime.effectiveRegionCode == nil)
        runtime.setManualRegion("account-b-region")

        runtime.mount(accountID: accountA)

        #expect(runtime.manualRegionCode == "account-a-region")
        #expect(runtime.locationRegionCode == nil)
        #expect(runtime.effectiveRegionCode == nil)
    }

    private func evaluation() throws -> WeatherRiskEvaluation {
        let now = try Instant.parse("2026-08-11T03:00:00Z")
        let snapshot = try WeatherSnapshot(
            id: WeatherSnapshotID.parse("runtime-alert-snapshot"),
            regionCode: "manual-seoul",
            temperatureCelsius: 31,
            humidityPercent: 39,
            precipitationMillimeters: 0,
            observedAt: now,
            expiresAt: Instant.parse("2026-08-11T04:00:00Z")
        )
        return try WeatherRiskEvaluator(now: now).evaluate(
            snapshot: snapshot,
            thresholds: .plantDefault,
            globalAlertsEnabled: true,
            perPlantAlertsEnabled: true
        )
    }
}
