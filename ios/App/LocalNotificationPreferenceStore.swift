import Foundation
import PlanteriorData
import PlanteriorDomain

@MainActor
final class LocalNotificationPreferenceStore {
    static let shared = LocalNotificationPreferenceStore()

    private struct StoredOverride: Codable {
        let enabled: Bool?
        let time: String?
    }

    private struct StoredPreferences: Codable {
        let globalEnabled: Bool
        let globalTime: String
        let overrides: [String: StoredOverride]
    }

    private let defaults: UserDefaults
    private var key: String
    private var preferences = StoredPreferences(
        globalEnabled: true,
        globalTime: "09:00",
        overrides: [:]
    )

    init(
        defaults: UserDefaults = .standard,
        key: String = "notifications.signed-out.preferences"
    ) {
        self.defaults = defaults
        self.key = key
        restore()
    }

    var global: NotificationPreference? {
        guard let time = try? LocalTime.parse(preferences.globalTime) else {
            return nil
        }
        return NotificationPreference(
            enabled: preferences.globalEnabled,
            time: time
        )
    }

    var overrides: [PersonalPlantID: PlantNotificationOverride] {
        preferences.overrides.reduce(into: [:]) { result, entry in
            guard let plantID = try? PersonalPlantID.parse(entry.key) else {
                return
            }
            result[plantID] = PlantNotificationOverride(
                enabled: entry.value.enabled,
                time: try? entry.value.time.map(LocalTime.parse)
            )
        }
    }

    func mount(accountID: String?) {
        key = "notifications.\(accountID ?? "signed-out").preferences"
        restore()
    }

    func setGlobal(enabled: Bool, time: LocalTime) {
        preferences = StoredPreferences(
            globalEnabled: enabled,
            globalTime: time.rawValue,
            overrides: preferences.overrides
        )
        persist()
    }

    func setOverride(
        plantID: PersonalPlantID,
        enabled: Bool?,
        time: LocalTime?
    ) {
        var overrides = preferences.overrides
        overrides[plantID.rawValue] = StoredOverride(
            enabled: enabled,
            time: time?.rawValue
        )
        preferences = StoredPreferences(
            globalEnabled: preferences.globalEnabled,
            globalTime: preferences.globalTime,
            overrides: overrides
        )
        persist()
    }

    private func restore() {
        guard let data = defaults.data(forKey: key) else {
            preferences = StoredPreferences(
                globalEnabled: true,
                globalTime: "09:00",
                overrides: [:]
            )
            return
        }
        preferences = (
            try? JSONDecoder().decode(StoredPreferences.self, from: data)
        ) ?? StoredPreferences(
            globalEnabled: true,
            globalTime: "09:00",
            overrides: [:]
        )
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(preferences) else {
            return
        }
        defaults.set(data, forKey: key)
    }
}
