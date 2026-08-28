import CoreLocation
import Foundation
import PlanteriorData
import PlanteriorDomain

extension WeatherRuntime {
    static var hasResetQAState = false

    var manualRegionCode: String? {
        guard let manualRegionStorageKey else {
            return nil
        }
        return defaults.string(forKey: manualRegionStorageKey)
    }

    var manualRegionStorageKey: String? {
        accountScopeID.map { "weather.\($0).manual-region" }
    }

    func setManualRegion(_ regionCode: String?) {
        guard let manualRegionStorageKey else {
            return
        }
        let normalized = regionCode?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let normalized, !normalized.isEmpty {
            defaults.set(normalized, forKey: manualRegionStorageKey)
        } else {
            defaults.removeObject(forKey: manualRegionStorageKey)
            let selection = WeatherRegionSelection(
                authorization: authorization,
                manualRegionCode: nil,
                locationRegionCode: locationRegionCode
            )
            if selection.effectiveRegionCode == nil {
                clearUnavailableRegionState()
            }
        }
    }

    static func prepareSharedAccountRemount(accountID: String?) {
        WeatherAccountScopeCoordinator.shared.prepareForMount(
            accountID: accountID
        )
    }

    func mount(accountID: String?) {
        let nextScope = accountID ?? "signed-out"
        let nextIdentity = accountCoordinator.prepareForMount(
            accountID: accountID
        )
        guard
            accountScopeID != nextScope ||
            mountedAccountIdentity != nextIdentity
        else {
            if !alertStore.isMounted(accountID: nextScope) {
                alertStore.mount(accountID: accountID)
            }
            reloadAlertPreferences()
            return
        }
        let requiresRemount =
            mountedAccountIdentity != nil || accountScopeID != nextScope
        if requiresRemount {
            prepareForAccountRemount()
        }
        accountScopeID = nextScope
        mountedAccountIdentity = nextIdentity
        alertStore.mount(accountID: accountID)
        reloadAlertPreferences()
    }

    static var initialAccountScopeID: String {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_AUTHENTICATED"] == "1" {
                return ProcessInfo.processInfo.environment["QA_ACCOUNT_ID"]
                    ?? "qa-account"
            }
        #endif
        return "signed-out"
    }

    func resetQAStateIfNeeded() {
        #if DEBUG
            guard ProcessInfo.processInfo.environment[
                "QA_RESET_WEATHER"
            ] == "1", !Self.hasResetQAState else {
                return
            }
            defaults.removeObject(forKey: "weather.manual-region")
            if let manualRegionStorageKey {
                defaults.removeObject(forKey: manualRegionStorageKey)
            }
            if ProcessInfo.processInfo.environment[
                "QA_AUTHENTICATED"
            ] == "1" {
                LocalWeatherAlertStore.shared.mount(
                    accountID: ProcessInfo.processInfo.environment[
                        "QA_ACCOUNT_ID"
                    ] ?? "qa-account"
                )
            }
            LocalWeatherAlertStore.shared.resetForQA()
            Self.hasResetQAState = true
        #endif
    }

    static var locationTimeoutMilliseconds: Int {
        10000
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
            return WeatherRepositoryFactory.make(
                baseURLString: Bundle.main.object(
                    forInfoDictionaryKey: "PLAN_OPEN_WEATHER_PROXY_BASE_URL"
                ) as? String
            )
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
