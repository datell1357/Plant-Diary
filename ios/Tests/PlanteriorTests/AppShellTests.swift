import Foundation
@testable import Planterior
import Testing

struct AppShellTests {
    @Test
    func preservesIndependentTabStacks() {
        var navigation = AppNavigationState()

        navigation.push(.tabDetail(.home))
        navigation.select(.collection)
        navigation.push(.tabDetail(.collection))
        navigation.select(.storage)
        navigation.push(.tabDetail(.storage))
        navigation.select(.settings)
        navigation.push(.tabDetail(.settings))

        #expect(navigation.path(for: .home) == [.tabDetail(.home)])
        #expect(navigation.path(for: .collection) == [.tabDetail(.collection)])
        #expect(navigation.path(for: .storage) == [.tabDetail(.storage)])
        #expect(navigation.path(for: .settings) == [.tabDetail(.settings)])
    }

    @Test
    func cameraActionDoesNotReplaceSelectedTabOrItsStack() {
        var navigation = AppNavigationState()
        navigation.select(.collection)
        navigation.push(.tabDetail(.collection))

        navigation.presentCamera()

        #expect(navigation.isCameraPresented)
        #expect(navigation.selectedTab == .collection)
        #expect(navigation.path(for: .collection) == [.tabDetail(.collection)])

        navigation.dismissCamera()

        #expect(!navigation.isCameraPresented)
    }

    @Test
    func resumesTypedPendingRouteAfterAuthentication() throws {
        let target = try #require(PlantRouteTarget(rawValue: "plant-42"))
        var navigation = AppNavigationState()

        navigation.handle(
            .route(.plant(target)),
            authentication: .signedOut,
            targetAvailability: .available
        )

        #expect(navigation.pendingAuthenticationRoute == .plant(target))
        #expect(navigation.path(for: .collection).isEmpty)

        navigation.completeAuthentication(targetAvailability: .available)

        #expect(navigation.pendingAuthenticationRoute == nil)
        #expect(navigation.selectedTab == .collection)
        #expect(navigation.path(for: .collection) == [.plant(target)])
    }

    @Test
    func invalidAndDeletedTargetsFallBackWithoutMetadata() throws {
        var invalidNavigation = AppNavigationState()

        invalidNavigation.handle(
            .plant(rawTarget: "../private-plant"),
            authentication: .signedIn,
            targetAvailability: .available
        )

        #expect(invalidNavigation.selectedTab == .home)
        #expect(invalidNavigation.path(for: .home) == [.unavailable])
        #expect(invalidNavigation.pendingAuthenticationRoute == nil)

        let deletedTarget = try #require(PlantRouteTarget(rawValue: "deleted-plant"))
        var deletedNavigation = AppNavigationState()
        deletedNavigation.handle(
            .route(.plant(deletedTarget)),
            authentication: .signedOut,
            targetAvailability: .available
        )

        deletedNavigation.completeAuthentication(targetAvailability: .deleted)

        #expect(deletedNavigation.selectedTab == .home)
        #expect(deletedNavigation.path(for: .home) == [.unavailable])
        #expect(deletedNavigation.pendingAuthenticationRoute == nil)
    }

    @Test
    func parsesOnlyAllowlistedExternalPlantURLs() throws {
        let valid = try #require(URL(string: "planterior://plant/plant-42"))
        let hostile = try #require(URL(string: "https://evil.test/plant/plant-42"))
        let traversal = try #require(URL(string: "planterior://plant/%2E%2E"))
        let query = try #require(URL(string: "planterior://plant/plant-42?uid=private"))
        let fragment = try #require(URL(string: "planterior://plant/plant-42#private"))
        let duplicateSlash = try #require(URL(string: "planterior://plant//plant-42"))
        let trailingSlash = try #require(URL(string: "planterior://plant/plant-42/"))
        let credentials = try #require(URL(string: "planterior://user@plant/plant-42"))
        let port = try #require(URL(string: "planterior://plant:443/plant-42"))
        #expect(
            AppURLRoute.parse(valid) == .plant(rawTarget: "plant-42")
        )
        #expect(AppURLRoute.parse(hostile) == .invalid)
        #expect(AppURLRoute.parse(traversal) == .invalid)
        #expect(AppURLRoute.parse(query) == .invalid)
        #expect(AppURLRoute.parse(fragment) == .invalid)
        #expect(AppURLRoute.parse(duplicateSlash) == .invalid)
        #expect(AppURLRoute.parse(trailingSlash) == .invalid)
        #expect(AppURLRoute.parse(credentials) == .invalid)
        #expect(AppURLRoute.parse(port) == .invalid)
        #expect(PlantRouteTarget(rawValue: "식물-42") == nil)
        #expect(PlantRouteTarget(rawValue: "plant_42") != nil)
    }
}
