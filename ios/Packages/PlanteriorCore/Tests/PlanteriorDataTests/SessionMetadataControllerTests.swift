@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct SessionMetadataControllerTests {
    @Test
    func emitsAccountIsolationRestoreAndUnmountTransitions() async throws {
        let first = try AccountID.parse("account-a")
        let second = try AccountID.parse("account-b")
        let store = MetadataStoreFake()
        let sessions = SessionMetadataController(store: store)

        let initial = try await sessions.establish(
            accountID: first,
            provider: .apple,
            isNewAccount: true
        )
        #expect(initial.signupCompletion == .profileCreated)
        #expect(initial.cacheSignal == .mount(first))

        let switched = try await sessions.establish(
            accountID: second,
            provider: .google,
            isNewAccount: false
        )
        #expect(switched.cacheSignal == .isolate(previous: first, current: second))

        let restored = try await sessions.restore(authenticatedAccountID: second)
        #expect(restored?.cacheSignal == .mount(second))
        #expect(try await sessions.logout() == .unmount(second))
    }
}
