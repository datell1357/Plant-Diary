import CoreLocation
import Foundation
import PlanteriorData
import PlanteriorDomain

extension WeatherRuntime {
    static var hasResetQAState = false

    func resetQAStateIfNeeded() {
        #if DEBUG
            guard ProcessInfo.processInfo.environment[
                "QA_RESET_WEATHER"
            ] == "1", !Self.hasResetQAState else {
                return
            }
            defaults.removeObject(forKey: "weather.manual-region")
            if ProcessInfo.processInfo.environment[
                "QA_AUTHENTICATED"
            ] == "1" {
                LocalWeatherAlertStore.shared.mount(
                    accountID: "qa-account"
                )
            }
            LocalWeatherAlertStore.shared.resetForQA()
            Self.hasResetQAState = true
        #endif
    }

    static var locationTimeoutMilliseconds: Int {
        #if DEBUG
            if let rawValue = ProcessInfo.processInfo.environment[
                "QA_WEATHER_TIMEOUT_MILLISECONDS"
            ], let milliseconds = Int(rawValue) {
                return milliseconds
            }
        #endif
        return 10000
    }

    func applyQALocationStateIfNeeded() {
        #if DEBUG
            switch ProcessInfo.processInfo.environment[
                "QA_WEATHER_AUTHORIZATION"
            ] {
            case "full": authorization = .full
            case "reduced": authorization = .reduced
            case "denied", "revoked": authorization = .denied
            default: authorization = .notDetermined
            }
            locationRegionCode = ProcessInfo.processInfo.environment[
                "QA_WEATHER_LOCATION_REGION"
            ]
            if let manual = ProcessInfo.processInfo.environment[
                "QA_WEATHER_MANUAL_REGION"
            ] {
                setManualRegion(manual)
            } else if ProcessInfo.processInfo.environment[
                "QA_HOME_WEATHER_STATE"
            ] != nil {
                setManualRegion("qa-home-region")
            }
        #endif
    }

    static func currentRepository() -> any WeatherSnapshotRepository {
        #if DEBUG
            return QAWeatherRepository(processInfo: .processInfo)
        #else
            return UnavailableWeatherRepository()
        #endif
    }

    static func regionName(for regionCode: String) -> String {
        switch regionCode {
        case "manual-seoul": "서울"
        case "manual-busan", "location-busan": "부산"
        case "manual-incheon": "인천"
        case "manual-daegu": "대구"
        case "manual-daejeon": "대전"
        case "manual-gwangju": "광주"
        case "manual-jeju": "제주"
        default:
            regionCode.contains(",") ? "현재 위치" : regionCode
        }
    }

    static func authorizationState(
        _ status: CLAuthorizationStatus,
        accuracy: CLAccuracyAuthorization
    ) -> LocationAuthorizationState {
        switch status {
        case .authorizedAlways, .authorizedWhenInUse:
            accuracy == .reducedAccuracy ? .reduced : .full
        case .denied, .restricted:
            .denied
        case .notDetermined:
            .notDetermined
        @unknown default:
            .denied
        }
    }
}
