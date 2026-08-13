import AuthenticationServices
import CryptoKit
import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation
import GoogleSignIn
import PlanteriorData
import PlanteriorDomain
import UIKit

@MainActor
final class AuthRuntime: NSObject, ObservableObject {
    @Published private(set) var isSignedIn = false
    @Published private(set) var accountID: AccountID?
    @Published private(set) var cacheSignal: AccountCacheSignal?
    @Published private(set) var errorMessage: String?
    private var nonce: String?
    private let sessions: SessionMetadataController

    override init() {
        sessions = SessionMetadataController(
            store: KeychainSessionMetadataStore(service: "com.planterior.helper.auth")
        )
        super.init()
    }

    func restore() async {
        guard FirebaseConfiguration.isAvailable else {
            return
        }
        guard let user = Auth.auth().currentUser,
              let parsedID = try? AccountID.parse(user.uid)
        else {
            return
        }
        do {
            guard let transition = try await sessions.restore(
                authenticatedAccountID: parsedID
            ) else {
                try Auth.auth().signOut()
                return
            }
            accountID = parsedID
            cacheSignal = transition.cacheSignal
            isSignedIn = true
        } catch {
            try? Auth.auth().signOut()
            errorMessage = "저장된 로그인 정보를 확인할 수 없어요."
        }
    }

    func beginApple(_ request: ASAuthorizationAppleIDRequest) {
        let rawNonce = Self.randomNonce()
        nonce = rawNonce
        request.requestedScopes = [.fullName, .email]
        request.nonce = Self.sha256(rawNonce)
    }

    func completeApple(_ authorization: ASAuthorization) async {
        guard FirebaseConfiguration.isAvailable,
              let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let nonce,
              let tokenData = credential.identityToken,
              let token = String(data: tokenData, encoding: .utf8)
        else {
            nonce = nil
            errorMessage = "Apple 로그인 정보를 확인할 수 없어요."
            return
        }
        self.nonce = nil
        do {
            let firebaseCredential = OAuthProvider.appleCredential(
                withIDToken: token,
                rawNonce: nonce,
                fullName: credential.fullName
            )
            let result = try await Auth.auth().signIn(with: firebaseCredential)
            try await establishSession(
                result: result,
                provider: .apple
            )
        } catch {
            errorMessage = "Apple 로그인에 실패했어요."
        }
    }

    func cancelApple() {
        nonce = nil
    }

    func reportAppleFailure() {
        errorMessage = "Apple 로그인에 실패했어요."
    }

    func signInWithGoogle(presenting viewController: UIViewController) async {
        guard FirebaseConfiguration.isAvailable,
              let clientID = FirebaseApp.app()?.options.clientID
        else {
            errorMessage = "Google 로그인을 사용할 수 없어요."
            return
        }
        do {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
            let result = try await GIDSignIn.sharedInstance.signIn(
                withPresenting: viewController
            )
            guard let idToken = result.user.idToken?.tokenString else {
                errorMessage = "Google 로그인 정보를 확인할 수 없어요."
                return
            }
            let credential = GoogleAuthProvider.credential(
                withIDToken: idToken,
                accessToken: result.user.accessToken.tokenString
            )
            let authResult = try await Auth.auth().signIn(with: credential)
            try await establishSession(
                result: authResult,
                provider: .google
            )
        } catch {
            errorMessage = (error as NSError).code == GIDSignInError.canceled.rawValue
                ? nil
                : "Google 로그인에 실패했어요."
        }
    }

    func signOut() async {
        guard FirebaseConfiguration.isAvailable else {
            return
        }
        do {
            try Auth.auth().signOut()
            cacheSignal = try await sessions.logout()
        } catch {
            errorMessage = "로그아웃에 실패했어요."
            return
        }
        GIDSignIn.sharedInstance.signOut()
        accountID = nil
        isSignedIn = false
    }

    private func establishSession(
        result: AuthDataResult,
        provider: AuthProvider
    ) async throws {
        let parsedID = try AccountID.parse(result.user.uid)
        let isNewAccount = result.additionalUserInfo?.isNewUser == true
        if isNewAccount {
            try await Firestore.firestore()
                .collection("users")
                .document(result.user.uid)
                .setData(
                    [
                        "authProvider": provider.rawValue,
                        "createdAt": FieldValue.serverTimestamp()
                    ],
                    merge: true
                )
        }
        let transition = try await sessions.establish(
            accountID: parsedID,
            provider: provider,
            isNewAccount: isNewAccount
        )
        accountID = parsedID
        cacheSignal = transition.cacheSignal
        isSignedIn = true
        errorMessage = nil
    }

    private static func randomNonce(length: Int = 32) -> String {
        let alphabet = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        while result.count < length {
            var random: UInt8 = 0
            guard SecRandomCopyBytes(kSecRandomDefault, 1, &random) == errSecSuccess else {
                continue
            }
            if Int(random) < alphabet.count {
                result.append(alphabet[Int(random)])
            }
        }
        return result
    }

    private static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

enum FirebaseConfiguration {
    static var isAvailable: Bool {
        Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil
    }
}
