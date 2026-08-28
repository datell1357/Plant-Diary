import Foundation
import PlanteriorData
import PlanteriorDomain

struct UnavailablePlantIdentificationService: PlantIdentificationService {
    func identify(
        requestID: IdentificationRequestID,
        idempotencyKey: OperationID,
        image: Data
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
        #if DEBUG
            return LocalPlantIdentificationService()
        #else
            return makeRelease(
                baseURLString: Bundle.main.object(
                    forInfoDictionaryKey: "PLAN_PLANT_ID_PROXY_BASE_URL"
                ) as? String
            )
        #endif
    }

    static func makeRelease(
        baseURLString: String?,
        session: URLSession? = nil
    ) -> any PlantIdentificationService {
        guard let configuration = try? PlantIdentificationProxyConfiguration(
            baseURLString: baseURLString
        ) else {
            return UnavailablePlantIdentificationService()
        }
        return PlantIdentificationProxyService(
            configuration: configuration,
            session: session ?? proxySession()
        )
    }

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
