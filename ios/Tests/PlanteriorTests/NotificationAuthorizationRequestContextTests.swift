import Foundation
@testable import Planterior
import Testing

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
}
