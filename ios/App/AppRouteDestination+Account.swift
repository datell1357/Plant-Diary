import Foundation

extension AppRouteDestination {
    var accountScopeID: String? {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_AUTHENTICATED"
            ] == "1" {
                return "qa-account"
            }
        #endif
        return auth.accountID?.rawValue
    }
}
