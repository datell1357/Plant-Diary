import Foundation
import PlanteriorData
import PlanteriorDomain

struct PlantIdentificationProxyConfiguration: Sendable {
    let endpoint: URL

    init(productionBaseURLString: String?) throws {
        let endpoint = try Self.parseURL(baseURLString: productionBaseURLString)
        guard endpoint.scheme == "https",
              endpoint.host?.caseInsensitiveCompare(Self.productionHost)
              == .orderedSame,
              Self.hasExactPath(endpoint, expected: Self.productionPath),
              (endpoint.port ?? 443) == 443
        else {
            throw PlantIdentificationProxyError.invalidConfiguration
        }
        self.endpoint = endpoint
    }

    #if DEBUG
        init(testEndpoint: URL) throws {
            endpoint = try Self.validateSecure(endpoint: testEndpoint)
        }

        init(localEmulatorBaseURLString: String?) throws {
            let endpoint = try Self.parseURL(
                baseURLString: localEmulatorBaseURLString
            )
            guard endpoint.scheme == "http",
                  endpoint.host == Self.localEmulatorHost,
                  endpoint.port == Self.localEmulatorPort,
                  Self.hasExactPath(endpoint, expected: Self.localEmulatorPath)
            else {
                throw PlantIdentificationProxyError.invalidConfiguration
            }
            self.endpoint = endpoint
        }
    #endif

    private static func parseURL(baseURLString: String?) throws -> URL {
        guard let value = baseURLString?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !value.isEmpty
        else {
            throw PlantIdentificationProxyError.configurationMissing
        }
        guard let url = URL(string: value) else {
            throw PlantIdentificationProxyError.invalidConfiguration
        }
        return try validateCommon(endpoint: url)
    }

    private static func validateCommon(endpoint: URL) throws -> URL {
        guard endpoint.host != nil,
              endpoint.user == nil,
              endpoint.password == nil,
              endpoint.fragment == nil,
              endpoint.query == nil
        else {
            throw PlantIdentificationProxyError.invalidConfiguration
        }
        return endpoint
    }

    private static func validateSecure(endpoint: URL) throws -> URL {
        let endpoint = try validateCommon(endpoint: endpoint)
        guard endpoint.scheme?.lowercased() == "https",
              (endpoint.port ?? 443) == 443
        else {
            throw PlantIdentificationProxyError.invalidConfiguration
        }
        return endpoint
    }

    private static func hasExactPath(_ endpoint: URL, expected: String) -> Bool {
        endpoint.path == expected
            && URLComponents(
                url: endpoint,
                resolvingAgainstBaseURL: false
            )?.percentEncodedPath == expected
    }

    private static let productionHost =
        "us-central1-planterior-helper-ios.cloudfunctions.net"
    private static let productionPath = "/identifyPlant"
    #if DEBUG
        private static let localEmulatorHost = "127.0.0.1"
        private static let localEmulatorPort = 5201
        private static let localEmulatorPath =
            "/planterior-helper-ios/us-central1/identifyPlant"
    #endif
}

enum PlantIdentificationProxyError: Error, Equatable, Sendable {
    case configurationMissing
    case invalidConfiguration
    case invalidImage
    case credentialsUnavailable
    case transport
    case httpStatus(Int)
    case invalidResponse
}

struct PlantIdentificationProxyService: PlantIdentificationService {
    private static let requestTimeout: TimeInterval = 15
    private static let maximumImageBytes = 4 * 1024 * 1024
    private let configuration: PlantIdentificationProxyConfiguration
    private let session: URLSession
    private let credentialProvider: any PlantIdentificationCredentialProvider

    init(
        configuration: PlantIdentificationProxyConfiguration,
        session: URLSession,
        credentialProvider: any PlantIdentificationCredentialProvider
    ) {
        self.configuration = configuration
        self.session = session
        self.credentialProvider = credentialProvider
    }

    #if DEBUG
        init(
            testEndpoint: URL,
            session: URLSession,
            credentialProvider: any PlantIdentificationCredentialProvider
        ) throws {
            try self.init(
                configuration: PlantIdentificationProxyConfiguration(
                    testEndpoint: testEndpoint
                ),
                session: session,
                credentialProvider: credentialProvider
            )
        }
    #endif

