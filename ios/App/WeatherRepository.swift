import Foundation
import PlanteriorDomain

enum WeatherRepositoryError: Error {
    case unavailable
    case fixtureFailure
}

protocol WeatherSnapshotRepository: Sendable {
    func snapshot(regionCode: String) async throws -> WeatherSnapshot
}

struct UnavailableWeatherRepository: WeatherSnapshotRepository {
    func snapshot(regionCode _: String) async throws -> WeatherSnapshot {
        throw WeatherRepositoryError.unavailable
    }
}

struct QAWeatherRepository: WeatherSnapshotRepository {
    let processInfo: ProcessInfo

    func snapshot(regionCode: String) async throws -> WeatherSnapshot {
        #if DEBUG
            let fixture = processInfo.environment["QA_WEATHER_FIXTURE"]
                ?? processInfo.environment["QA_HOME_WEATHER_STATE"]
            guard let fixture else {
                throw WeatherRepositoryError.unavailable
            }
            if fixture == "failed" {
                throw WeatherRepositoryError.fixtureFailure
            }
            let temperature = fixture == "high-dry" ? 35.0 : 22.0
            let humidity = fixture == "high-dry" ? 30 : 55
            let observedAt = processInfo.environment[
                "QA_WEATHER_OBSERVED_AT"
            ] ?? "2026-08-11T00:00:00Z"
            let id = try WeatherSnapshotID.parse("qa-weather")
            let observed = try Instant.parse(observedAt)
            let expires = try Instant.parse(
                "2026-08-11T06:00:00Z"
            )
            return WeatherSnapshot(
                id: id,
                regionCode: regionCode,
                temperatureCelsius: temperature,
                humidityPercent: humidity,
                precipitationMillimeters: 0,
                observedAt: observed,
                expiresAt: expires
            )
        #else
            throw WeatherRepositoryError.unavailable
        #endif
    }
}
