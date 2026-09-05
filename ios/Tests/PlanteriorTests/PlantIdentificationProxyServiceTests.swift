import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

struct PlantIdentificationProxyServiceTests {
    @Test
    func sendsImageToConfiguredHTTPSProxyAndValidatesCandidates() async throws {
        let host = "plant-id-\(UUID().uuidString).example.invalid"
        let recorder = IdentificationRequestRecorder()
        TestWeatherURLProtocol.install(host: host) { request in
            recorder.record(request)
            return (200, Self.candidateResponse)
        }
        defer { TestWeatherURLProtocol.remove(host: host) }
        let service = try service(host: host)

        let states = try await states(
            from: service.identify(
                requestID: IdentificationRequestID.parse("request-123"),
                idempotencyKey: OperationID.parse("operation-123"),
                images: [
                    Data("private-front".utf8),
                    Data("private-leaf".utf8),
                    Data("private-stem".utf8)
                ]
            )
        )

        #expect(states.count == 2)
        #expect(states.first == .pending)
        guard case let .candidates(candidates) = states.last else {
            Issue.record("Expected validated candidates")
            return
        }
        #expect(candidates.items.map(\.plantID.rawValue) == ["plant-a", "plant-b"])
        #expect(candidates.items[0].koreanName == "몬스테라")
        #expect(candidates.items[0].commonName == "Monstera")
        #expect(candidates.items[0].scientificName == "Monstera deliciosa")
        #expect(
            candidates.items[0].thumbnailURL
                == URL(string: "https://images.example.invalid/plant-a.jpg")
        )
        guard let request = recorder.request,
              let body = recorder.body,
              let object = try JSONSerialization.jsonObject(with: body) as? [String: Any]
        else {
            Issue.record("Expected a captured JSON request")
            return
        }
        #expect(request.url?.scheme == "https")
        #expect(request.url?.path == "/identify")
        #expect(request.httpMethod == "POST")
        #expect(request.timeoutInterval == 15)
        #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
        #expect(
            request.value(forHTTPHeaderField: "Authorization")
                == "Bearer test-id-token"
        )
        #expect(
            request.value(forHTTPHeaderField: "X-Firebase-AppCheck")
                == "test-app-check-token"
        )
        #expect(object["requestID"] as? String == "request-123")
        #expect(object["idempotencyKey"] as? String == "operation-123")
        #expect(object["imagesBase64"] as? [String] == [
            Data("private-front".utf8).base64EncodedString(),
            Data("private-leaf".utf8).base64EncodedString(),
            Data("private-stem".utf8).base64EncodedString()
        ])
    }

    @Test
    func providerAuthorityIsShownInsteadOfFixedSpeciesForArbitraryCandidateID() async throws {
        let host = "plant-id-authority-\(UUID().uuidString).example.invalid"
        TestWeatherURLProtocol.install(host: host) { _ in
            (200, providerAuthorityResponse)
        }
        defer { TestWeatherURLProtocol.remove(host: host) }

        let states = try await states(
            from: service(host: host).identify(
                requestID: IdentificationRequestID.parse("request-authority"),
                idempotencyKey: OperationID.parse("operation-authority"),
                images: [Data("image".utf8)]
            )
        )

        guard case let .candidates(candidates) = states.last,
              let candidate = candidates.items.first
        else {
            Issue.record("Expected an authoritative provider candidate")
            return
        }
        #expect(candidate.plantID.rawValue == "arbitrary-provider-id")
        #expect(candidate.koreanName == "제공자 식물")
        #expect(candidate.commonName == "Provider common")
        #expect(candidate.scientificName == "Provider species")
        #expect(candidate.species.koreanName == "제공자 식물")
        #expect(candidate.species.binomial == "Provider species")
    }

    @Test
    func mapsHTTPTransportAndMalformedResponsesWithoutFixtureFallback() async throws {
        let rateHost = "plant-id-\(UUID().uuidString).example.invalid"
        TestWeatherURLProtocol.install(host: rateHost) { _ in (429, Data()) }
        defer { TestWeatherURLProtocol.remove(host: rateHost) }
        let rateStates = try await states(
            from: service(host: rateHost).identify(
                requestID: IdentificationRequestID.parse("request-rate"),
                idempotencyKey: OperationID.parse("operation-rate"),
                images: [Data("image".utf8)]
            )
        )
        #expect(rateStates.last == .failed(.rateLimited))

        let transportHost = "plant-id-\(UUID().uuidString).example.invalid"
        TestWeatherURLProtocol.install(host: transportHost) { _ in
            throw URLError(.notConnectedToInternet)
        }
        defer { TestWeatherURLProtocol.remove(host: transportHost) }
        let transportStates = try await states(
            from: service(host: transportHost).identify(
                requestID: IdentificationRequestID.parse("request-network"),
                idempotencyKey: OperationID.parse("operation-network"),
                images: [Data("image".utf8)]
            )
        )
        #expect(transportStates.last == .failed(.providerUnavailable))

        let malformedHost = "plant-id-\(UUID().uuidString).example.invalid"
        TestWeatherURLProtocol.install(host: malformedHost) { _ in
            let malformed = #"{"kind":"candidates","candidates":[]}"#
            return (200, Data(malformed.utf8))
        }
        defer { TestWeatherURLProtocol.remove(host: malformedHost) }
        let malformedStates = try await states(
            from: service(host: malformedHost).identify(
                requestID: IdentificationRequestID.parse("request-malformed"),
                idempotencyKey: OperationID.parse("operation-malformed"),
                images: [Data("image".utf8)]
            )
        )
        #expect(malformedStates.last == .failed(.invalidResponse))
    }

    @Test
    func doesNotSendRequestWhenCredentialProviderFails() async throws {
        let host = "plant-id-credentials-\(UUID().uuidString).example.invalid"
        let recorder = IdentificationRequestRecorder()
        TestWeatherURLProtocol.install(host: host) { request in
            recorder.record(request)
            return (200, Self.candidateResponse)
        }
        defer { TestWeatherURLProtocol.remove(host: host) }
        let service = try service(
            host: host,
            credentialProvider: FailingCredentialProvider()
        )

        let states = try await states(
            from: service.identify(
                requestID: IdentificationRequestID.parse("request-credential"),
                idempotencyKey: OperationID.parse("operation-credential"),
                images: [Data("image".utf8)]
            )
        )

        #expect(states.last == .failed(.providerUnavailable))
        #expect(recorder.request == nil)
    }

    @Test
    func validatesImageBeforeFetchingCredentials() async throws {
        let host = "plant-id-image-\(UUID().uuidString).example.invalid"
        let recorder = IdentificationRequestRecorder()
        let credentialProvider = CountingCredentialProvider()
        TestWeatherURLProtocol.install(host: host) { request in
            recorder.record(request)
            return (200, Self.candidateResponse)
        }
        defer { TestWeatherURLProtocol.remove(host: host) }
        let service = try service(
            host: host,
            credentialProvider: credentialProvider
        )

        let emptyStates = try await states(
            from: service.identify(
                requestID: IdentificationRequestID.parse("request-image"),
                idempotencyKey: OperationID.parse("operation-image"),
                images: []
            )
        )

        let tooManyStates = try await states(
            from: service.identify(
                requestID: IdentificationRequestID.parse("request-images"),
                idempotencyKey: OperationID.parse("operation-images"),
                images: Array(repeating: Data("image".utf8), count: 6)
            )
        )

        #expect(emptyStates.last == .failed(.invalidResponse))
        #expect(tooManyStates.last == .failed(.invalidResponse))
        #expect(await credentialProvider.callCount == 0)
        #expect(recorder.request == nil)
    }

    @Test
    func mapsUnauthorizedProxyResponsesToProviderUnavailable() async throws {
        for statusCode in [401, 403] {
            let host = "plant-id-auth-\(statusCode)-\(UUID().uuidString).example.invalid"
            TestWeatherURLProtocol.install(host: host) { _ in
                (statusCode, Data())
            }
            defer { TestWeatherURLProtocol.remove(host: host) }

            let states = try await states(
                from: service(host: host).identify(
                    requestID: IdentificationRequestID.parse("request-auth-\(statusCode)"),
                    idempotencyKey: OperationID.parse("operation-auth-\(statusCode)"),
                    images: [Data("image".utf8)]
                )
            )

            #expect(states.last == .failed(.providerUnavailable))
        }
    }

    private func service(
        host: String,
        credentialProvider: any PlantIdentificationCredentialProvider =
            TestCredentialProvider()
    ) throws -> PlantIdentificationProxyService {
        let sessionConfiguration = URLSessionConfiguration.ephemeral
        sessionConfiguration.protocolClasses = [TestWeatherURLProtocol.self]
        return try PlantIdentificationProxyService(
            testEndpoint: #require(URL(string: "https://\(host)/identify")),
            session: URLSession(configuration: sessionConfiguration),
            credentialProvider: credentialProvider
        )
    }

    private func states(
        from stream: AsyncStream<IdentificationState>
    ) async -> [IdentificationState] {
        var result: [IdentificationState] = []
        for await state in stream {
            result.append(state)
        }
        return result
    }

    private static let candidateResponse = Data(
        """
        {
          "kind": "candidates",
          "candidates": [
            {
              "publicContentId": "plant-b",
              "koreanName": "몬스테라 델리시오사",
              "commonName": "Swiss cheese plant",
              "scientificName": "Monstera deliciosa",
              "confidence": 0.72,
              "thumbnailUrl": "https://images.example.invalid/plant-b.jpg"
            },
            {
              "publicContentId": "plant-a",
              "koreanName": "몬스테라",
              "commonName": "Monstera",
              "scientificName": "Monstera deliciosa",
              "confidence": 0.95,
              "thumbnailUrl": "https://images.example.invalid/plant-a.jpg"
            }
          ]
        }
        """.utf8
    )

    private struct TestCredentialProvider: PlantIdentificationCredentialProvider {
        func headers() async throws -> PlantIdentificationCredentialHeaders {
            PlantIdentificationCredentialHeaders(
                authorization: "Bearer test-id-token",
                appCheck: "test-app-check-token"
            )
        }
    }

    private struct FailingCredentialProvider: PlantIdentificationCredentialProvider {
        func headers() async throws -> PlantIdentificationCredentialHeaders {
            throw PlantIdentificationCredentialError.unavailable
        }
    }

    private actor CountingCredentialProvider: PlantIdentificationCredentialProvider {
        private(set) var callCount = 0

        func headers() async throws -> PlantIdentificationCredentialHeaders {
            callCount += 1
            return PlantIdentificationCredentialHeaders(
                authorization: "Bearer test-id-token",
                appCheck: "test-app-check-token"
            )
        }
    }
}
