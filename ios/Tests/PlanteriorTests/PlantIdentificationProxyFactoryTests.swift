import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

struct PlantIdentificationProxyFactoryTests {
    @Test
    func releaseFactoryFailsClosedForMissingOrInvalidConfiguration() async throws {
        let missing = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: nil
        )
        #expect(missing is UnavailablePlantIdentificationService)
        let missingStates = try await collectStates(
            from: missing.identify(
                requestID: IdentificationRequestID.parse("request-missing"),
                idempotencyKey: OperationID.parse("operation-missing"),
                image: Data("image".utf8)
            )
        )
        #expect(missingStates == [.pending, .failed(.providerUnavailable)])

        let insecure = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "http://provider.example.invalid/identify"
        )
        #expect(insecure is UnavailablePlantIdentificationService)
        #expect(throws: PlantIdentificationProxyError.invalidConfiguration) {
            try PlantIdentificationProxyConfiguration(
                baseURLString: "https://user:secret@provider.example.invalid/identify"
            )
        }

        let configured = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "https://provider.example.invalid/identify"
        )
        #expect(configured is PlantIdentificationProxyService)
    }

    #if DEBUG
        @Test
        func debugFactoryRetainsTheDeterministicLocalFixture() {
            #expect(
                PlantIdentificationServiceFactory.current()
                    is LocalPlantIdentificationService
            )
        }
    #endif

    private func collectStates(
        from stream: AsyncStream<IdentificationState>
    ) async -> [IdentificationState] {
        var result: [IdentificationState] = []
        for await state in stream {
            result.append(state)
        }
        return result
    }
}
