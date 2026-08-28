import Foundation
import PlanteriorDomain

struct WeatherProxyConfiguration: Sendable {
    let baseURL: URL

    init(baseURLString: String?) throws {
        guard let baseURLString = baseURLString?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !baseURLString.isEmpty
        else {
            throw WeatherRepositoryError.configurationMissing
        }
        guard let baseURL = URL(string: baseURLString),
              baseURL.scheme?.lowercased() == "https",
              baseURL.host != nil,
              baseURL.user == nil,
              baseURL.password == nil,
              baseURL.fragment == nil
        else {
            throw WeatherRepositoryError.invalidConfiguration
        }
        self.baseURL = baseURL
    }

    func snapshotURL(regionCode: String) throws -> URL {
        guard !regionCode.trimmingCharacters(
            in: .whitespacesAndNewlines
        ).isEmpty,
            var components = URLComponents(
                url: baseURL,
                resolvingAgainstBaseURL: false
            )
        else {
            throw WeatherRepositoryError.invalidConfiguration
        }
        var allowedCharacters = CharacterSet.urlQueryAllowed
        allowedCharacters.remove(charactersIn: "&=+/?")
        guard let encodedRegionCode = regionCode.addingPercentEncoding(
            withAllowedCharacters: allowedCharacters
        )
        else {
            throw WeatherRepositoryError.invalidConfiguration
        }
        let regionQuery = "regionCode=\(encodedRegionCode)"
        components.percentEncodedQuery = [
            components.percentEncodedQuery,
            regionQuery
        ].compactMap(\.self).joined(separator: "&")
        guard let url = components.url else {
            throw WeatherRepositoryError.invalidConfiguration
        }
        return url
    }
}

struct WeatherProxyWeatherSnapshotRepository: WeatherSnapshotRepository {
    private static let requestTimeout: TimeInterval = 10
    private let configuration: WeatherProxyConfiguration
    private let session: URLSession

    init(configuration: WeatherProxyConfiguration, session: URLSession) {
        self.configuration = configuration
        self.session = session
    }

    private static func hasSameOrigin(
        _ responseURL: URL,
        as configuredURL: URL
    ) -> Bool {
        guard let responseScheme = responseURL.scheme?.lowercased(),
              let configuredScheme = configuredURL.scheme?.lowercased(),
              let responseHost = responseURL.host?.lowercased(),
              let configuredHost = configuredURL.host?.lowercased()
        else {
            return false
        }
        let responsePort = responseURL.port ?? 443
        let configuredPort = configuredURL.port ?? 443
        return responseScheme == configuredScheme &&
            responseHost == configuredHost &&
            responsePort == configuredPort
    }

    func snapshot(regionCode: String) async throws -> WeatherSnapshot {
        let url = try configuration.snapshotURL(regionCode: regionCode)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = Self.requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw WeatherRepositoryError.transport
        }
        guard let httpResponse = response as? HTTPURLResponse,
              let responseURL = httpResponse.url,
              Self.hasSameOrigin(
                  responseURL,
                  as: configuration.baseURL
              )
        else {
            throw WeatherRepositoryError.invalidResponse
        }
        guard (200 ... 299).contains(httpResponse.statusCode) else {
            throw WeatherRepositoryError.httpStatus(httpResponse.statusCode)
        }
        do {
            return try WeatherProxySnapshotResponse.decode(
                data: data,
                requestedRegionCode: regionCode
            )
        } catch let error as WeatherRepositoryError {
            throw error
        } catch {
            throw WeatherRepositoryError.invalidResponse
        }
    }
}

enum WeatherRepositoryFactory {
    static func make(
        baseURLString: String?,
        session: URLSession? = nil
    ) -> any WeatherSnapshotRepository {
        do {
            let configuration = try WeatherProxyConfiguration(
                baseURLString: baseURLString
            )
            return WeatherProxyWeatherSnapshotRepository(
                configuration: configuration,
                session: session ?? weatherProxySession()
            )
        } catch let error as WeatherRepositoryError {
            return UnavailableWeatherRepository(error: error)
        } catch {
            return UnavailableWeatherRepository(error: .invalidConfiguration)
        }
    }

    private static func weatherProxySession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 15
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration)
    }
}
