import Foundation
import PlanteriorData
import UserNotifications

struct NotificationAuthorizationRequestContext: Equatable {
    let accountID: String?
    let id = UUID()

    func matches(accountID: String?) -> Bool {
        self.accountID == accountID
    }

    static func shouldApply(
        responseFor request: Self,
        currentRequest: Self?,
        accountID: String?
    ) -> Bool {
        currentRequest == request && request.matches(accountID: accountID)
    }
}

struct NotificationRuntimeState {
    let authorization: NotificationAuthorizationState
    let endpoint: NotificationEndpointState

    static let initial = NotificationRuntimeState(
        authorization: .notDetermined,
        endpoint: .unavailable
    )

    static func current(
        processInfo: ProcessInfo = .processInfo
    ) async -> NotificationRuntimeState {
        #if DEBUG
            if let rawAuthorization = processInfo.environment[
                "QA_NOTIFICATION_AUTHORIZATION"
            ] {
                return NotificationRuntimeState(
                    authorization: authorizationState(rawAuthorization),
                    endpoint:
                    processInfo.environment["QA_NOTIFICATION_ENDPOINT"] == "registered"
                        ? .registered
                        : .unavailable
                )
            }
        #endif
        let settings = await UNUserNotificationCenter.current()
            .notificationSettings()
        return NotificationRuntimeState(
            authorization: authorizationState(settings.authorizationStatus),
            endpoint: .unavailable
        )
    }

    static func requestAuthorizationIfNeeded(
        processInfo: ProcessInfo = .processInfo
    ) async -> NotificationAuthorizationState {
        #if DEBUG
            if let rawAuthorization = processInfo.environment[
                "QA_NOTIFICATION_AUTHORIZATION"
            ] {
                let authorization = authorizationState(rawAuthorization)
                guard authorization == .notDetermined else {
                    return authorization
                }
                guard let requestResult = processInfo.environment[
                    "QA_NOTIFICATION_REQUEST_RESULT"
                ] else {
                    return .notDetermined
                }
                return authorizationState(requestResult)
            }
        #endif

        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        let authorization = authorizationState(settings.authorizationStatus)
        guard authorization == .notDetermined else {
            return authorization
        }
        do {
            let granted = try await center.requestAuthorization(
                options: [.alert, .badge, .sound]
            )
            return granted ? .authorized : .denied
        } catch {
            return await current(processInfo: processInfo).authorization
        }
    }

    private static func authorizationState(
        _ value: String
    ) -> NotificationAuthorizationState {
        switch value {
        case "authorized": .authorized
        case "denied": .denied
        default: .notDetermined
        }
    }

    private static func authorizationState(
        _ status: UNAuthorizationStatus
    ) -> NotificationAuthorizationState {
        switch status {
        case .authorized, .provisional, .ephemeral:
            .authorized
        case .denied:
            .denied
        case .notDetermined:
            .notDetermined
        @unknown default:
            .notDetermined
        }
    }
}
