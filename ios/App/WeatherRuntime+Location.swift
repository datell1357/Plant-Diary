import CoreLocation
import Foundation

extension WeatherRuntime: @preconcurrency CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_WEATHER_AUTHORIZATION"
            ] != nil {
                return
            }
        #endif
        authorization = Self.authorizationState(
            manager.authorizationStatus,
            accuracy: manager.accuracyAuthorization
        )
        if authorization == .denied {
            manager.stopUpdatingLocation()
            completeLocationRequest()
            locationRegionCode = nil
        }
    }

    func locationManager(
        _ manager: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        guard let coordinate = locations.last?.coordinate else {
            return
        }
        locationRegionCode = String(
            format: "%.2f,%.2f",
            coordinate.latitude,
            coordinate.longitude
        )
        completeLocationRequest()
        manager.stopUpdatingLocation()
    }

    func locationManager(
        _ manager: CLLocationManager,
        didFailWithError _: Error
    ) {
        completeLocationRequest()
        manager.stopUpdatingLocation()
        homeState = .failed
    }
}
