import CoreLocation
import Foundation
@testable import Planterior
import Testing

@MainActor
@Suite(.serialized)
struct WeatherRuntimeLocationConcurrencyTests {
    @Test
    func delayedOldAccountLocationSuccessCannotPopulateRemountedState() throws {
        let harness = try makeHarness()
        harness.runtime.mount(accountID: "location-old-account")
        harness.runtime.authorization = .full
        #expect(harness.runtime.requestLocationIfNeeded() == true)

        harness.runtime.mount(accountID: "location-new-account")
        harness.runtime.homeState = .loading
        harness.runtime.locationManagerDidChangeAuthorization(
            harness.runtime.locationManager
        )
        harness.runtime.locationManager(
            harness.runtime.locationManager,
            didUpdateLocations: [CLLocation(latitude: 37.57, longitude: 126.98)]
        )

        #expect(harness.runtime.authorization == .full)
        #expect(harness.runtime.locationRegionCode == nil)
        #expect(harness.runtime.effectiveRegionCode == nil)
        #expect(harness.runtime.homeState == .loading)
    }

    @Test
    func supersededLocationRequestIgnoresDelayedSuccess() throws {
        let harness = try makeHarness()
        harness.runtime.mount(accountID: "location-superseded")
        harness.runtime.authorization = .full
        #expect(harness.runtime.requestLocationIfNeeded() == true)

        harness.runtime.requestLocationPermission()
        harness.runtime.homeState = .loading
        harness.runtime.locationManager(
            harness.runtime.locationManager,
            didUpdateLocations: [CLLocation(latitude: 35.18, longitude: 129.08)]
        )

        #expect(harness.runtime.locationRegionCode == nil)
        #expect(harness.runtime.homeState == .loading)
    }

    @Test
    func delayedOldAccountLocationFailureCannotChangeRemountedState() throws {
        let harness = try makeHarness()
        harness.runtime.mount(accountID: "location-error-old")
        harness.runtime.authorization = .full
        #expect(harness.runtime.requestLocationIfNeeded() == true)

        harness.runtime.mount(accountID: "location-error-new")
        harness.runtime.homeState = .loading
        harness.runtime.locationManager(
            harness.runtime.locationManager,
            didFailWithError: URLError(.networkConnectionLost)
        )

        #expect(harness.runtime.locationRegionCode == nil)
        #expect(harness.runtime.effectiveRegionCode == nil)
        #expect(harness.runtime.homeState == .loading)
    }

    private func makeHarness() throws -> WeatherLocationRuntimeHarness {
        let suiteName = "WeatherRuntimeLocationConcurrencyTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let repository = ControllableWeatherRepository()
        let alertStore = LocalWeatherAlertStore(defaults: defaults)
        let accountCoordinator = WeatherAccountScopeCoordinator()
        let runtime = WeatherRuntime(
            repository: repository,
            defaults: defaults,
            alertStore: alertStore,
            accountCoordinator: accountCoordinator
        )
        return WeatherLocationRuntimeHarness(runtime: runtime)
    }
}

@MainActor
private struct WeatherLocationRuntimeHarness {
    let runtime: WeatherRuntime
}
