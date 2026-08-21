import FirebaseAppCheck
import FirebaseCore
import Foundation
import GoogleSignIn
import PlanteriorDesignSystem
import SwiftUI

@main
struct PlanteriorApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var auth = AuthRuntime()

    init() {
        if FirebaseConfiguration.isAvailable {
            #if DEBUG
                AppCheck.setAppCheckProviderFactory(
                    AppCheckDebugProviderFactory()
                )
            #else
                AppCheck.setAppCheckProviderFactory(
                    AppAttestProviderFactory()
                )
            #endif
            FirebaseApp.configure()
        }
    }

    var body: some Scene {
        WindowGroup {
            AppShellView()
                .environmentObject(auth)
                .task {
                    await auth.restore()
                    await AccountDeletionRecoveryRuntime.refresh(auth: auth)
                }
                .onChange(of: scenePhase) { _, phase in
                    guard phase == .active else { return }
                    Task { await AccountDeletionRecoveryRuntime.refresh(auth: auth) }
                }
                .onOpenURL {
                    _ = GIDSignIn.sharedInstance.handle($0)
                }
        }
    }
}

/// Outcome of a shell affordance tap. Figma `home-screen-logged-out` routes every
/// signed-out tab and camera affordance to the login sheet instead of navigating.
enum ShellAffordanceOutcome: Equatable, Sendable {
    case proceed
    case requiresLogin
}

struct AppNavigationState {
    private(set) var selectedTab: AppTab = .home
    var homePath: [AppRoute] = []
    var collectionPath: [AppRoute] = []
    var storagePath: [AppRoute] = []
    var settingsPath: [AppRoute] = []
    private(set) var isCameraPresented = false
    private(set) var pendingAuthenticationRoute: AppRoute?
    private(set) var pendingAuthenticationAvailability:
        RouteTargetAvailability?

    mutating func select(_ tab: AppTab) {
        selectedTab = tab
    }

    mutating func requestTab(
        _ tab: AppTab,
        authentication: AppAuthenticationState
    ) -> ShellAffordanceOutcome {
        guard authentication == .signedIn else {
            return .requiresLogin
        }
        select(tab)
        return .proceed
    }

    mutating func requestCamera(
        authentication: AppAuthenticationState
    ) -> ShellAffordanceOutcome {
        guard authentication == .signedIn else {
            return .requiresLogin
        }
        presentCamera()
        return .proceed
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
            pendingAuthenticationAvailability = targetAvailability
            return
        }

        open(route, targetAvailability: targetAvailability)
    }

    mutating func completeAuthentication(targetAvailability: RouteTargetAvailability) {
        guard let pendingAuthenticationRoute else {
            return
        }

        let resolvedAvailability: RouteTargetAvailability =
            pendingAuthenticationAvailability == .deleted
                || targetAvailability == .deleted
                ? .deleted
                : .available
        self.pendingAuthenticationRoute = nil
        pendingAuthenticationAvailability = nil
        open(
            pendingAuthenticationRoute,
            targetAvailability: resolvedAvailability
        )
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
