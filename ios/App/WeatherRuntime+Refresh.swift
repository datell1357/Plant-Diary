import Foundation
import PlanteriorData
import PlanteriorDomain

extension WeatherRuntime {
    func refresh(plants: [PersonalPlantID]) async {
        requestGeneration &+= 1
        let refreshGeneration = requestGeneration
        let currentAccountID = accountScopeID ?? "signed-out"
        guard
            let accountIdentity = mountedAccountIdentity,
            accountIdentity.accountID == currentAccountID,
            accountCoordinator.matches(accountIdentity),
            alertStore.isMounted(accountID: currentAccountID)
        else {
            return
        }
        let selection = WeatherRegionSelection(
            authorization: authorization,
            manualRegionCode: manualRegionCode,
            locationRegionCode: locationRegionCode
        )
        guard let regionCode = selection.effectiveRegionCode else {
            clearUnavailableRegionState()
            if selection.shouldRequestLocation {
                if requestLocationIfNeeded() {
                    homeState = .loading
                }
            }
            return
        }
        updateEffectiveRegionCode(regionCode)
        homeState = .loading
        do {
            let snapshot = try await repository.snapshot(
                regionCode: regionCode
            )
            guard isCurrentRefresh(
                generation: refreshGeneration,
                accountIdentity: accountIdentity
            ) else {
                return
            }
            try evaluate(snapshot: snapshot, plants: plants)
        } catch {
            applyFailureIfCurrent(
                generation: refreshGeneration,
                accountIdentity: accountIdentity
            )
        }
    }

    func invalidateRefreshes() {
        requestGeneration &+= 1
    }

    private func isCurrentRefresh(
        generation: UInt64,
        accountIdentity: WeatherAccountScopeCoordinator.Identity
    ) -> Bool {
        requestGeneration == generation &&
            accountScopeID == accountIdentity.accountID &&
            accountCoordinator.matches(accountIdentity) &&
            alertStore.isMounted(accountID: accountIdentity.accountID)
    }

    private func applyFailureIfCurrent(
        generation: UInt64,
        accountIdentity: WeatherAccountScopeCoordinator.Identity
    ) {
        guard isCurrentRefresh(
            generation: generation,
            accountIdentity: accountIdentity
        ) else {
            return
        }
        risks = []
        isStale = false
        plannedAlertCount = 0
        homeState = .failed
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
        if let nowOverride {
            return nowOverride
        }
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
}
