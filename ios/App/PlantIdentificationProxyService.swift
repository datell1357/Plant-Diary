import Foundation
import PlanteriorData
import PlanteriorDomain

struct PlantIdentificationProxyConfiguration: Sendable {
    let endpoint: URL

    init(baseURLString: String?) throws {
        guard let value = baseURLString?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !value.isEmpty
        else {
            throw PlantIdentificationProxyError.configurationMissing
        }
        guard let url = URL(string: value),
              url.scheme?.lowercased() == "https",
              url.host != nil,
              url.user == nil,
              url.password == nil,
              url.fragment == nil,
              url.query == nil
        else {
            throw PlantIdentificationProxyError.invalidConfiguration
        }
        endpoint = url
    }
}

enum PlantIdentificationProxyError: Error, Equatable, Sendable {
    case configurationMissing
    case invalidConfiguration
    case invalidImage
    case transport
    case httpStatus(Int)
    case invalidResponse
}

struct PlantIdentificationProxyService: PlantIdentificationService {
    private static let requestTimeout: TimeInterval = 15
    private static let maximumImageBytes = 10 * 1024 * 1024
    private let configuration: PlantIdentificationProxyConfiguration
    private let session: URLSession

    init(
        configuration: PlantIdentificationProxyConfiguration,
        session: URLSession
    ) {
        self.configuration = configuration
        self.session = session
    }

    func identify(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        image: Data
    ) -> AsyncStream<IdentificationState> {
        AsyncStream { continuation in
            continuation.yield(.pending)
            let task = Task {
                let state = await terminalState(
                    requestID: requestID,
                    idempotencyKey: idempotencyKey,
                    image: image
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
        image: Data
    ) async -> IdentificationState {
        do {
            let request = try makeRequest(
                requestID: requestID,
                idempotencyKey: idempotencyKey,
                image: image
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
        image: Data
    ) throws -> URLRequest {
        guard !image.isEmpty, image.count <= Self.maximumImageBytes else {
            throw PlantIdentificationProxyError.invalidImage
        }
        let payload = PlantIdentificationProxyRequest(
            requestID: requestID.rawValue,
            idempotencyKey: idempotencyKey.rawValue,
            imageBase64: image.base64EncodedString()
        )
        var request = URLRequest(url: configuration.endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = Self.requestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(payload)
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
        case 408, 504: return .failed(.timeout)
        case 429: return .failed(.rateLimited)
        case 500 ... 599: return .failed(.serverFailure)
        default: return .failed(.invalidResponse)
        }
    }

    private func hasSameOrigin(_ responseURL: URL?, as endpoint: URL) -> Bool {
        guard let responseURL,
              responseURL.scheme?.lowercased() == "https",
              let responseHost = responseURL.host,
              let endpointHost = endpoint.host
        else {
            return false
        }
        return responseHost.caseInsensitiveCompare(endpointHost) == .orderedSame
            && (responseURL.port ?? 443) == (endpoint.port ?? 443)
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
        case .configurationMissing, .invalidConfiguration, .transport:
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
    let imageBase64: String
}
