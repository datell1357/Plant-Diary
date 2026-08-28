import Combine
import CoreLocation
import Foundation
import PlanteriorData
import PlanteriorDomain

@MainActor
final class WeatherAccountScopeCoordinator {
    struct Identity: Equatable {
        let accountID: String
        let generation: UInt64
    }

    static let shared = WeatherAccountScopeCoordinator()

    private var accountID = "signed-out"
    private var generation: UInt64 = 0

    @discardableResult
    func prepareForMount(accountID: String?) -> Identity {
        let normalizedAccountID = accountID ?? "signed-out"
        if self.accountID != normalizedAccountID {
            self.accountID = normalizedAccountID
            generation &+= 1
        }
        return Identity(
            accountID: normalizedAccountID,
            generation: generation
        )
    }

    func matches(_ identity: Identity) -> Bool {
        accountID == identity.accountID && generation == identity.generation
    }
}

@MainActor
final class WeatherRuntime: NSObject, ObservableObject {
    @Published var authorization: LocationAuthorizationState =
        .notDetermined
    @Published var homeState = HomeWeatherState.unavailable
    @Published var risks: [RiskType] = []
    @Published private(set) var effectiveRegionCode: String?
    @Published var isStale = false
    @Published var plannedAlertCount = 0
    @Published var newlyPlannedAlertCount = 0
    @Published private(set) var locationRequestCount = 0
    @Published var locationRegionCode: String?
    @Published var globalAlertsEnabledState = true

    let locationManager = CLLocationManager()
    #if DEBUG
        let qaLocationClient: QAWeatherLocationClient?
    #endif
    let defaults: UserDefaults
    let repository: any WeatherSnapshotRepository
    let nowOverride: Instant?
    let accountCoordinator: WeatherAccountScopeCoordinator
    var requestGeneration: UInt64 = 0
    let alertStore: LocalWeatherAlertStore
    var accountScopeID: String?
    var mountedAccountIdentity: WeatherAccountScopeCoordinator.Identity?
    struct LocationRequestContext: Equatable {
        let accountIdentity: WeatherAccountScopeCoordinator.Identity
        let token: UUID
        let generation: UInt64
    }

    var locationRequestToken: UUID?
    var locationRequestGeneration: UInt64 = 0
    var locationRequestContext: LocationRequestContext?
    var locationAuthorizationContext: LocationRequestContext?
    var latestEvaluation: WeatherRiskEvaluation?
    var latestPlantIDs: [PersonalPlantID] = []
    var plannedRisksByPlant: [PersonalPlantID: Set<RiskType>] = [:]

    override convenience init() {
        self.init(
            repository: Self.currentRepository(),
            defaults: .standard,
            alertStore: .shared,
            initialAccountScopeID: Self.initialAccountScopeID,
            nowOverride: nil,
            accountCoordinator: .shared
        )
    }

    init(
        repository: any WeatherSnapshotRepository,
        defaults: UserDefaults = .standard,
        alertStore: LocalWeatherAlertStore = .shared,
        initialAccountScopeID: String = "signed-out",
        nowOverride: Instant? = nil,
        accountCoordinator: WeatherAccountScopeCoordinator = .shared
    ) {
        #if DEBUG
            qaLocationClient = QAWeatherLocationClient()
        #endif
        self.defaults = defaults
        self.repository = repository
        self.nowOverride = nowOverride
        self.accountCoordinator = accountCoordinator
        self.alertStore = alertStore
        accountScopeID = initialAccountScopeID
        mountedAccountIdentity = nil
        super.init()
        defaults.removeObject(forKey: "weather.manual-region")
        locationManager.delegate = self
        resetQAStateIfNeeded()
        reloadAlertPreferences()
        applyQALocationStateIfNeeded()
        refreshAuthorization()
    }

    var effectiveRegionName: String? {
        guard let effectiveRegionCode else {
            return nil
        }
        return Self.regionName(for: effectiveRegionCode)
    }

    func clearRegionStateForAccountRemount() {
        locationManager.stopUpdatingLocation()
        invalidateLocationRequest()
        locationAuthorizationContext = nil
        locationRegionCode = nil
        effectiveRegionCode = nil
        accountScopeID = nil
        mountedAccountIdentity = nil
    }

    func updateEffectiveRegionCode(_ regionCode: String?) {
        effectiveRegionCode = regionCode
    }

    func requestLocationPermission() {
        guard let mountedAccountIdentity else {
            return
        }
        invalidateLocationRequest()
        let context = LocationRequestContext(
            accountIdentity: mountedAccountIdentity,
            token: UUID(),
            generation: locationRequestGeneration
        )
        locationAuthorizationContext = context
        locationManager.requestWhenInUseAuthorization()
    }

    #if DEBUG
        func revokeLocationForQA() {
            authorization = .denied
            clearUnavailableRegionState()
            locationRegionCode = nil
        }
    #endif

    private func refreshAuthorization() {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_WEATHER_AUTHORIZATION"
            ] != nil {
                return
            }
        #endif
        authorization = Self.authorizationState(
            locationManager.authorizationStatus,
            accuracy: locationManager.accuracyAuthorization
        )
        if authorization == .denied {
            if mountedAccountIdentity != nil {
                clearUnavailableRegionState()
            } else {
                locationManager.stopUpdatingLocation()
                invalidateLocationRequest()
                locationAuthorizationContext = nil
                locationRegionCode = nil
            }
        }
    }

    func recordLocationRequest(count: Int) {
        locationRequestCount = count
    }

    func recordLocationRequest() {
        locationRequestCount += 1
    }

    func completeLocationRequest() {
        locationRequestToken = nil
        locationRequestContext = nil
    }

    func invalidateLocationRequest() {
        locationRequestGeneration &+= 1
        locationRequestToken = nil
        locationRequestContext = nil
    }
}
