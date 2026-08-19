import Foundation
import PlanteriorDomain

@MainActor
final class LocalWeatherAlertStore {
    static let shared = LocalWeatherAlertStore()

    private struct State: Codable {
        var globalEnabled = true
        var perPlantEnabled: [String: Bool] = [:]
        var activeRisks: [String: [String]] = [:]
    }

    private let defaults: UserDefaults
    private var key: String
    private var state = State()

    init(
        defaults: UserDefaults = .standard,
        key: String = "weather.signed-out.alerts"
    ) {
        self.defaults = defaults
        self.key = key
        restore()
    }

    func mount(accountID: String?) {
        key = "weather.\(accountID ?? "signed-out").alerts"
        restore()
    }

    #if DEBUG
        func resetForQA() {
            defaults.removeObject(forKey: key)
            state = State()
        }
    #endif

    func setGlobalEnabled(_ enabled: Bool) {
        state.globalEnabled = enabled
        persist()
    }

    var globalEnabled: Bool {
        state.globalEnabled
    }

    func setPlantEnabled(_ enabled: Bool, plantID: PersonalPlantID) {
        state.perPlantEnabled[plantID.rawValue] = enabled
        persist()
    }

    func alertsEnabled(for plantID: PersonalPlantID) -> Bool {
        state.globalEnabled &&
            (state.perPlantEnabled[plantID.rawValue] ?? true)
    }

    func plantEnabled(for plantID: PersonalPlantID) -> Bool {
        state.perPlantEnabled[plantID.rawValue] ?? true
    }

    func reconcile(
        plantID: PersonalPlantID,
        activeRisks: Set<RiskType>,
        alertsAllowed: Bool = true
    ) -> [RiskType] {
        let previous = Set(
            state.activeRisks[plantID.rawValue, default: []]
                .compactMap(RiskType.init(rawValue:))
        )
        state.activeRisks[plantID.rawValue] = activeRisks.map(\.rawValue)
        persist()
        guard alertsAllowed, alertsEnabled(for: plantID) else {
            return []
        }
        return activeRisks
            .subtracting(previous)
            .sorted { riskOrder($0) < riskOrder($1) }
    }

    private func riskOrder(_ risk: RiskType) -> Int {
        switch risk {
        case .highTemperature: 0
        case .lowTemperature: 1
        case .dry: 2
        case .overwatered: 3
        }
    }

    private func restore() {
        guard let data = defaults.data(forKey: key) else {
            state = State()
            return
        }
        state = (try? JSONDecoder().decode(State.self, from: data))
            ?? State()
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(state) else {
            return
        }
        defaults.set(data, forKey: key)
    }
}
