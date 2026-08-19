import Foundation

extension AppShellView {
    func handleExplicitQARoute() -> Bool {
        if ProcessInfo.processInfo.environment[
            "QA_PROGRESS_ROUTE"
        ] == "1" {
            navigation.select(.settings)
            navigation.push(.tabDetail(.settings))
            return true
        }
        if ProcessInfo.processInfo.environment[
            "QA_INVENTORY_ROUTE"
        ] == "1" {
            navigation.select(.storage)
            return true
        }
        if ProcessInfo.processInfo.environment[
            "QA_MINIHOME_ROUTE"
        ] == "1" {
            navigation.push(.miniHome)
            return true
        }
        if ProcessInfo.processInfo.environment[
            "QA_MANUAL_REGISTRATION"
        ] == "1" {
            navigation.push(.manualRegistration)
            return true
        }
        guard let plantID = ProcessInfo.processInfo.environment[
            "QA_NOTIFICATION_PLANT_ID"
        ] else {
            return false
        }
        let availability: RouteTargetAvailability =
            ProcessInfo.processInfo.environment["QA_TARGET_DELETED"] == "1"
                ? .deleted
                : .available
        navigation.handle(
            .plant(rawTarget: plantID),
            authentication: authenticationState,
            targetAvailability: availability
        )
        showsLogin = navigation.pendingAuthenticationRoute != nil
        return true
    }
}
