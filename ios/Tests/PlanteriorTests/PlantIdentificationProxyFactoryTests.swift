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
                images: [Data("image".utf8)]
            )
        )
        #expect(missingStates == [.pending, .failed(.providerUnavailable)])

        let insecure = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "http://us-central1-planterior-helper-ios.cloudfunctions.net/identifyPlant"
        )
        #expect(insecure is UnavailablePlantIdentificationService)
        #expect(throws: PlantIdentificationProxyError.invalidConfiguration) {
            try PlantIdentificationProxyConfiguration(
                productionBaseURLString: "https://user:secret@provider.example.invalid/identify"
            )
        }

        let wrongHost = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "https://provider.example.invalid/identifyPlant"
        )
        #expect(wrongHost is UnavailablePlantIdentificationService)
        let wrongPath = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "https://us-central1-planterior-helper-ios.cloudfunctions.net/identify"
        )
        #expect(wrongPath is UnavailablePlantIdentificationService)
        let wrongPort = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "https://us-central1-planterior-helper-ios.cloudfunctions.net:8443/identifyPlant"
        )
        #expect(wrongPort is UnavailablePlantIdentificationService)
        let query = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "https://us-central1-planterior-helper-ios.cloudfunctions.net/identifyPlant?debug=1"
        )
        #expect(query is UnavailablePlantIdentificationService)
        let fragment = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "https://us-central1-planterior-helper-ios.cloudfunctions.net/identifyPlant#fragment"
        )
        #expect(fragment is UnavailablePlantIdentificationService)
        let credentials = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "https://user:secret@us-central1-planterior-helper-ios.cloudfunctions.net/identifyPlant"
        )
        #expect(credentials is UnavailablePlantIdentificationService)

        let configured = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "https://us-central1-planterior-helper-ios.cloudfunctions.net/identifyPlant"
        )
        #expect(configured is PlantIdentificationProxyService)
        let explicitDefaultPort = PlantIdentificationServiceFactory.makeRelease(
            baseURLString: "https://us-central1-planterior-helper-ios.cloudfunctions.net:443/identifyPlant"
        )
        #expect(explicitDefaultPort is PlantIdentificationProxyService)
    }

    #if DEBUG
        @Test
        func debugFactoryUsesTheConfiguredLocalEmulatorProxy() throws {
            let localEndpoint =
                "http://127.0.0.1:5201/planterior-helper-ios/us-central1/identifyPlant"
            let service = PlantIdentificationServiceFactory.makeDebug(
                baseURLString: localEndpoint
            )

            #expect(service is PlantIdentificationProxyService)
            #expect(
                try PlantIdentificationProxyConfiguration(
                    localEmulatorBaseURLString: localEndpoint
                ).endpoint == URL(string: localEndpoint)
            )
        }

        @Test
        func debugFactoryRejectsLocalLookalikes() {
            let lookalikes = [
                "http://localhost:5201/planterior-helper-ios/us-central1/identifyPlant",
                "http://127.0.0.1:5001/planterior-helper-ios/us-central1/identifyPlant",
                "http://127.0.0.1:5201/other-project/us-central1/identifyPlant",
                "http://127.0.0.1:5201/planterior-helper-ios/us-central1/identifyPlant?debug=1",
                "https://127.0.0.1:5201/planterior-helper-ios/us-central1/identifyPlant"
            ]

            for value in lookalikes {
                #expect(
                    PlantIdentificationServiceFactory.makeDebug(
                        baseURLString: value
                    ) is UnavailablePlantIdentificationService
                )
            }
        }

        @Test
        func localCredentialProviderUsesEmulatorOnlyMarkers() async throws {
            let headers = try await
                FirebaseLocalPlantIdentificationCredentialProvider().headers()

            #expect(
                headers.authorization
                    == "Bearer planterior-local-simulator"
            )
            #expect(headers.appCheck == "planterior-local-emulator")
        }

        @Test
        func debugCurrentFactoryUsesTheConfiguredProxy() {
            #expect(
                PlantIdentificationServiceFactory.current()
                    is PlantIdentificationProxyService
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
