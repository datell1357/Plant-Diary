import FirebaseCore
import Foundation
import GoogleSignIn
import PlanteriorDesignSystem
import SwiftUI

@main
struct PlanteriorApp: App {
    @StateObject private var auth = AuthRuntime()

    init() {
        if FirebaseConfiguration.isAvailable {
            FirebaseApp.configure()
        }
    }

    var body: some Scene {
        WindowGroup {
            AppShellView()
                .environmentObject(auth)
                .task {
                    await auth.restore()
                }
                .onOpenURL {
                    _ = GIDSignIn.sharedInstance.handle($0)
                }
        }
    }
}

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
    case identificationDraft
    case manualRegistration
    case unavailable

    var destinationTab: AppTab {
        switch self {
        case let .tabDetail(tab): tab
        case .plant: .collection
        case .identificationDraft, .manualRegistration: .collection
        case .unavailable: .home
        }
    }

    var requiresAuthentication: Bool {
        if case .plant = self {
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

struct AppNavigationState {
    private(set) var selectedTab: AppTab = .home
    var homePath: [AppRoute] = []
    var collectionPath: [AppRoute] = []
    var storagePath: [AppRoute] = []
    var settingsPath: [AppRoute] = []
    private(set) var isCameraPresented = false
    private(set) var pendingAuthenticationRoute: AppRoute?

    mutating func select(_ tab: AppTab) {
        selectedTab = tab
    }

    mutating func push(_ route: AppRoute) {
        selectedTab = route.destinationTab
        switch route.destinationTab {
        case .home: homePath.append(route)
        case .collection: collectionPath.append(route)
        case .storage: storagePath.append(route)
        case .settings: settingsPath.append(route)
        }
    }

    func path(for tab: AppTab) -> [AppRoute] {
        switch tab {
        case .home: homePath
        case .collection: collectionPath
        case .storage: storagePath
        case .settings: settingsPath
        }
    }

    mutating func presentCamera() {
        isCameraPresented = true
    }

    mutating func dismissCamera() {
        isCameraPresented = false
    }

    mutating func handle(
        _ incomingRoute: IncomingAppRoute,
        authentication: AppAuthenticationState,
        targetAvailability: RouteTargetAvailability
    ) {
        let route: AppRoute
        switch incomingRoute {
        case let .route(typedRoute):
            route = typedRoute
        case let .plant(rawTarget):
            guard let target = PlantRouteTarget(rawValue: rawTarget) else {
                showUnavailable()
                return
            }
            route = .plant(target)
        case .invalid:
            showUnavailable()
            return
        }

        guard !route.requiresAuthentication || authentication == .signedIn else {
            pendingAuthenticationRoute = route
            return
        }

        open(route, targetAvailability: targetAvailability)
    }

    mutating func completeAuthentication(targetAvailability: RouteTargetAvailability) {
        guard let pendingAuthenticationRoute else {
            return
        }

        self.pendingAuthenticationRoute = nil
        open(pendingAuthenticationRoute, targetAvailability: targetAvailability)
    }

    private mutating func open(
        _ route: AppRoute,
        targetAvailability: RouteTargetAvailability
    ) {
        guard targetAvailability == .available else {
            showUnavailable()
            return
        }
        push(route)
    }

    private mutating func showUnavailable() {
        pendingAuthenticationRoute = nil
        selectedTab = .home
        homePath = [.unavailable]
    }
}
