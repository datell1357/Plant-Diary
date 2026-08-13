@testable import PlanteriorData
import PlanteriorDomain

actor ProviderFake: SocialAuthProviding {
    enum Response: Sendable {
        case success
        case cancelled
        case failed(String)
    }

    let provider: AuthProvider
    let response: Response
    private var challenges: [String?] = []

    init(provider: AuthProvider, response: Response) {
        self.provider = provider
        self.response = response
    }

    func authorize(challenge: String?) async -> ProviderAuthorization {
        challenges.append(challenge)
        return switch response {
        case .cancelled: .cancelled
        case let .failed(code): .failed(code: code)
        case .success:
            if provider == .apple {
                .credential(.apple(identityToken: "apple-token", nonceDigest: challenge ?? ""))
            } else {
                .credential(.google(idToken: "google-token", accessToken: "google-access"))
            }
        }
    }

    func receivedChallenges() -> [String?] {
        challenges
    }
}

actor MetadataStoreFake: SessionMetadataPersisting {
    private var metadata: SessionMetadata?

    init(initial: SessionMetadata? = nil) {
        metadata = initial
    }

    func load() async throws -> SessionMetadata? {
        metadata
    }

    func save(_ metadata: SessionMetadata) async throws {
        self.metadata = metadata
    }

    func clear() async throws {
        metadata = nil
    }

    func value() -> SessionMetadata? {
        metadata
    }
}

actor BackendFake: SocialAuthBackend {
    private var account: BackendAuthAccount
    private let restoredID: AccountID?
    private var requests: [(credential: SocialCredential, rawNonce: String?)] = []
    private var profiles: [AccountID] = []
    private var logoutCount = 0

    init(signInAccount: BackendAuthAccount, restoredID: AccountID? = nil) {
        account = signInAccount
        self.restoredID = restoredID
    }

    func signIn(
        credential: SocialCredential,
        rawNonce: String?
    ) async throws -> BackendAuthAccount {
        requests.append((credential, rawNonce))
        return account
    }

    func restoreAccountID() async throws -> AccountID? {
        restoredID
    }

    func createProfile(accountID: AccountID) async throws {
        profiles.append(accountID)
    }

    func signOut() async throws {
        logoutCount += 1
    }

    func setSignInAccount(_ account: BackendAuthAccount) {
        self.account = account
    }

    func signInRequests() -> [(credential: SocialCredential, rawNonce: String?)] {
        requests
    }

    func profileCreations() -> [AccountID] {
        profiles
    }

    func signOutCount() -> Int {
        logoutCount
    }
}
