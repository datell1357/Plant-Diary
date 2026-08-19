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
