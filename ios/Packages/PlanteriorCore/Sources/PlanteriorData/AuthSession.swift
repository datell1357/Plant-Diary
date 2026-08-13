import CryptoKit
import Foundation
import PlanteriorDomain

public struct AuthNonce: Equatable, Sendable {
    public let rawValue: String
    public let digest: String

    public init(rawValue: String, digest: String) {
        self.rawValue = rawValue
        self.digest = digest
    }
}

public enum SecureNonceGenerator {
    public static func generate() async throws -> String {
        SymmetricKey(size: .bits256).withUnsafeBytes {
            Data($0).map { String(format: "%02x", $0) }.joined()
        }
    }
}

public actor CryptographicNonceVault {
    private let generator: @Sendable () async throws -> String
    private var pending: AuthNonce?

    public init(
        generator: @escaping @Sendable () async throws -> String =
            SecureNonceGenerator.generate
    ) {
        self.generator = generator
    }

    public func issue() async throws -> AuthNonce {
        let raw = try await generator()
        let nonce = AuthNonce(rawValue: raw, digest: Self.digest(raw))
        pending = nonce
        return nonce
    }

    public func consume(_ nonce: AuthNonce) throws -> String {
        defer { pending = nil }
        guard pending == nonce, Self.digest(nonce.rawValue) == nonce.digest else {
            throw AuthSessionError.nonceMismatch
        }
        return nonce.rawValue
    }

    public func discard() {
        pending = nil
    }

    private static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

public actor AuthSession {
    private let backend: any SocialAuthBackend
    private let store: any SessionMetadataPersisting
    private let nonces: CryptographicNonceVault
    private var metadata: SessionMetadata?
    private var pendingRoute: SanitizedPendingRoute?

    public init(
        backend: any SocialAuthBackend,
        metadataStore: any SessionMetadataPersisting,
        nonceVault: CryptographicNonceVault
    ) {
        self.backend = backend
        store = metadataStore
        nonces = nonceVault
    }

    public func signIn(with client: any SocialAuthProviding) async throws -> AuthAttemptResult {
        let nonce = try await client.provider == .apple ? nonces.issue() : nil
        let authorization = await client.authorize(challenge: nonce?.digest)
        guard case let .credential(credential) = authorization else {
            await nonces.discard()
            switch authorization {
            case .cancelled: return .cancelled
            case let .failed(code): return .providerFailed(provider: client.provider, code: code)
            case .credential: preconditionFailure()
            }
        }
        guard credential.provider == client.provider else {
            await nonces.discard()
            throw AuthSessionError.credentialProviderMismatch
        }
        let rawNonce = try await consume(nonce, credential)
        let account = try await backend.signIn(credential: credential, rawNonce: rawNonce)
        let saved = try await store.load()
        let previous = metadata?.accountID ?? saved?.accountID
        if account.isNewAccount {
            try await backend.createProfile(accountID: account.id)
        }
        let next = SessionMetadata(accountID: account.id, provider: client.provider)
        try await store.save(next)
        metadata = next
        let cache: AccountCacheSignal = previous.map {
            $0 == account.id ? .mount(account.id) : .isolate(previous: $0, current: account.id)
        } ?? .mount(account.id)
        let transition = AuthTransition(
            metadata: next,
            signupCompletion: account.isNewAccount ? .profileCreated : .existingAccount,
            cacheSignal: cache,
            resumedRoute: pendingRoute
        )
        pendingRoute = nil
        return .succeeded(transition)
    }

    public func restore() async throws -> AuthTransition? {
        guard let saved = try await store.load() else { return nil }
        guard try await backend.restoreAccountID() == saved.accountID else {
            try await store.clear()
            throw AuthSessionError.restoredSessionMismatch
        }
        metadata = saved
        return AuthTransition(
            metadata: saved,
            signupCompletion: .existingAccount,
            cacheSignal: .mount(saved.accountID),
            resumedRoute: nil
        )
    }

    public func logout() async throws -> AccountCacheSignal? {
        let saved = try await store.load()
        let id = metadata?.accountID ?? saved?.accountID
        try await backend.signOut()
        try await store.clear()
        metadata = nil
        pendingRoute = nil
        return id.map(AccountCacheSignal.unmount)
    }

    public func holdPendingPlantRoute(rawTarget: String) -> Bool {
        pendingRoute = (try? PersonalPlantID.parse(rawTarget))
            .map(SanitizedPendingRoute.plant)
        return pendingRoute != nil
    }

    private func consume(
        _ nonce: AuthNonce?,
        _ credential: SocialCredential
    ) async throws -> String? {
        guard let nonce else {
            return nil
        }
        guard case let .apple(_, digest) = credential, digest == nonce.digest else {
            await nonces.discard()
            throw AuthSessionError.nonceMismatch
        }
        return try await nonces.consume(nonce)
    }
}
