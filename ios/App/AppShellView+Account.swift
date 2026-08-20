import Foundation
import PlanteriorData

extension AppShellView {
    var authenticationState: AppAuthenticationState {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_AUTHENTICATED"] == "1" {
                return .signedIn
            }
        #endif
        return auth.isSignedIn ? .signedIn : .signedOut
    }

    var accountScopeID: String? {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_AUTHENTICATED"
            ] == "1" {
                return ProcessInfo.processInfo.environment["QA_ACCOUNT_ID"]
                    ?? "qa-account"
            }
        #endif
        return auth.accountID?.rawValue
    }

    func mountAccountStores() async {
        LocalPlantCollectionStore.shared.mount(accountID: accountScopeID)
        LocalNotificationScheduleStore.shared.mount(accountID: accountScopeID)
        LocalNotificationPreferenceStore.shared.mount(accountID: accountScopeID)
        LocalWeatherAlertStore.shared.mount(accountID: accountScopeID)
        guard !auth.isRestoring else {
            return
        }
        await IdentificationDraftStore.shared.mount(accountID: accountScopeID)
    }

    func authorizeAccountAction() -> Bool {
        guard authenticationState == .signedIn else {
            showsLogin = true
            return false
        }
        return true
    }
}
