import Foundation
@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct AuthSessionTests {
    @Test(arguments: [
        (AuthProvider.apple, true), (.apple, false),
        (.google, true), (.google, false)
    ])
    func socialProvidersHandleNewAndExistingAccounts(
        provider: AuthProvider,
        isNew: Bool
    ) async throws {
        let id = try AccountID.parse("\(provider.rawValue)-\(isNew ? "new" : "existing")")
        let backend = BackendFake(signInAccount: .init(id: id, isNewAccount: isNew))
        let store = MetadataStoreFake()
        let session = makeSession(backend: backend, store: store)
        let client = ProviderFake(provider: provider, response: .success)

        let result = try await session.signIn(with: client)
        let expectedSignup: SignupCompletion = isNew ? .profileCreated : .existingAccount
        let transition = AuthTransition(
            metadata: .init(accountID: id, provider: provider),
            signupCompletion: expectedSignup,
            cacheSignal: .mount(id),
            resumedRoute: nil
        )
        #expect(result == .succeeded(transition))
        #expect(await backend.profileCreations() == (isNew ? [id] : []))
        let request = try #require(await backend.signInRequests().first)
        #expect(request.credential.provider == provider)
        #expect((request.rawNonce != nil) == (provider == .apple))
        let challenge = await client.receivedChallenges().first ?? nil
        #expect((challenge != nil) == (provider == .apple))
    }

    @Test
    func nonceIsHashedAndMismatchConsumesChallenge() async throws {
        let vault = CryptographicNonceVault(generator: { "raw-nonce" })
        let issued = try await vault.issue()

        #expect(issued.digest.count == 64)
        #expect(issued.digest != issued.rawValue)
        let tampered = AuthNonce(rawValue: "other", digest: issued.digest)
        await #expect(throws: AuthSessionError.nonceMismatch) {
            try await vault.consume(tampered)
        }
        await #expect(throws: AuthSessionError.nonceMismatch) {
            try await vault.consume(issued)
        }
    }

    @Test
    func cancellationAndProviderFailureDoNotCreateSessions() async throws {
        let accountID = try AccountID.parse("account-a")
        let backend = BackendFake(
            signInAccount: .init(id: accountID, isNewAccount: false)
        )
        let store = MetadataStoreFake()
        let session = makeSession(backend: backend, store: store)

        let cancelled = ProviderFake(provider: .apple, response: .cancelled)
        #expect(try await session.signIn(with: cancelled) == .cancelled)
        #expect(
            try await session.signIn(
                with: ProviderFake(provider: .google, response: .failed("offline"))
            )
                == .providerFailed(provider: .google, code: "offline")
        )
        #expect(await backend.signInRequests().isEmpty)
        #expect(await store.value() == nil)
    }

    @Test
    func coldRestorePersistsOnlyMetadataAndLogoutUnmountsAccount() async throws {
        let id = try AccountID.parse("account-a")
        let metadata = SessionMetadata(accountID: id, provider: .apple)
        let store = MetadataStoreFake(initial: metadata)
        let backend = BackendFake(
            signInAccount: .init(id: id, isNewAccount: false),
            restoredID: id
        )
        let session = makeSession(backend: backend, store: store)

        let restored = AuthTransition(
            metadata: metadata,
            signupCompletion: .existingAccount,
            cacheSignal: .mount(id),
            resumedRoute: nil
        )
        #expect(try await session.restore() == restored)
        let encoded = try JSONEncoder().encode(metadata)
        let object = try #require(
            JSONSerialization.jsonObject(with: encoded) as? [String: String]
        )
        #expect(Set(object.keys) == ["accountID", "provider"])
        #expect(try await session.logout() == .unmount(id))
        #expect(await store.value() == nil)
        #expect(await backend.signOutCount() == 1)
    }

    @Test
    func remoteSignOutClearsPendingRouteWhenMetadataCleanupFails() async throws {
        let accountA = try AccountID.parse("account-a")
        let accountB = try AccountID.parse("account-b")
        let store = MetadataStoreFake(
            initial: SessionMetadata(accountID: accountA, provider: .apple),
            failsClear: true
        )
        let backend = BackendFake(
            signInAccount: .init(id: accountB, isNewAccount: false)
        )
        let session = makeSession(backend: backend, store: store)
        #expect(await session.holdPendingPlantRoute(rawTarget: "private-plant"))

        await #expect(throws: MetadataStoreFakeError.clearFailed) {
            try await session.logout()
        }
        let result = try await session.signIn(
            with: ProviderFake(provider: .google, response: .success)
        )
        guard case let .succeeded(transition) = result else {
            Issue.record("sign-in should succeed after remote sign-out")
            return
        }
        #expect(transition.resumedRoute == nil)
    }

    @Test
    func accountSwitchSignalsIsolationAndResumesOnlySanitizedRoute() async throws {
        let accountA = try AccountID.parse("account-a")
        let accountB = try AccountID.parse("account-b")
        let backend = BackendFake(signInAccount: .init(id: accountA, isNewAccount: false))
        let session = makeSession(backend: backend, store: MetadataStoreFake())
        _ = try await session.signIn(with: ProviderFake(provider: .google, response: .success))
        await backend.setSignInAccount(.init(id: accountB, isNewAccount: false))

        let acceptsUnsafe = await session.holdPendingPlantRoute(
            rawTarget: "plant-a?token=secret"
        )
        #expect(acceptsUnsafe == false)
        #expect(await session.holdPendingPlantRoute(rawTarget: "plant-a"))
        let apple = ProviderFake(provider: .apple, response: .success)
        let result = try await session.signIn(with: apple)
        let transition = try AuthTransition(
            metadata: .init(accountID: accountB, provider: .apple),
            signupCompletion: .existingAccount,
            cacheSignal: .isolate(previous: accountA, current: accountB),
            resumedRoute: .plant(PersonalPlantID.parse("plant-a"))
        )
        #expect(result == .succeeded(transition))
    }

    private func makeSession(backend: BackendFake, store: MetadataStoreFake) -> AuthSession {
        AuthSession(
            backend: backend,
            metadataStore: store,
            nonceVault: .init(generator: { "fixed-raw-nonce" })
        )
    }
}
