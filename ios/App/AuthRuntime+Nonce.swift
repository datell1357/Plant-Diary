import AuthenticationServices
import CryptoKit
import FirebaseAuth
import FirebaseCore
import Foundation
import GoogleSignIn
import PlanteriorData
import PlanteriorDomain
import Security
import UIKit

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

    var sessionProvider: AuthProvider? {
        let providerIDs = Auth.auth().currentUser?.providerData.map(\.providerID) ?? []
        if providerIDs.contains("apple.com") {
            return .apple
        }
        return providerIDs.contains("google.com") ? .google : nil
    }

    func reauthenticateGoogle(
        presenting viewController: UIViewController
    ) async -> Bool {
        guard let user = Auth.auth().currentUser,
              let clientID = FirebaseApp.app()?.options.clientID
        else {
            return false
        }
        do {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
            let result = try await GIDSignIn.sharedInstance.signIn(
                withPresenting: viewController
            )
            guard let idToken = result.user.idToken?.tokenString else {
                return false
            }
            let credential = GoogleAuthProvider.credential(
                withIDToken: idToken,
                accessToken: result.user.accessToken.tokenString
            )
            let authenticated = try await user.reauthenticate(with: credential)
            return authenticated.user.uid == user.uid
        } catch {
            return false
        }
    }

    func reauthenticateApple(
        _ result: Result<ASAuthorization, any Error>
    ) async -> Bool {
        defer { nonce = nil }
        guard case let .success(authorization) = result,
              let credential = authorization.credential
              as? ASAuthorizationAppleIDCredential,
              let nonce,
              let tokenData = credential.identityToken,
              let token = String(data: tokenData, encoding: .utf8),
              let user = Auth.auth().currentUser
        else {
            return false
        }
        do {
            let firebaseCredential = OAuthProvider.appleCredential(
                withIDToken: token,
                rawNonce: nonce,
                fullName: credential.fullName
            )
            let authenticated = try await user.reauthenticate(
                with: firebaseCredential
            )
            return authenticated.user.uid == user.uid
        } catch {
            return false
        }
    }

    func signOutAfterSync() async {
        do {
            try Auth.auth().signOut()
        } catch {
            reportSignOutFailure()
            return
        }
        GIDSignIn.sharedInstance.signOut()
        let cleanup = await sessions.logoutAfterRemoteSignOut(
            fallbackAccountID: accountID
        )
        applyLogoutCleanup(cleanup)
        failClosedSessionState()
    }

    func completeDeletionSignOut() async -> DeletionSessionCleanupResult {
        let firebaseSignedOut: Bool
        if FirebaseConfiguration.isAvailable {
            do {
                try Auth.auth().signOut()
                firebaseSignedOut = true
            } catch {
                firebaseSignedOut = Auth.auth().currentUser == nil
            }
        } else {
            firebaseSignedOut = true
        }
        GIDSignIn.sharedInstance.signOut()
        let cleanup = await sessions.logoutAfterRemoteSignOut(
            fallbackAccountID: accountID
        )
        applyLogoutCleanup(cleanup)
        failClosedSessionState()
        return DeletionSessionCleanupResult(
            firebaseSignedOut: firebaseSignedOut,
            metadataCleared: cleanup.metadataCleared
        )
    }
}

struct DeletionSessionCleanupResult: Sendable {
    let firebaseSignedOut: Bool
    let metadataCleared: Bool
}
