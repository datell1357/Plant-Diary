import PlanteriorDomain

public actor SessionMetadataController {
    private let store: any SessionMetadataPersisting
    private var metadata: SessionMetadata?

    public init(store: any SessionMetadataPersisting) {
        self.store = store
    }

    public func establish(
        accountID: AccountID,
        provider: AuthProvider,
        isNewAccount: Bool
    ) async throws -> AuthTransition {
        let saved = try await store.load()
        let previous = metadata?.accountID ?? saved?.accountID
        let next = SessionMetadata(accountID: accountID, provider: provider)
        try await store.save(next)
        metadata = next
        let cacheSignal: AccountCacheSignal = previous.map {
            $0 == accountID ? .mount(accountID) : .isolate(previous: $0, current: accountID)
        } ?? .mount(accountID)
        return AuthTransition(
            metadata: next,
            signupCompletion: isNewAccount ? .profileCreated : .existingAccount,
            cacheSignal: cacheSignal,
            resumedRoute: nil
        )
    }

    public func restore(authenticatedAccountID: AccountID) async throws -> AuthTransition? {
        guard let saved = try await store.load() else {
            return nil
        }
        guard saved.accountID == authenticatedAccountID else {
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
        let accountID = metadata?.accountID ?? saved?.accountID
        metadata = nil
        try await store.clear()
        return accountID.map(AccountCacheSignal.unmount)
    }

    public func logoutAfterRemoteSignOut(
        fallbackAccountID: AccountID?
    ) async -> SessionCleanupResult {
        let saved = try? await store.load()
        let accountID = metadata?.accountID ?? saved?.accountID
            ?? fallbackAccountID
        metadata = nil
        do {
            try await store.clear()
            return SessionCleanupResult(
                cacheSignal: accountID.map(AccountCacheSignal.unmount),
                metadataCleared: true
            )
        } catch {
            return SessionCleanupResult(
                cacheSignal: accountID.map(AccountCacheSignal.unmount),
                metadataCleared: false
            )
        }
    }
}
