import Foundation
import PlanteriorData
import PlanteriorDomain
import UserNotifications

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
    private let quietHours: () -> QuietHoursPreference
    private let notificationCenter: any LocalNotificationCenterScheduling
    private var key: String
    private var accountID = "signed-out"
    private var schedules: [StoredSchedule] = []
    private var pendingOperation: Task<Void, Error>?

    init(
        defaults: UserDefaults = .standard,
        key: String = "notifications.signed-out.scheduled",
        quietHours: @escaping () -> QuietHoursPreference = {
            LocalNotificationPreferenceStore.shared.quietHours
        },
        notificationCenter: any LocalNotificationCenterScheduling =
            SystemLocalNotificationCenter()
    ) {
        self.defaults = defaults
        self.key = key
        self.quietHours = quietHours
        self.notificationCenter = notificationCenter
        restore()
    }

    var scheduledCount: Int {
        deliverySchedules.count
    }

    func mount(accountID: String?) {
        let mountedAccountID = accountID ?? "signed-out"
        guard self.accountID != mountedAccountID else {
            return
        }
        let prefixes = [ownedPrefix, Self.ownedPrefix(accountID: mountedAccountID)]
        self.accountID = mountedAccountID
        key = "notifications.\(mountedAccountID).scheduled"
        restore()
        enqueueRemoval(prefixes: prefixes)
        enqueueReconciliation()
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
            .localSchedules(normalizedRequest)
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
        enqueueReconciliation()
    }

    func cancel(for plantID: PersonalPlantID) {
        schedules.removeAll { $0.plantID == plantID.rawValue }
        persist()
        enqueueReconciliation()
    }

    func suspendDeliveryForCurrentAccount() {
        enqueueRemoval(prefixes: [ownedPrefix])
    }

    func refreshDeliveryForCurrentAccount() {
        enqueueReconciliation()
    }

    func waitForPendingOperations() async throws {
        try await pendingOperation?.value
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

    private var ownedPrefix: String {
        Self.ownedPrefix(accountID: accountID)
    }

    private var deliverySchedules: [StoredSchedule] {
        let quietHours = quietHours()
        return schedules.filter {
            guard let time = try? LocalTime.parse($0.time),
                  (try? CalendarDate.parse($0.date)) != nil
            else {
                return false
            }
            return !quietHours.contains(time)
        }
    }

    private func enqueueReconciliation() {
        let prefix = ownedPrefix
        let requests = deliverySchedules.map(notificationRequest)
        let center = notificationCenter
        let previous = pendingOperation
        pendingOperation = Task {
            _ = await previous?.result
            let pending = await center.pendingRequests()
            let owned = pending
                .map(\.identifier)
                .filter { $0.hasPrefix(prefix) }
            await center.removePendingRequests(withIdentifiers: owned)
            for request in requests {
                try await center.add(request)
            }
        }
    }

    private func enqueueRemoval(prefixes: [String]) {
        let center = notificationCenter
        let previous = pendingOperation
        pendingOperation = Task {
            _ = await previous?.result
            let pending = await center.pendingRequests()
            let owned = pending
                .map(\.identifier)
                .filter { identifier in
                    prefixes.contains { identifier.hasPrefix($0) }
                }
            await center.removePendingRequests(withIdentifiers: owned)
        }
    }

    private func notificationRequest(
        _ schedule: StoredSchedule
    ) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = "물 주기 알림"
        content.body = "식물의 물 주기 일정을 확인해 주세요."
        content.sound = .default
        content.userInfo = [
            "route": "plant-care",
            "accountID": accountID,
            "plantID": schedule.plantID,
            "care": "watering"
        ]
        let date = schedule.date.split(separator: "-").compactMap { Int($0) }
        let time = schedule.time.split(separator: ":").compactMap { Int($0) }
        let trigger = UNCalendarNotificationTrigger(
            dateMatching: DateComponents(
                year: date[0],
                month: date[1],
                day: date[2],
                hour: time[0],
                minute: time[1]
            ),
            repeats: false
        )
        return UNNotificationRequest(
            identifier: "\(ownedPrefix)\(schedule.plantID).\(schedule.kind)",
            content: content,
            trigger: trigger
        )
    }
}
