import Foundation
import PlanteriorData
import PlanteriorDomain

extension SettingsView {
    func reloadPresentedValues() {
        wateringEnabled = LocalNotificationPreferenceStore.shared.global?.enabled
            ?? true
        quietHoursSummary = QuietHoursPresentation.summary(
            LocalNotificationPreferenceStore.shared.quietHours
        )
        regionName = Self.fullRegionName(weather.manualRegionCode)
    }

    func setWateringNotificationsEnabled(_ enabled: Bool) {
        guard enabled else {
            wateringEnabled = false
            return
        }
        guard notificationAuthorizationRequest == nil else { return }
        wateringEnabled = true
        let request = NotificationAuthorizationRequestContext(
            accountID: accountScopeID
        )
        notificationAuthorizationRequest = request
        Task {
            let authorization = await NotificationRuntimeState
                .requestAuthorizationIfNeeded()
            guard NotificationAuthorizationRequestContext.shouldApply(
                responseFor: request,
                currentRequest: notificationAuthorizationRequest,
                accountID: accountScopeID
            ) else {
                return
            }
            applyNotificationAuthorization(
                authorization,
                requestAttempted: true
            )
            notificationAuthorizationRequest = nil
        }
    }

    func setWeatherNotificationsEnabled(_ enabled: Bool) {
        guard enabled else {
            weather.setGlobalAlertsEnabled(false)
            return
        }
        guard notificationAuthorizationRequest == nil else { return }
        let request = NotificationAuthorizationRequestContext(
            accountID: accountScopeID
        )
        notificationAuthorizationRequest = request
        Task {
            let authorization = await NotificationRuntimeState
                .requestAuthorizationIfNeeded()
            guard NotificationAuthorizationRequestContext.shouldApply(
                responseFor: request,
                currentRequest: notificationAuthorizationRequest,
                accountID: accountScopeID
            ) else {
                return
            }
            applyNotificationAuthorization(authorization)
            weather.setGlobalAlertsEnabled(authorization == .authorized)
            notificationAuthorizationRequest = nil
        }
    }

    func applyNotificationAuthorization(
        _ authorization: NotificationAuthorizationState,
        requestAttempted: Bool = false
    ) {
        LocalNotificationScheduleStore.shared.updateAuthorization(authorization)
        notificationStatus = requestAttempted && authorization != .authorized
            ? "설정에서 허용 필요"
            : Self.notificationText(authorization)
    }

    func reloadNotificationAuthorization() async {
        let state = await NotificationRuntimeState.current()
        applyNotificationAuthorization(state.authorization)
    }

    var notificationAuthorizationRequestInFlight: Bool {
        notificationAuthorizationRequest != nil
    }

    func mountPresentedAccount() {
        weather.mount(accountID: accountScopeID)
        LocalNotificationPreferenceStore.shared.mount(
            accountID: accountScopeID
        )
    }

    var accountScopeID: String? {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_AUTHENTICATED"] == "1" {
                return ProcessInfo.processInfo.environment["QA_ACCOUNT_ID"]
                    ?? "qa-account"
            }
        #endif
        return auth.accountID?.rawValue
    }

    func performDeletionCleanup(ownerID: AccountID) async -> [String] {
        await AccountDeletionLocalCleanup.perform(ownerID: ownerID, auth: auth)
    }

    static func clearAccountDefaults(
        ownerID: AccountID,
        defaults: UserDefaults = .standard
    ) -> Bool {
        let accountSegment = ".\(ownerID.rawValue)."
        let scopedKeys = defaults.dictionaryRepresentation().keys.filter {
            $0.contains(accountSegment)
        }
        scopedKeys.forEach(defaults.removeObject(forKey:))
        return !defaults.dictionaryRepresentation().keys.contains {
            $0.contains(accountSegment)
        }
    }

    static func notificationText(
        _ status: NotificationAuthorizationState
    ) -> String {
        switch status {
        case .authorized: "허용됨"
        case .denied: "설정에서 허용 필요"
        case .notDetermined: "확인 필요"
        }
    }
}
