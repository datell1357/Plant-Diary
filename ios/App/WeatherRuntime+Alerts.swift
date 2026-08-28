import Foundation
import PlanteriorDomain

extension WeatherRuntime {
    var globalAlertsEnabled: Bool {
        globalAlertsEnabledState
    }

    func setGlobalAlertsEnabled(_ enabled: Bool) {
        globalAlertsEnabledState = enabled
        alertStore.setGlobalEnabled(enabled)
        reconcileAlerts(plants: latestPlantIDs)
    }

    func reloadAlertPreferences() {
        globalAlertsEnabledState = alertStore.globalEnabled
    }

    func reconcileAlerts(plants: [PersonalPlantID]) {
        latestPlantIDs = plants
        guard let latestEvaluation else {
            plannedAlertCount = 0
            newlyPlannedAlertCount = 0
            return
        }
        let currentPlantIDs = Set(plants)
        plannedRisksByPlant = plannedRisksByPlant.filter {
            currentPlantIDs.contains($0.key)
        }
        var newlyPlanned: [RiskType] = []
        for plantID in plants {
            let activeRisks = Set(latestEvaluation.risks)
            let enteredRisks = alertStore.reconcile(
                plantID: plantID,
                activeRisks: activeRisks,
                alertsAllowed: latestEvaluation.alertsAllowed &&
                    globalAlertsEnabledState
            )
            let alertsEnabled = latestEvaluation.alertsAllowed &&
                globalAlertsEnabledState &&
                alertStore.plantEnabled(for: plantID)
            if alertsEnabled {
                var planned = plannedRisksByPlant[
                    plantID,
                    default: []
                ].intersection(activeRisks)
                planned.formUnion(enteredRisks)
                plannedRisksByPlant[plantID] = planned
                newlyPlanned.append(contentsOf: enteredRisks)
            } else {
                plannedRisksByPlant[plantID] = []
            }
        }
        newlyPlannedAlertCount = newlyPlanned.count
        plannedAlertCount = plannedRisksByPlant.values.reduce(0) {
            $0 + $1.count
        }
    }

    func clearUnavailableRegionState() {
        invalidateRefreshes()
        locationManager.stopUpdatingLocation()
        locationRequestToken = nil
        locationRequestContext = nil
        locationAuthorizationContext = nil
        updateEffectiveRegionCode(nil)
        risks = []
        isStale = false
        latestEvaluation = nil
        plannedRisksByPlant = [:]
        plannedAlertCount = 0
        newlyPlannedAlertCount = 0
        alertStore.clearActiveRisks()
        homeState = .unavailable
    }

    func prepareForAccountRemount() {
        clearUnavailableRegionState()
        clearRegionStateForAccountRemount()
        latestPlantIDs = []
    }

    func setPlantAlertsEnabled(
        _ enabled: Bool,
        plantID: PersonalPlantID
    ) {
        alertStore.setPlantEnabled(
            enabled,
            plantID: plantID
        )
        NotificationCenter.default.post(
            name: .weatherAlertPreferencesDidChange,
            object: nil
        )
    }
}
