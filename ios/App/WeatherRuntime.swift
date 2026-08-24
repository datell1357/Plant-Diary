import Combine
import CoreLocation
import Foundation
import PlanteriorData
import PlanteriorDomain

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
    private let repository: any WeatherSnapshotRepository
    var accountScopeID: String?
    var locationRequestToken: UUID?
    var latestEvaluation: WeatherRiskEvaluation?
    var latestPlantIDs: [PersonalPlantID] = []
    var plannedRisksByPlant: [PersonalPlantID: Set<RiskType>] = [:]

    override init() {
        #if DEBUG
            qaLocationClient = QAWeatherLocationClient()
        #endif
        defaults = .standard
        repository = Self.currentRepository()
        accountScopeID = Self.initialAccountScopeID
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
        locationRequestToken = nil
        locationRegionCode = nil
        effectiveRegionCode = nil
        accountScopeID = nil
    }

    func requestLocationPermission() {
        locationManager.requestWhenInUseAuthorization()
    }

    #if DEBUG
        func revokeLocationForQA() {
            authorization = .denied
            locationManager.stopUpdatingLocation()
            locationRequestToken = nil
            locationRegionCode = nil
        }
    #endif

    func refresh(plants: [PersonalPlantID]) async {
        let selection = WeatherRegionSelection(
            authorization: authorization,
            manualRegionCode: manualRegionCode,
            locationRegionCode: locationRegionCode
        )
        guard let regionCode = selection.effectiveRegionCode else {
            if selection.shouldRequestLocation {
                if requestLocationIfNeeded() {
                    homeState = .loading
                }
            } else {
                homeState = .unavailable
            }
            effectiveRegionCode = nil
            return
        }
        effectiveRegionCode = regionCode
        homeState = .loading
        do {
            let snapshot = try await repository.snapshot(
                regionCode: regionCode
            )
            try evaluate(snapshot: snapshot, plants: plants)
        } catch {
            risks = []
            isStale = false
            plannedAlertCount = 0
            homeState = .failed
        }
    }

    private func evaluate(
        snapshot: WeatherSnapshot,
        plants: [PersonalPlantID]
    ) throws {
        guard let now = effectiveNow else {
            throw WeatherRepositoryError.fixtureFailure
        }
        let evaluation = try WeatherRiskEvaluator(now: now).evaluate(
            snapshot: snapshot,
            thresholds: .plantDefault,
            globalAlertsEnabled: true,
            perPlantAlertsEnabled: true
        )
        risks = evaluation.risks
        isStale = evaluation.isStale
        latestEvaluation = evaluation
        reconcileAlerts(plants: plants)
        homeState = .content(summary: summary(for: snapshot, evaluation))
    }

    private func summary(
        for snapshot: WeatherSnapshot,
        _ evaluation: WeatherRiskEvaluation
    ) -> String {
        evaluation.risks.isEmpty
            ? "\(String(format: "%.0f", snapshot.temperatureCelsius))℃ · 위험 없음"
            : "주의 \(evaluation.risks.count)건"
    }

    private var effectiveNow: Instant? {
        #if DEBUG
            let qaNow = ProcessInfo.processInfo.environment[
                "QA_WEATHER_NOW"
            ].flatMap { try? Instant.parse($0) }
            if let qaNow {
                return qaNow
            }
        #endif
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return try? Instant.parse(formatter.string(from: Date()))
    }

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
            locationManager.stopUpdatingLocation()
            locationRequestToken = nil
            locationRegionCode = nil
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
    }
}
