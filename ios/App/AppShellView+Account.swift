import Foundation
import PlanteriorData

extension AppShellView {
    var authenticationState: AppAuthenticationState {
        #if DEBUG
            switch ProcessInfo.processInfo.environment["QA_AUTHENTICATED"] {
            case "1":
                return .signedIn
            case "0":
                return .signedOut
            default:
                break
            }
        #endif
        return auth.isSignedIn ? .signedIn : .signedOut
    }

    var accountScopeID: String? {
        #if DEBUG
            switch ProcessInfo.processInfo.environment["QA_AUTHENTICATED"] {
            case "1":
                return ProcessInfo.processInfo.environment["QA_ACCOUNT_ID"]
                    ?? ProcessInfo.processInfo.environment["QA_INVENTORY_ACCOUNT_ID"]
                    ?? "qa-account"
            case "0":
                return nil
            default:
                break
            }
        #endif
        return auth.accountID?.rawValue
    }

    func mountAccountStores() async {
        let mountedAccountID = auth.isRestoring ? nil : accountScopeID
        do {
            try await miniHomeStore.mount(
                accountID: mountedAccountID,
                defaultDraft: MiniHomeView.defaultDraft(
                    updatedAt: MiniHomeView.runtimeInstant()
                )
            )
        } catch {
            await miniHomeStore.mount(accountID: mountedAccountID, defaultDraft: nil)
            miniHomeStore.state = .loadFailed
        }
        WeatherRuntime.prepareSharedAccountRemount(
            accountID: mountedAccountID
        )
        LocalPlantCollectionStore.shared.mount(accountID: mountedAccountID)
        LocalPlantCollectionStore.shared.loadQAFixtureIfNeeded()
        LocalNotificationScheduleStore.shared.mount(accountID: mountedAccountID)
        LocalNotificationPreferenceStore.shared.mount(accountID: mountedAccountID)
        LocalWeatherAlertStore.shared.mount(accountID: mountedAccountID)
        guard !auth.isRestoring else { return }
        await IdentificationDraftStore.shared.mount(accountID: mountedAccountID)
    }

    func authorizeAccountAction() -> Bool {
        guard authenticationState == .signedIn else {
            showsLogin = true
            return false
        }
        return true
    }
}
