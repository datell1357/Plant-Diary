@testable import PlanteriorData
import Testing

struct WeatherRegionSelectionTests {
    @Test
    func manualRegionOverridesAuthorizedLocation() {
        let selection = WeatherRegionSelection(
            authorization: .reduced,
            manualRegionCode: "manual-seoul",
            locationRegionCode: "location-busan"
        )

        #expect(selection.effectiveRegionCode == "manual-seoul")
        #expect(!selection.shouldRequestLocation)
    }

    @Test
    func revokedAuthorizationStopsLocationRequests() {
        let selection = WeatherRegionSelection(
            authorization: .denied,
            manualRegionCode: nil,
            locationRegionCode: "stale-location"
        )

        #expect(selection.effectiveRegionCode == nil)
        #expect(!selection.shouldRequestLocation)
    }

    @Test
    func authorizedLocationIsUsedWithoutManualRegion() {
        let selection = WeatherRegionSelection(
            authorization: .full,
            manualRegionCode: nil,
            locationRegionCode: "location-seoul"
        )

        #expect(selection.effectiveRegionCode == "location-seoul")
        #expect(selection.shouldRequestLocation)
    }
}
