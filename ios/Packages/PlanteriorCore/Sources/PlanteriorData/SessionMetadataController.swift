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
        try await store.clear()
        metadata = nil
        return accountID.map(AccountCacheSignal.unmount)
    }
}
