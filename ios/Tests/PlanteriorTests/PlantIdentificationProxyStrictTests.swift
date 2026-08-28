import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

struct PlantIdentificationProxyStrictTests {
    @Test
    func rejectsUnknownKeysDuplicateIDsNonFiniteConfidenceAndInsecureURLs() async throws {
        for payload in Self.invalidPayloads {
            let host = "plant-id-strict-\(UUID().uuidString).example.invalid"
            TestWeatherURLProtocol.install(host: host) { _ in
                (200, Data(payload.utf8))
            }
            defer { TestWeatherURLProtocol.remove(host: host) }
            let states = try await collectStates(
                from: makeService(host: host).identify(
                    requestID: IdentificationRequestID.parse("request-strict"),
                    idempotencyKey: OperationID.parse("operation-strict"),
                    image: Data("image".utf8)
                )
            )
            #expect(states.last == .failed(.invalidResponse))
        }
    }

    @Test
    func rejectsCrossOriginNonHTTPSAndPortChangedFinalResponseURLs() async throws {
        let host = "plant-id-origin-\(UUID().uuidString).example.invalid"
        let finalURLs = [
            URL(string: "https://cross-origin.example.invalid/identify"),
            URL(string: "http://\(host)/identify"),
            URL(string: "https://\(host):8443/identify")
        ]
        for finalURL in finalURLs {
            let finalURL = try #require(finalURL)
            TestWeatherURLProtocol.install(
                host: host,
                responseURL: finalURL
            ) { _ in (200, Self.validResponse) }
            defer { TestWeatherURLProtocol.remove(host: host) }
            let states = try await collectStates(
                from: makeService(host: host).identify(
                    requestID: IdentificationRequestID.parse("request-origin"),
                    idempotencyKey: OperationID.parse("operation-origin"),
                    image: Data("image".utf8)
                )
            )
            #expect(states.last == .failed(.invalidResponse))
        }
    }

    private func makeService(host: String) throws -> PlantIdentificationProxyService {
        let configuration = try PlantIdentificationProxyConfiguration(
            baseURLString: "https://\(host)/identify"
        )
        let sessionConfiguration = URLSessionConfiguration.ephemeral
        sessionConfiguration.protocolClasses = [TestWeatherURLProtocol.self]
        return PlantIdentificationProxyService(
            configuration: configuration,
            session: URLSession(configuration: sessionConfiguration)
        )
    }

    private func collectStates(
        from stream: AsyncStream<IdentificationState>
    ) async -> [IdentificationState] {
        var result: [IdentificationState] = []
        for await state in stream {
            result.append(state)
        }
        return result
    }

    private static let invalidPayloads = [
        """
        {
          "kind":"candidates",
          "candidates":[{
            "publicContentId":"one",
            "koreanName":"하나",
            "commonName":"One",
            "scientificName":"One species",
            "confidence":0.5,
            "thumbnailUrl":"https://images.example.invalid/one.jpg",
            "extra":true
          }]
        }
        """,
        """
        {
          "kind":"candidates",
          "candidates":[
            {
              "publicContentId":"same",
              "koreanName":"하나",
              "commonName":"One",
              "scientificName":"One species",
              "confidence":0.5,
              "thumbnailUrl":"https://images.example.invalid/one.jpg"
            },
            {
              "publicContentId":"same",
              "koreanName":"둘",
              "commonName":"Two",
              "scientificName":"Two species",
              "confidence":0.4,
              "thumbnailUrl":"https://images.example.invalid/two.jpg"
            }
          ]
        }
        """,
        """
        {
          "kind":"candidates",
          "candidates":[
            {"publicContentId":"one","koreanName":"하나","commonName":"One",
             "scientificName":"One species","confidence":0.5,
             "thumbnailUrl":"https://images.example.invalid/one.jpg"},
            {"publicContentId":"two","koreanName":"둘","commonName":"Two",
             "scientificName":"Two species","confidence":0.4,
             "thumbnailUrl":"https://images.example.invalid/two.jpg"},
            {"publicContentId":"three","koreanName":"셋","commonName":"Three",
             "scientificName":"Three species","confidence":0.3,
             "thumbnailUrl":"https://images.example.invalid/three.jpg"},
            {"publicContentId":"four","koreanName":"넷","commonName":"Four",
             "scientificName":"Four species","confidence":0.2,
             "thumbnailUrl":"https://images.example.invalid/four.jpg"}
          ]
        }
        """,
        """
        {
          "kind":"candidates",
          "candidates":[{
            "publicContentId":"one",
            "koreanName":"하나",
            "commonName":"One",
            "scientificName":"One species",
            "confidence":1e999,
            "thumbnailUrl":"https://images.example.invalid/one.jpg"
          }]
        }
        """,
        """
        {
          "kind":"candidates",
          "candidates":[{
            "publicContentId":"one",
            "koreanName":"하나",
            "commonName":"One",
            "scientificName":"One species",
            "confidence":0.5,
            "thumbnailUrl":"http://images.example.invalid/one.jpg"
          }]
        }
        """
    ]

    private static let validResponse = Data(
        """
        {
          "kind": "candidates",
          "candidates": [
            {
              "publicContentId": "provider-id",
              "koreanName": "제공자 식물",
              "commonName": "Provider common",
              "scientificName": "Provider species",
              "confidence": 0.95,
              "thumbnailUrl": "https://images.example.invalid/provider.jpg"
            }
          ]
        }
        """.utf8
    )
}
