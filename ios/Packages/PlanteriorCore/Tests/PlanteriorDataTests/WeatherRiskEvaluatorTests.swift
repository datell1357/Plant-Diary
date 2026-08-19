@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct WeatherRiskEvaluatorTests {
    @Test
    func strictBoundariesStaySafeAndCrossedValuesAggregate() throws {
        let now = try Instant.parse("2026-08-11T03:00:00Z")
        let thresholds = WeatherRiskThresholds(
            lowTemperatureCelsius: 10,
            highTemperatureCelsius: 30,
            dryHumidityPercent: 40,
            overwateredPrecipitationMillimeters: 20
        )
        let evaluator = WeatherRiskEvaluator(now: now)

        let equality = try evaluator.evaluate(
            snapshot: snapshot(
                temperature: 30,
                humidity: 40,
                precipitation: 20,
                observedAt: "2026-08-11T00:00:00Z"
            ),
            thresholds: thresholds,
            globalAlertsEnabled: true,
            perPlantAlertsEnabled: true
        )
        let crossed = try evaluator.evaluate(
            snapshot: snapshot(
                temperature: 31,
                humidity: 39,
                precipitation: 21,
                observedAt: "2026-08-11T00:00:00Z"
            ),
            thresholds: thresholds,
            globalAlertsEnabled: true,
            perPlantAlertsEnabled: true
        )

        #expect(equality.risks.isEmpty)
        #expect(equality.alertsAllowed)
        #expect(
            crossed.risks == [
                .highTemperature,
                .dry,
                .overwatered
            ]
        )
    }

    @Test
    func dataOlderThanThreeHoursDisplaysButDoesNotAlert() throws {
        let now = try Instant.parse("2026-08-11T03:00:01Z")
        let evaluator = WeatherRiskEvaluator(now: now)
        let evaluation = try evaluator.evaluate(
            snapshot: snapshot(
                temperature: 35,
                humidity: 30,
                precipitation: 0,
                observedAt: "2026-08-11T00:00:00Z"
            ),
            thresholds: .plantDefault,
            globalAlertsEnabled: true,
            perPlantAlertsEnabled: true
        )

        #expect(evaluation.risks == [.highTemperature, .dry])
        #expect(evaluation.isStale)
        #expect(!evaluation.alertsAllowed)
    }

    @Test
    func globalOffOverridesPerPlantOnAndEpisodeAlertsOnce() throws {
        let plantID = try PersonalPlantID.parse("plant-a")
        var episodes = WeatherRiskEpisodeCoordinator()
        let active: Set<RiskType> = [.highTemperature, .dry]

        let disabled = episodes.alertsForTransition(
            plantID: plantID,
            activeRisks: active,
            globalEnabled: false,
            perPlantEnabled: true
        )
        let stillActive = episodes.alertsForTransition(
            plantID: plantID,
            activeRisks: active,
            globalEnabled: true,
            perPlantEnabled: true
        )
        _ = episodes.alertsForTransition(
            plantID: plantID,
            activeRisks: [],
            globalEnabled: true,
            perPlantEnabled: true
        )
        let nextEpisode = episodes.alertsForTransition(
            plantID: plantID,
            activeRisks: active,
            globalEnabled: true,
            perPlantEnabled: true
        )

        #expect(disabled.isEmpty)
        #expect(stillActive.isEmpty)
        #expect(nextEpisode == [.highTemperature, .dry])
    }

    @Test
    func futureObservationFailsClosed() throws {
        let now = try Instant.parse("2026-08-11T03:00:00Z")
        let evaluator = WeatherRiskEvaluator(now: now)

        #expect(throws: WeatherRiskEvaluationError.futureObservation) {
            try evaluator.evaluate(
                snapshot: snapshot(
                    temperature: 35,
                    humidity: 30,
                    precipitation: 0,
                    observedAt: "2026-08-11T03:00:01Z"
                ),
                thresholds: .plantDefault,
                globalAlertsEnabled: true,
                perPlantAlertsEnabled: true
            )
        }
    }

    private func snapshot(
        temperature: Double,
        humidity: Int,
        precipitation: Double,
        observedAt: String
    ) throws -> WeatherSnapshot {
        let id = try WeatherSnapshotID.parse("weather-a")
        let observed = try Instant.parse(observedAt)
        let expires = try Instant.parse("2026-08-11T06:00:00Z")
        return WeatherSnapshot(
            id: id,
            regionCode: "seoul",
            temperatureCelsius: temperature,
            humidityPercent: humidity,
            precipitationMillimeters: precipitation,
            observedAt: observed,
            expiresAt: expires
        )
    }
}
