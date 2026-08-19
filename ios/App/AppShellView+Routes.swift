import Foundation

extension AppShellView {
    func handleQARouteIfPresent() {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_MINIHOME_ROUTE"
            ] == "1" {
                navigation.push(.miniHome)
                return
            }
            if ProcessInfo.processInfo.environment["QA_MANUAL_REGISTRATION"] == "1" {
                navigation.push(.manualRegistration)
                return
            }
            if let plantID = ProcessInfo.processInfo.environment[
                "QA_NOTIFICATION_PLANT_ID"
            ] {
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
                return
            }
        #endif
        guard let rawURL = ProcessInfo.processInfo.environment["QA_DEEP_LINK"],
              let url = URL(string: rawURL)
        else {
            return
        }
        let availability: RouteTargetAvailability =
            ProcessInfo.processInfo.environment["QA_TARGET_DELETED"] == "1"
                ? .deleted
                : .available
        navigation.handle(
            AppURLRoute.parse(url),
            authentication: authenticationState,
            targetAvailability: availability
        )
        showsLogin = navigation.pendingAuthenticationRoute != nil
    }

    func targetAvailability(
        for incomingRoute: IncomingAppRoute
    ) -> RouteTargetAvailability {
        guard case let .plant(rawTarget) = incomingRoute else {
            return .available
        }
        return LocalPlantCollectionStore.shared.containsRouteTarget(rawTarget)
            ? .available
            : .deleted
    }

    func pendingTargetAvailability() -> RouteTargetAvailability {
        guard case let .plant(target) = navigation.pendingAuthenticationRoute
        else {
            return .available
        }
        return LocalPlantCollectionStore.shared.containsRouteTarget(
            target.rawValue
        )
            ? .available
            : .deleted
    }
}
