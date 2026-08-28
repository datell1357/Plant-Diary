import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
@Suite(.serialized, .timeLimit(.minutes(1)))
struct WeatherRuntimeConcurrencyTests {
    @Test
    func firstMountForInitialAccountPreservesConfiguredLocationRegion() throws {
        let harness = try makeHarness(initialAccountScopeID: "qa-account")
        harness.runtime.authorization = .full
        harness.runtime.locationRegionCode = "location-busan"

        harness.runtime.mount(accountID: "qa-account")

        #expect(harness.runtime.locationRegionCode == "location-busan")
        #expect(harness.runtime.locationRequestCount == 0)
    }

    @Test
    func accountRemountDiscardsOldResultAfterNewRefreshCompletes() async throws {
        let harness = try makeHarness()
        let plantID = try PersonalPlantID.parse("account-result-plant")
        harness.runtime.mount(accountID: "account-a")
        harness.runtime.setManualRegion("manual-seoul")
        let oldRefresh = Task {
            await harness.runtime.refresh(plants: [plantID])
        }
        let oldRequest = await harness.repository.nextRequest()
        #expect(oldRequest.regionCode == "manual-seoul")

        harness.runtime.mount(accountID: "account-b")
        harness.runtime.setManualRegion("manual-busan")
        let newRefresh = Task {
            await harness.runtime.refresh(plants: [plantID])
        }
        let newRequest = await harness.repository.nextRequest()
        #expect(newRequest.regionCode == "manual-busan")
        try await harness.repository.succeed(
            newRequest,
            with: snapshot(id: "account-new", region: "manual-busan", temperature: 22)
        )
        await newRefresh.value

        try await harness.repository.succeed(
            oldRequest,
            with: snapshot(
                id: "account-old",
                region: "manual-seoul",
                temperature: 35,
                humidity: 30
            )
        )
        await oldRefresh.value

        #expect(harness.runtime.homeState == .content(summary: "22℃ · 위험 없음"))
        #expect(harness.runtime.effectiveRegionCode == "manual-busan")
        #expect(harness.runtime.risks.isEmpty)
        #expect(harness.runtime.plannedAlertCount == 0)
        #expect(
            harness.alertStore.reconcile(
                plantID: plantID,
                activeRisks: [.highTemperature, .dry]
            ) == [.highTemperature, .dry]
        )
    }

    @Test
    func signOutRemountDiscardsOldAccountError() async throws {
        let harness = try makeHarness()
        let plantID = try PersonalPlantID.parse("sign-out-plant")
        harness.runtime.mount(accountID: "account-a")
        harness.runtime.setManualRegion("manual-seoul")
        let oldRefresh = Task {
            await harness.runtime.refresh(plants: [plantID])
        }
        let oldRequest = await harness.repository.nextRequest()

        harness.runtime.mount(accountID: nil)
        harness.runtime.setManualRegion("manual-jeju")
        let signedOutRefresh = Task {
            await harness.runtime.refresh(plants: [plantID])
        }
        let signedOutRequest = await harness.repository.nextRequest()
        try await harness.repository.succeed(
            signedOutRequest,
            with: snapshot(id: "signed-out", region: "manual-jeju", temperature: 24)
        )
        await signedOutRefresh.value

        await harness.repository.fail(oldRequest, with: .transport)
        await oldRefresh.value

        #expect(harness.runtime.homeState == .content(summary: "24℃ · 위험 없음"))
        #expect(harness.runtime.effectiveRegionCode == "manual-jeju")
        #expect(harness.runtime.risks.isEmpty)
        #expect(harness.runtime.plannedAlertCount == 0)
    }

    @Test
    func newerRegionRefreshSupersedesOlderRequest() async throws {
        let harness = try makeHarness()
        let plantID = try PersonalPlantID.parse("region-change-plant")
        harness.runtime.mount(accountID: "region-account")
        harness.runtime.setManualRegion("manual-seoul")
        let oldRefresh = Task {
            await harness.runtime.refresh(plants: [plantID])
        }
        let oldRequest = await harness.repository.nextRequest()

        harness.runtime.setManualRegion("manual-busan")
        let newRefresh = Task {
            await harness.runtime.refresh(plants: [plantID])
        }
        let newRequest = await harness.repository.nextRequest()
        try await harness.repository.succeed(
            newRequest,
            with: snapshot(id: "region-new", region: "manual-busan", temperature: 23)
        )
        await newRefresh.value

        try await harness.repository.succeed(
            oldRequest,
            with: snapshot(
                id: "region-old",
                region: "manual-seoul",
                temperature: 35,
                humidity: 30
            )
        )
        await oldRefresh.value

        #expect(harness.runtime.homeState == .content(summary: "23℃ · 위험 없음"))
        #expect(harness.runtime.effectiveRegionCode == "manual-busan")
        #expect(harness.runtime.risks.isEmpty)
        #expect(harness.runtime.plannedAlertCount == 0)
    }

    private func makeHarness(
        initialAccountScopeID: String = "signed-out"
    ) throws -> WeatherRuntimeHarness {
        let suiteName = "WeatherRuntimeConcurrencyTests-\(UUID())"
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
            initialAccountScopeID: initialAccountScopeID,
            nowOverride: now,
            accountCoordinator: accountCoordinator
        )
        return WeatherRuntimeHarness(
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
private struct WeatherRuntimeHarness {
    let runtime: WeatherRuntime
    let repository: ControllableWeatherRepository
    let alertStore: LocalWeatherAlertStore
}
