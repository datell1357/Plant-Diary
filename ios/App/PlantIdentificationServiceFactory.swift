import Foundation
import PlanteriorData
import PlanteriorDomain

struct UnavailablePlantIdentificationService: PlantIdentificationService {
    func identify(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        images: [Data]
    ) -> AsyncStream<IdentificationState> {
        AsyncStream { continuation in
            continuation.yield(.pending)
            continuation.yield(.failed(.providerUnavailable))
            continuation.finish()
        }
    }
}

enum PlantIdentificationServiceFactory {
    static func current() -> any PlantIdentificationService {
        let baseURLString = Bundle.main.object(
            forInfoDictionaryKey: "PLAN_PLANT_ID_PROXY_BASE_URL"
        ) as? String
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_IDENTIFICATION_PROVIDER"] == "local" {
                return LocalPlantIdentificationService()
            }
            let configuredURL = baseURLString?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if let configuredURL, !configuredURL.isEmpty {
                return makeDebug(baseURLString: configuredURL)
            }
            return LocalPlantIdentificationService()
        #else
            return makeRelease(baseURLString: baseURLString)
        #endif
    }

    static func makeRelease(
        baseURLString: String?,
        session: URLSession? = nil,
        credentialProvider: any PlantIdentificationCredentialProvider =
            FirebasePlantIdentificationCredentialProvider()
    ) -> any PlantIdentificationService {
        guard let configuration = try? PlantIdentificationProxyConfiguration(
            productionBaseURLString: baseURLString
        ) else {
            return UnavailablePlantIdentificationService()
        }
        return PlantIdentificationProxyService(
            configuration: configuration,
            session: session ?? proxySession(),
            credentialProvider: credentialProvider
        )
    }

    #if DEBUG
        static func makeDebug(
            baseURLString: String?,
            session: URLSession? = nil,
            localCredentialProvider: any PlantIdentificationCredentialProvider =
                FirebaseLocalPlantIdentificationCredentialProvider()
        ) -> any PlantIdentificationService {
            if let configuration = try? PlantIdentificationProxyConfiguration(
                localEmulatorBaseURLString: baseURLString
            ) {
                return PlantIdentificationProxyService(
                    configuration: configuration,
                    session: session ?? proxySession(),
                    credentialProvider: localCredentialProvider
                )
            }
            return makeRelease(
                baseURLString: baseURLString,
                session: session
            )
        }
    #endif

    private static func proxySession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 20
        configuration.waitsForConnectivity = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration)
    }
}
