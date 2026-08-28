import Foundation
@testable import Planterior
import PlanteriorDomain

actor ControllableWeatherRepository: WeatherSnapshotRepository {
    struct Request: Equatable, Sendable {
        let id: UUID
        let regionCode: String
    }

    private var availableRequests: [Request] = []
    private var requestWaiters: [CheckedContinuation<Request, Never>] = []
    private var responses: [
        UUID: CheckedContinuation<WeatherSnapshot, any Error>
    ] = [:]

    func snapshot(regionCode: String) async throws -> WeatherSnapshot {
        let request = Request(id: UUID(), regionCode: regionCode)
        return try await withCheckedThrowingContinuation { continuation in
            responses[request.id] = continuation
            if requestWaiters.isEmpty {
                availableRequests.append(request)
            } else {
                requestWaiters.removeFirst().resume(returning: request)
            }
        }
    }

    func nextRequest() async -> Request {
        if !availableRequests.isEmpty {
            return availableRequests.removeFirst()
        }
        return await withCheckedContinuation { continuation in
            requestWaiters.append(continuation)
        }
    }

    func succeed(_ request: Request, with snapshot: WeatherSnapshot) {
        guard let continuation = responses.removeValue(forKey: request.id) else {
            preconditionFailure("Unknown weather request")
        }
        continuation.resume(returning: snapshot)
    }

    func fail(_ request: Request, with error: WeatherRepositoryError) {
        guard let continuation = responses.removeValue(forKey: request.id) else {
            preconditionFailure("Unknown weather request")
        }
        continuation.resume(throwing: error)
    }
}
