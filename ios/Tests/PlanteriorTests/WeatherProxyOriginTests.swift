import Foundation
@testable import Planterior
import Testing

struct WeatherProxyOriginTests {
    @Test
    func rejectsCrossOriginFinalProxyResponse() async throws {
        let host = "weather-\(UUID().uuidString).example.invalid"
        let crossOriginURL = try #require(
            URL(string: "https://redirected-\(UUID().uuidString).example.invalid/weather")
        )
        TestWeatherURLProtocol.install(
            host: host,
            responseURL: crossOriginURL
        ) { _ in
            (200, response(regionCode: "manual-seoul"))
        }
        defer { TestWeatherURLProtocol.remove(host: host) }
        let repository = try repository(host: host)

        await #expect(throws: WeatherRepositoryError.invalidResponse) {
            try await repository.snapshot(regionCode: "manual-seoul")
        }
    }

    @Test
    func rejectsFinalProxyResponseOnDifferentEffectivePort() async throws {
        let host = "weather-\(UUID().uuidString).example.invalid"
        let differentPortURL = try #require(
            URL(string: "https://\(host):444/weather")
        )
        TestWeatherURLProtocol.install(
            host: host,
            responseURL: differentPortURL
        ) { _ in
            (200, response(regionCode: "manual-seoul"))
        }
        defer { TestWeatherURLProtocol.remove(host: host) }
        let repository = try repository(host: host)

        await #expect(throws: WeatherRepositoryError.invalidResponse) {
            try await repository.snapshot(regionCode: "manual-seoul")
        }
    }

    private func repository(
        host: String
    ) throws -> WeatherProxyWeatherSnapshotRepository {
        let configuration = try WeatherProxyConfiguration(
            baseURLString: "https://\(host)/weather"
        )
        let sessionConfiguration = URLSessionConfiguration.ephemeral
        sessionConfiguration.protocolClasses = [TestWeatherURLProtocol.self]
        return WeatherProxyWeatherSnapshotRepository(
            configuration: configuration,
            session: URLSession(configuration: sessionConfiguration)
        )
    }

    private func response(regionCode: String) -> Data {
        Data(
            """
            {
              "id": "snapshot-20260824",
              "regionCode": "\(regionCode)",
              "temperatureCelsius": 23.5,
              "humidityPercent": 61,
              "precipitationMillimeters": 0.4,
              "observedAt": "2026-08-24T00:00:00Z",
              "expiresAt": "2026-08-24T03:00:00Z"
            }
            """.utf8
        )
    }
}
