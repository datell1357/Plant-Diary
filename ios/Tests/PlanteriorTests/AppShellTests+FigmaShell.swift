@testable import Planterior
import PlanteriorDesignSystem
import Testing

/// Figma shell contract: signed-out auth gate (figma-analysis §8.3) and tab-bar
/// geometry / system-image contract (§6.1).
extension AppShellTests {
    @Test
    func signedOutTabAndCameraTapsRequireLoginWithoutNavigating() {
        for tab in AppTab.allCases {
            var navigation = AppNavigationState()

            let outcome = navigation.requestTab(tab, authentication: .signedOut)

            #expect(outcome == .requiresLogin)
            #expect(navigation.selectedTab == .home)
            #expect(navigation.path(for: tab).isEmpty)
            #expect(!navigation.isCameraPresented)
        }

        var cameraNavigation = AppNavigationState()
        let cameraOutcome = cameraNavigation.requestCamera(authentication: .signedOut)

        #expect(cameraOutcome == .requiresLogin)
        #expect(!cameraNavigation.isCameraPresented)
        #expect(cameraNavigation.selectedTab == .home)
        #expect(cameraNavigation.path(for: .home).isEmpty)
    }

    @Test
    func signedInTabAndCameraTapsProceedWithIndependentStacks() {
        var navigation = AppNavigationState()

        #expect(navigation.requestTab(.collection, authentication: .signedIn) == .proceed)
        navigation.push(.tabDetail(.collection))
        #expect(navigation.requestTab(.storage, authentication: .signedIn) == .proceed)
        navigation.push(.tabDetail(.storage))
        #expect(navigation.requestTab(.collection, authentication: .signedIn) == .proceed)

        #expect(navigation.selectedTab == .collection)
        #expect(navigation.path(for: .collection) == [.tabDetail(.collection)])
        #expect(navigation.path(for: .storage) == [.tabDetail(.storage)])

        #expect(navigation.requestCamera(authentication: .signedIn) == .proceed)
        #expect(navigation.isCameraPresented)
        #expect(navigation.selectedTab == .collection)

        navigation.dismissCamera()

        #expect(!navigation.isCameraPresented)
        #expect(navigation.selectedTab == .collection)
        #expect(navigation.path(for: .collection) == [.tabDetail(.collection)])
    }

    @Test
    func tabBarMatchesFigmaSystemImageAndGeometryContract() {
        #expect(AppTab.allCases == [.home, .collection, .storage, .settings])
        #expect(AppTab.home.systemImage == "house")
        #expect(AppTab.collection.systemImage == "book")
        #expect(AppTab.storage.systemImage == "shippingbox")
        #expect(AppTab.settings.systemImage == "gearshape")
        #expect(AppTab.home.title == "홈")
        #expect(AppTab.collection.title == "도감")
        #expect(AppTab.storage.title == "창고")
        #expect(AppTab.settings.title == "설정")

        #expect(AppTabBarMetrics.cameraDiameter == PlanteriorControl.cameraDiameter)
        #expect(AppTabBarMetrics.cameraDiameter == 52)
        #expect(AppTabBarMetrics.minimumTarget == PlanteriorControl.minimumTarget)
        #expect(AppTabBarMetrics.minimumTarget == 44)
        #expect(AppTabBarMetrics.iconSize == 24)
        #expect(AppTabBarMetrics.iconLabelSpacing == PlanteriorSpacing.extraSmall)
        #expect(AppTabBarMetrics.horizontalPadding == PlanteriorSpacing.small)
        #expect(AppTabBarMetrics.hairlineWidth == PlanteriorControl.hairline)
        #expect(AppTabBarMetrics.cameraRaise == 10)
        #expect(AppTabBarMetrics.surface == PlanteriorPalette.surface)
        #expect(AppTabBarMetrics.hairline == PlanteriorPalette.border)
        #expect(AppTabBarMetrics.activeTint == PlanteriorPalette.accent)
        #expect(AppTabBarMetrics.inactiveTint == PlanteriorPalette.textSecondary)
    }
}
