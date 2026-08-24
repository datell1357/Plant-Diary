import CoreLocation
import Foundation

extension WeatherRuntime: @preconcurrency CLLocationManagerDelegate {
    func requestLocationIfNeeded() -> Bool {
        guard locationRequestToken == nil else {
            return true
        }
        let token = UUID()
        locationRequestToken = token
        #if DEBUG
            if let qaLocationClient {
                let event = qaLocationClient.request()
                recordLocationRequest(count: qaLocationClient.requestCount)
                finishQALocationRequest(event)
                return false
            }
        #endif
        recordLocationRequest()
        locationManager.requestLocation()
        let milliseconds = Self.locationTimeoutMilliseconds
        Task { @MainActor [weak self] in
            try? await Task.sleep(
                for: .milliseconds(milliseconds)
            )
            guard let self, locationRequestToken == token else {
                return
            }
            locationRequestToken = nil
            locationManager.stopUpdatingLocation()
            homeState = .failed
        }
        return true
    }

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

    #if DEBUG
        private func finishQALocationRequest(
            _: QAWeatherLocationClient.Event
        ) {
            locationRequestToken = nil
            locationManager.stopUpdatingLocation()
            homeState = .failed
        }
    #endif
}

#if DEBUG
    @MainActor
    final class QAWeatherLocationClient {
        enum Event {
            case timedOut
            case failed
        }

        private let event: Event
        private(set) var requestCount = 0

        init?(processInfo: ProcessInfo = .processInfo) {
            switch processInfo.environment["QA_WEATHER_LOCATION_MODE"] {
            case "timeout": event = .timedOut
            case "failure": event = .failed
            default: return nil
            }
        }

        func request() -> Event {
            if requestCount == 0 {
                requestCount = 1
            }
            return event
        }
    }
#endif
