import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

struct WeatherProxyRepositoryTests {
    @Test
    func requestsConfiguredProxyWithPercentEncodedRegion() async throws {
        let host = "weather-\(UUID().uuidString).example.invalid"
        let recorder = RequestRecorder()
        TestWeatherURLProtocol.install(host: host) { request in
            recorder.record(request)
            return (200, Self.response(regionCode: "seoul/중앙 & district"))
        }
        defer { TestWeatherURLProtocol.remove(host: host) }
        let repository = try repository(host: host)

        let snapshot = try await repository.snapshot(
            regionCode: "seoul/중앙 & district"
        )

        #expect(snapshot.id.rawValue == "snapshot-20260824")
        #expect(snapshot.temperatureCelsius == 23.5)
        #expect(snapshot.humidityPercent == 61)
        guard let request = recorder.request else {
            Issue.record("The proxy request was not captured")
            return
        }
        guard let requestURL = request.url else {
            Issue.record("The captured proxy request has no URL")
            return
        }
        #expect(request.httpMethod == "GET")
        #expect(request.value(forHTTPHeaderField: "Accept") == "application/json")
        #expect(request.timeoutInterval == 10)
        #expect(requestURL.scheme == "https")
        #expect(requestURL.path == "/weather")
        let components = URLComponents(
            url: requestURL,
            resolvingAgainstBaseURL: false
        )
        #expect(components?.queryItems?.first(
            where: { $0.name == "regionCode" }
        )?.value == "seoul/중앙 & district")
        #expect(request.url?.absoluteString.contains("%2F") == true)
    }

    @Test
    func rejectsMalformedProxyResponse() async throws {
        let host = "weather-\(UUID().uuidString).example.invalid"
        TestWeatherURLProtocol.install(host: host) { _ in
            (200, Data(#"{"id":"snapshot-20260824"}"#.utf8))
        }
        defer { TestWeatherURLProtocol.remove(host: host) }
        let repository = try repository(host: host)

        await #expect(throws: WeatherRepositoryError.invalidResponse) {
            try await repository.snapshot(regionCode: "manual-seoul")
        }
    }

    @Test
    func rejectsUnknownProxyResponseKeysBeforeTypedDecode() async throws {
        let host = "weather-\(UUID().uuidString).example.invalid"
        TestWeatherURLProtocol.install(host: host) { _ in
            (200, Data(
                """
                {
                  "id": "snapshot-20260824",
                  "regionCode": "manual-seoul",
                  "temperatureCelsius": 23.5,
                  "humidityPercent": 61,
                  "precipitationMillimeters": 0.4,
                  "observedAt": "2026-08-24T00:00:00Z",
                  "expiresAt": "2026-08-24T03:00:00Z",
                  "schemaDrift": true
                }
                """.utf8
            ))
        }
        defer { TestWeatherURLProtocol.remove(host: host) }
        let repository = try repository(host: host)

        await #expect(throws: WeatherRepositoryError.invalidResponse) {
            try await repository.snapshot(regionCode: "manual-seoul")
        }
    }

    @Test
    func surfacesProxyHTTPAndTransportErrorsWithoutFallback() async throws {
        let httpHost = "weather-\(UUID().uuidString).example.invalid"
        TestWeatherURLProtocol.install(host: httpHost) { _ in (503, Data()) }
        defer { TestWeatherURLProtocol.remove(host: httpHost) }
        let httpRepository = try repository(host: httpHost)

        await #expect(throws: WeatherRepositoryError.httpStatus(503)) {
            try await httpRepository.snapshot(regionCode: "manual-seoul")
        }

        let transportHost = "weather-\(UUID().uuidString).example.invalid"
        TestWeatherURLProtocol.install(host: transportHost) { _ in
            throw URLError(.notConnectedToInternet)
        }
        defer { TestWeatherURLProtocol.remove(host: transportHost) }
        let transportRepository = try repository(host: transportHost)

        await #expect(throws: WeatherRepositoryError.transport) {
            try await transportRepository.snapshot(regionCode: "manual-seoul")
        }
    }

    @Test
    func factorySelectsConfiguredProxyAndMakesMissingConfigurationExplicit() throws {
        let configured = WeatherRepositoryFactory.make(
            baseURLString: "https://weather.example.invalid/weather"
        )
        #expect(configured is WeatherProxyWeatherSnapshotRepository)

        let missing = WeatherRepositoryFactory.make(baseURLString: "  ")
        let unavailable = try #require(
            missing as? UnavailableWeatherRepository
        )
        #expect(unavailable.error == .configurationMissing)

        #expect(throws: WeatherRepositoryError.invalidConfiguration) {
            try WeatherProxyConfiguration(
                baseURLString: "http://weather.example.invalid/weather"
            )
        }
    }

    #if DEBUG
        @Test
        @MainActor
        func debugRuntimeKeepsTheDeterministicQARepository() {
            #expect(WeatherRuntime.currentRepository() is QAWeatherRepository)
        }
    #endif

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

    private static func response(regionCode: String) -> Data {
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
