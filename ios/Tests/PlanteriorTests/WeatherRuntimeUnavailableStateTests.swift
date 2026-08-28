import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
@Suite(.serialized)
struct WeatherRuntimeUnavailableStateTests {
    @Test
    func fullToDeniedTransitionClearsWeatherEvaluationAndAlerts() async throws {
        let harness = try makeHarness()
        let plantID = try PersonalPlantID.parse("full-denied-plant")
        harness.runtime.mount(accountID: "full-denied-account")
        harness.runtime.authorization = .full
        harness.runtime.locationRegionCode = "location-seoul"
        let refresh = Task {
            await harness.runtime.refresh(plants: [plantID])
        }
        let request = await harness.repository.nextRequest()
        try await harness.repository.succeed(
            request,
            with: snapshot(
                id: "full-denied-risk",
                region: "location-seoul",
                temperature: 35,
                humidity: 30
            )
        )
        await refresh.value
        #expect(harness.runtime.risks == [.highTemperature, .dry])
        #expect(harness.runtime.latestEvaluation != nil)
        #expect(harness.runtime.plannedAlertCount == 2)
        #expect(harness.runtime.newlyPlannedAlertCount == 2)

        harness.runtime.authorization = .denied
        harness.runtime.locationRegionCode = nil
        await harness.runtime.refresh(plants: [plantID])

        #expect(harness.runtime.homeState == .unavailable)
        #expect(harness.runtime.effectiveRegionCode == nil)
        #expect(harness.runtime.risks.isEmpty)
        #expect(harness.runtime.isStale == false)
        #expect(harness.runtime.latestEvaluation == nil)
        #expect(harness.runtime.plannedRisksByPlant.isEmpty)
        #expect(harness.runtime.plannedAlertCount == 0)
        #expect(harness.runtime.newlyPlannedAlertCount == 0)
        #expect(
            harness.alertStore.reconcile(
                plantID: plantID,
                activeRisks: [.highTemperature, .dry]
            ) == [.highTemperature, .dry]
        )
    }

    @Test
    func oldWeatherResponseCompletingAfterRegionClearCannotRepopulateState() async throws {
        let harness = try makeHarness()
        let plantID = try PersonalPlantID.parse("clear-old-response-plant")
        harness.runtime.mount(accountID: "clear-old-response-account")
        harness.runtime.setManualRegion("manual-seoul")
        let oldRefresh = Task {
            await harness.runtime.refresh(plants: [plantID])
        }
        let oldRequest = await harness.repository.nextRequest()

        harness.runtime.setManualRegion(nil)
        harness.runtime.authorization = .denied
        await harness.runtime.refresh(plants: [plantID])
        try await harness.repository.succeed(
            oldRequest,
            with: snapshot(
                id: "clear-old-response",
                region: "manual-seoul",
                temperature: 35,
                humidity: 30
            )
        )
        await oldRefresh.value

        #expect(harness.runtime.homeState == .unavailable)
        #expect(harness.runtime.effectiveRegionCode == nil)
        #expect(harness.runtime.risks.isEmpty)
        #expect(harness.runtime.isStale == false)
        #expect(harness.runtime.latestEvaluation == nil)
        #expect(harness.runtime.plannedRisksByPlant.isEmpty)
        #expect(harness.runtime.plannedAlertCount == 0)
        #expect(harness.runtime.newlyPlannedAlertCount == 0)
    }

    @Test
    func manualRegionRemovalClearsWeatherEvaluationAndAlerts() async throws {
        let harness = try makeHarness()
        let plantID = try PersonalPlantID.parse("manual-removal-plant")
        harness.runtime.mount(accountID: "manual-removal-account")
        harness.runtime.setManualRegion("manual-seoul")
        let refresh = Task {
            await harness.runtime.refresh(plants: [plantID])
        }
        let request = await harness.repository.nextRequest()
        try await harness.repository.succeed(
            request,
            with: snapshot(
                id: "manual-removal-risk",
                region: "manual-seoul",
                temperature: 35,
                humidity: 30
            )
        )
        await refresh.value
        #expect(harness.runtime.plannedAlertCount == 2)

        harness.runtime.setManualRegion(nil)
        harness.runtime.authorization = .denied
        await harness.runtime.refresh(plants: [plantID])

        #expect(harness.runtime.homeState == .unavailable)
        #expect(harness.runtime.effectiveRegionCode == nil)
        #expect(harness.runtime.risks.isEmpty)
        #expect(harness.runtime.isStale == false)
        #expect(harness.runtime.latestEvaluation == nil)
        #expect(harness.runtime.plannedRisksByPlant.isEmpty)
        #expect(harness.runtime.plannedAlertCount == 0)
        #expect(harness.runtime.newlyPlannedAlertCount == 0)
    }

    private func makeHarness() throws -> WeatherUnavailableHarness {
        let suiteName = "WeatherRuntimeUnavailableStateTests-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let repository = ControllableWeatherRepository()
        let alertStore = LocalWeatherAlertStore(defaults: defaults)
        let accountCoordinator = WeatherAccountScopeCoordinator()
        let now = try Instant.parse("2026-08-25T03:00:00Z")
        let runtime = WeatherRuntime(
            repository: repository,
            defaults: defaults,
            alertStore: alertStore,
            nowOverride: now,
            accountCoordinator: accountCoordinator
        )
        return WeatherUnavailableHarness(
            runtime: runtime,
            repository: repository,
            alertStore: alertStore
        )
    }

    private func snapshot(
        id: String,
        region: String,
        temperature: Double,
        humidity: Int = 55
    ) throws -> WeatherSnapshot {
        try WeatherSnapshot(
            id: WeatherSnapshotID.parse(id),
            regionCode: region,
            temperatureCelsius: temperature,
            humidityPercent: humidity,
            precipitationMillimeters: 0,
            observedAt: Instant.parse("2026-08-25T03:00:00Z"),
            expiresAt: Instant.parse("2026-08-25T04:00:00Z")
        )
    }
}

@MainActor
private struct WeatherUnavailableHarness {
    let runtime: WeatherRuntime
    let repository: ControllableWeatherRepository
    let alertStore: LocalWeatherAlertStore
}
