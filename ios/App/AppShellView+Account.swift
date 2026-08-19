import Foundation

extension AppShellView {
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

    func mountAccountStores() {
        LocalPlantCollectionStore.shared.mount(accountID: accountScopeID)
        LocalNotificationScheduleStore.shared.mount(accountID: accountScopeID)
        LocalNotificationPreferenceStore.shared.mount(accountID: accountScopeID)
        LocalWeatherAlertStore.shared.mount(accountID: accountScopeID)
    }
}
