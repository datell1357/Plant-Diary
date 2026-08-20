import PlanteriorDomain

public enum AuthProvider: String, Codable, Sendable {
    case apple
    case google
}

public enum SocialCredential: Equatable, Sendable {
    case apple(identityToken: String, nonceDigest: String)
    case google(idToken: String, accessToken: String)

    public var provider: AuthProvider {
        switch self {
        case .apple: .apple
        case .google: .google
        }
    }
}

public enum ProviderAuthorization: Equatable, Sendable {
    case credential(SocialCredential)
    case cancelled
    case failed(code: String)
}

public protocol SocialAuthProviding: Sendable {
    var provider: AuthProvider { get }
    func authorize(challenge: String?) async -> ProviderAuthorization
}

public struct BackendAuthAccount: Equatable, Sendable {
    public let id: AccountID
    public let isNewAccount: Bool
}

public protocol SocialAuthBackend: Sendable {
    func signIn(
        credential: SocialCredential,
        rawNonce: String?
    ) async throws -> BackendAuthAccount
    func restoreAccountID() async throws -> AccountID?
    func createProfile(accountID: AccountID) async throws
    func signOut() async throws
}

public struct SessionMetadata: Codable, Equatable, Sendable {
    public let accountID: AccountID
    public let provider: AuthProvider

    public init(accountID: AccountID, provider: AuthProvider) {
        self.accountID = accountID
        self.provider = provider
    }
}

public protocol SessionMetadataPersisting: Sendable {
    func load() async throws -> SessionMetadata?
    func save(_ metadata: SessionMetadata) async throws
    func clear() async throws
}

public enum SignupCompletion: Equatable, Sendable {
    case existingAccount
    case profileCreated
}

public enum AccountCacheSignal: Equatable, Sendable {
    case mount(AccountID)
    case isolate(previous: AccountID, current: AccountID)
    case unmount(AccountID)
}

public struct SessionCleanupResult: Equatable, Sendable {
    public let cacheSignal: AccountCacheSignal?
    public let metadataCleared: Bool

    public init(
        cacheSignal: AccountCacheSignal?,
        metadataCleared: Bool
    ) {
        self.cacheSignal = cacheSignal
        self.metadataCleared = metadataCleared
    }
}

public enum SanitizedPendingRoute: Equatable, Sendable {
    case plant(PersonalPlantID)
}

public struct AuthTransition: Equatable, Sendable {
    public let metadata: SessionMetadata
    public let signupCompletion: SignupCompletion
    public let cacheSignal: AccountCacheSignal
    public let resumedRoute: SanitizedPendingRoute?
}

public enum AuthAttemptResult: Equatable, Sendable {
    case succeeded(AuthTransition)
    case cancelled
    case providerFailed(provider: AuthProvider, code: String)
}

public enum AuthSessionError: Error, Equatable, Sendable {
    case nonceMismatch
    case credentialProviderMismatch
    case restoredSessionMismatch
}
