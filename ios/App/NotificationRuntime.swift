import Foundation
import PlanteriorData
import UserNotifications

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
