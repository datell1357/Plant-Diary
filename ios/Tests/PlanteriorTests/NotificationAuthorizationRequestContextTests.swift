import Foundation
@testable import Planterior
import Testing
import UserNotifications

struct NotificationRequestContextTests {
    @Test
    func responseForOldAccountDoesNotMatchAfterRemount() {
        let request = NotificationAuthorizationRequestContext(accountID: "account-a")

        let matchesCurrentAccount = NotificationAuthorizationRequestContext
            .shouldApply(
                responseFor: request,
                currentRequest: nil,
                accountID: "account-b"
            )

        #expect(!matchesCurrentAccount)
    }

    @Test
    func responseForRequestingAccountMatchesBeforeRemount() {
        let request = NotificationAuthorizationRequestContext(accountID: "account-a")

        let matchesCurrentAccount = NotificationAuthorizationRequestContext
            .shouldApply(
                responseFor: request,
                currentRequest: request,
                accountID: "account-a"
            )

        #expect(matchesCurrentAccount)
    }

    @Test
    func provisionalAuthorizationAllowsLocalDelivery() {
        #expect(
            NotificationRuntimeState.authorizationState(.provisional)
                == .authorized
        )
    }
}
