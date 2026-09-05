import FirebaseAppCheck
import FirebaseAuth
import Foundation

struct PlantIdentificationCredentialHeaders: Equatable, Sendable {
    let authorization: String
    let appCheck: String

    init(authorization: String, appCheck: String) {
        self.authorization = authorization
        self.appCheck = appCheck
    }
}

protocol PlantIdentificationCredentialProvider: Sendable {
    func headers() async throws -> PlantIdentificationCredentialHeaders
}

enum PlantIdentificationCredentialError: Error, Equatable, Sendable {
    case unavailable
    case emptyToken
}

struct FirebasePlantIdentificationCredentialProvider:
    PlantIdentificationCredentialProvider {
    func headers() async throws -> PlantIdentificationCredentialHeaders {
        do {
            let authorization = try await firebaseAuthorizationHeader()
            let appCheckResult = try await AppCheck.appCheck().limitedUseToken()
            let appCheckToken = appCheckResult.token
            guard !appCheckToken
                .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else {
                throw PlantIdentificationCredentialError.emptyToken
            }
            return PlantIdentificationCredentialHeaders(
                authorization: authorization,
                appCheck: appCheckToken
            )
        } catch let error as PlantIdentificationCredentialError {
            throw error
        } catch {
            throw PlantIdentificationCredentialError.unavailable
        }
    }
}

#if DEBUG
    struct FirebaseLocalPlantIdentificationCredentialProvider:
        PlantIdentificationCredentialProvider {
        static let appCheckMarker = "planterior-local-emulator"

        func headers() async throws -> PlantIdentificationCredentialHeaders {
            PlantIdentificationCredentialHeaders(
                authorization: "Bearer planterior-local-simulator",
                appCheck: Self.appCheckMarker
            )
        }
    }
#endif

private func firebaseAuthorizationHeader() async throws -> String {
    guard FirebaseConfiguration.isAvailable,
          let user = Auth.auth().currentUser
    else {
        throw PlantIdentificationCredentialError.unavailable
    }
    do {
        let idToken = try await user.getIDToken(forcingRefresh: false)
        guard !idToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw PlantIdentificationCredentialError.emptyToken
        }
        return "Bearer \(idToken)"
    } catch let error as PlantIdentificationCredentialError {
        throw error
    } catch {
        throw PlantIdentificationCredentialError.unavailable
    }
}
