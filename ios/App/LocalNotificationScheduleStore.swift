import Foundation
import PlanteriorData
import PlanteriorDomain

@MainActor
final class LocalNotificationScheduleStore: @unchecked Sendable {
    static let shared = LocalNotificationScheduleStore()

    private struct StoredSchedule: Codable {
        let plantID: String
        let date: String
        let time: String
        let kind: String
        let deduplicationKey: String
    }

    private let defaults: UserDefaults
    private var key: String
    private var schedules: [StoredSchedule] = []

    init(
        defaults: UserDefaults = .standard,
        key: String = "notifications.signed-out.scheduled"
    ) {
        self.defaults = defaults
        self.key = key
        restore()
    }

    var scheduledCount: Int {
        schedules.count
    }

    func mount(accountID: String?) {
        key = "notifications.\(accountID ?? "signed-out").scheduled"
        restore()
    }

    func reconcile(_ request: NotificationScheduleRequest) throws {
        let normalizedRequest = NotificationScheduleRequest(
            authorization: request.authorization,
            endpoint: request.endpoint,
            global: request.global,
            perPlant: request.perPlant,
            dueDates: request.dueDates,
            completedPlantIDs: request.completedPlantIDs,
            existingDeduplicationKeys: []
        )
        schedules = try NotificationCoordinator()
            .schedules(normalizedRequest)
            .map {
                StoredSchedule(
                    plantID: $0.plantID.rawValue,
                    date: $0.date.rawValue,
                    time: $0.time.rawValue,
                    kind: $0.kind == .dueDay ? "due" : "next",
                    deduplicationKey: $0.deduplicationKey
                )
            }
        persist()
    }

    func cancel(for plantID: PersonalPlantID) {
        schedules.removeAll { $0.plantID == plantID.rawValue }
        persist()
    }

    private func restore() {
        guard let data = defaults.data(forKey: key) else {
            schedules = []
            return
        }
        schedules = (
            try? JSONDecoder().decode([StoredSchedule].self, from: data)
        ) ?? []
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(schedules) else {
            return
        }
        defaults.set(data, forKey: key)
    }
}
