import CryptoKit
import FirebaseAuth
import Foundation
import Security

struct AuthenticatedUserProfile: Equatable {
    let displayName: String?
    let email: String?
}

extension AuthRuntime {
    var accountProfile: AuthenticatedUserProfile? {
        #if DEBUG
            let environment = ProcessInfo.processInfo.environment
            let qaName = environment["QA_AUTH_PROFILE_NAME"]
            let qaEmail = environment["QA_AUTH_PROFILE_EMAIL"]
            if qaName != nil || qaEmail != nil {
                return AuthenticatedUserProfile(
                    displayName: qaName,
                    email: qaEmail
                )
            }
        #endif
        return authenticatedProfile
    }

    static func profile(for user: User) -> AuthenticatedUserProfile {
        AuthenticatedUserProfile(
            displayName: user.displayName,
            email: user.email
        )
    }

    static func randomNonce(length: Int = 32) -> String {
        let alphabet = Array(
            "0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._"
        )
        var result = ""
        while result.count < length {
            var random: UInt8 = 0
            guard SecRandomCopyBytes(
                kSecRandomDefault,
                1,
                &random
            ) == errSecSuccess else {
                continue
            }
            if Int(random) < alphabet.count {
                result.append(alphabet[Int(random)])
            }
        }
        return result
    }

    static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}
