struct PlantRouteTarget: Hashable, RawRepresentable, Sendable {
    let rawValue: String

    init?(rawValue: String) {
        guard
            (1 ... 64).contains(rawValue.count),
            rawValue.utf8.allSatisfy({
                (48 ... 57).contains($0)
                    || (65 ... 90).contains($0)
                    || (97 ... 122).contains($0)
                    || $0 == 45
                    || $0 == 95
            })
        else {
            return nil
        }

        self.rawValue = rawValue
    }
}

enum AppRoute: Hashable, Sendable {
    case tabDetail(AppTab)
    case plant(PlantRouteTarget)
    case miniHome
    case identificationDraft
    case manualRegistration
    case unavailable

    var destinationTab: AppTab {
        switch self {
        case let .tabDetail(tab): tab
        case .miniHome: .home
        case .plant: .collection
        case .identificationDraft, .manualRegistration: .collection
        case .unavailable: .home
        }
    }

    var requiresAuthentication: Bool {
        if case .plant = self {
            true
        } else if case .miniHome = self {
            true
        } else {
            false
        }
    }
}

enum IncomingAppRoute: Hashable, Sendable {
    case route(AppRoute)
    case plant(rawTarget: String)
    case invalid
}

enum AppAuthenticationState: Equatable, Sendable {
    case signedIn
    case signedOut
}

enum RouteTargetAvailability: Equatable, Sendable {
    case available
    case deleted
}
