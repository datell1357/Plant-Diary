import Foundation

extension AppShellView {
    func handleQARouteIfPresent() {
        #if DEBUG
            if handleExplicitQARoute() {
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