    func identify(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        images: [Data]
    ) -> AsyncStream<IdentificationState> {
        AsyncStream { continuation in
            continuation.yield(.pending)
            let task = Task {
                let state = await terminalState(
                    requestID: requestID,
                    idempotencyKey: idempotencyKey,
                    images: images
                )
                guard !Task.isCancelled else {
                    continuation.finish()
                    return
                }
                continuation.yield(state)
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func terminalState(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        images: [Data]
    ) async -> IdentificationState {
        do {
            let request = try await makeRequest(
                requestID: requestID,
                idempotencyKey: idempotencyKey,
                images: images
            )
            let (data, response) = try await session.data(for: request)
            do {
                return try responseState(data: data, response: response)
            } catch let error as PlantIdentificationProxyError {
                throw error
            } catch {
                throw PlantIdentificationProxyError.invalidResponse
            }
        } catch {
            return failureState(error)
        }
    }

    private func makeRequest(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        images: [Data]
    ) async throws -> URLRequest {
        guard (1 ... 5).contains(images.count),
              images.allSatisfy({
                  !$0.isEmpty && $0.count <= Self.maximumImageBytes
              })
        else {
            throw PlantIdentificationProxyError.invalidImage
        }
        let payload = PlantIdentificationProxyRequest(
            requestID: requestID.rawValue,
            idempotencyKey: idempotencyKey.rawValue,
            imagesBase64: images.map { $0.base64EncodedString() }
        )
        var request = URLRequest(url: configuration.endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = Self.requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(payload)
        let headers = try await credentialProvider.headers()
        guard headers.authorization.hasPrefix("Bearer "),
              !String(headers.authorization.dropFirst("Bearer ".count))
              .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !headers.appCheck
              .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw PlantIdentificationProxyError.credentialsUnavailable
        }
        request.setValue(
            headers.authorization,
            forHTTPHeaderField: "Authorization"
        )
        request.setValue(
            headers.appCheck,
            forHTTPHeaderField: "X-Firebase-AppCheck"
        )
        return request
    }

    private func responseState(
        data: Data,
        response: URLResponse
    ) throws -> IdentificationState {
        guard let response = response as? HTTPURLResponse,
              hasSameOrigin(response.url, as: configuration.endpoint)
        else {
            throw PlantIdentificationProxyError.invalidResponse
        }
        switch response.statusCode {
        case 200 ... 299: return try PlantIdentificationProxyResponse.decode(data)
        case 401, 403: return .failed(.providerUnavailable)
        case 408, 504: return .failed(.timeout)
        case 429: return .failed(.rateLimited)
        case 500 ... 599: return .failed(.serverFailure)
        default: return .failed(.invalidResponse)
        }
    }

    private func hasSameOrigin(_ responseURL: URL?, as endpoint: URL) -> Bool {
        guard let responseURL,
              let responseScheme = responseURL.scheme?.lowercased(),
              let endpointScheme = endpoint.scheme?.lowercased(),
              let responseHost = responseURL.host,
              let endpointHost = endpoint.host
        else {
            return false
        }
        return responseScheme == endpointScheme
            && responseHost.caseInsensitiveCompare(endpointHost) == .orderedSame
            && effectivePort(responseURL) == effectivePort(endpoint)
    }

    private func effectivePort(_ url: URL) -> Int? {
        if let port = url.port {
            return port
        }
        switch url.scheme?.lowercased() {
        case "https": return 443
        case "http": return 80
        default: return nil
        }
    }

    private func failureState(_ error: Error) -> IdentificationState {
        if error is CancellationError {
            return .failed(.providerUnavailable)
        }
        if let proxyError = error as? PlantIdentificationProxyError {
            return proxyFailureState(proxyError)
        }
        if (error as? URLError)?.code == .timedOut {
            return .failed(.timeout)
        }
        return .failed(.providerUnavailable)
    }

    private func proxyFailureState(
        _ error: PlantIdentificationProxyError
    ) -> IdentificationState {
        switch error {
        case .invalidResponse, .invalidImage:
            return .failed(.invalidResponse)
        case .configurationMissing, .invalidConfiguration,
             .credentialsUnavailable, .transport:
            return .failed(.providerUnavailable)
        case let .httpStatus(status):
            if (500 ... 599).contains(status) {
                return .failed(.serverFailure)
            }
            return .failed(.invalidResponse)
        }
    }
}

private struct PlantIdentificationProxyRequest: Encodable {
    let requestID: String
    let idempotencyKey: String
    let imagesBase64: [String]
}
