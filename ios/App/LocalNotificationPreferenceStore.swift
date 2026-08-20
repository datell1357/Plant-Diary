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

    private struct StoredQuietHours: Codable {
        let enabled: Bool
        let start: String
        let end: String
    }

    private struct StoredPreferences: Codable {
        let globalEnabled: Bool
        let globalTime: String
        let overrides: [String: StoredOverride]
        let quietHours: StoredQuietHours?
    }

    private let defaults: UserDefaults
    private var key: String
    private var preferences =
        LocalNotificationPreferenceStore.defaultPreferences

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

    var quietHours: QuietHoursPreference {
        let stored = preferences.quietHours
        guard let start = try? LocalTime.parse(stored?.start ?? "22:00"),
              let end = try? LocalTime.parse(stored?.end ?? "07:00")
        else {
            preconditionFailure("Bundled quiet-hours defaults must be valid")
        }
        return QuietHoursPreference(
            enabled: stored?.enabled ?? false,
            start: start,
            end: end
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
            overrides: preferences.overrides,
            quietHours: preferences.quietHours
        )
        persist()
    }

    func setQuietHours(enabled: Bool, start: LocalTime, end: LocalTime) {
        preferences = StoredPreferences(
            globalEnabled: preferences.globalEnabled,
            globalTime: preferences.globalTime,
            overrides: preferences.overrides,
            quietHours: StoredQuietHours(
                enabled: enabled,
                start: start.rawValue,
                end: end.rawValue
            )
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
            overrides: overrides,
            quietHours: preferences.quietHours
        )
        persist()
    }

    private func restore() {
        guard let data = defaults.data(forKey: key) else {
            preferences = Self.defaultPreferences
            return
        }
        preferences = (
            try? JSONDecoder().decode(StoredPreferences.self, from: data)
        ) ?? Self.defaultPreferences
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(preferences) else {
            return
        }
        defaults.set(data, forKey: key)
    }

    private static let defaultPreferences = StoredPreferences(
        globalEnabled: true,
        globalTime: "09:00",
        overrides: [:],
        quietHours: nil
    )
}
