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
    private var authorization: NotificationAuthorizationState = .notDetermined
    private var schedules: [StoredSchedule] = []
    private var pendingOperation: Task<Void, Never>?
    private(set) var scheduledCount = 0

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

    func mount(accountID: String?) {
        let mountedAccountID = accountID ?? "signed-out"
        guard self.accountID != mountedAccountID else {
            return
        }
        let previousPrefix = ownedPrefix
        self.accountID = mountedAccountID
        key = "notifications.\(mountedAccountID).scheduled"
        restore()
        enqueueRemoval(prefixes: [previousPrefix])
        enqueueReconciliation()
    }

    func reconcile(_ request: NotificationScheduleRequest) throws {
        let planningRequest = NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: request.endpoint,
            global: request.global,
            perPlant: request.perPlant,
            dueDates: request.dueDates,
            completedPlantIDs: request.completedPlantIDs,
            existingDeduplicationKeys: []
        )
        authorization = request.authorization
        schedules = try NotificationCoordinator()
            .localSchedules(planningRequest)
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

    func updateAuthorization(_ authorization: NotificationAuthorizationState) {
        self.authorization = authorization
        enqueueReconciliation()
    }

    func waitForPendingOperations() async throws {
        await pendingOperation?.value
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
        guard authorization == .authorized else {
            return []
        }
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
            await previous?.value
            let pending = await center.pendingRequests()
            let desiredByID = Dictionary(
                uniqueKeysWithValues: requests.map { ($0.identifier, $0) }
            )
            let owned = pending.filter { $0.identifier.hasPrefix(prefix) }
            let stale = owned
                .map(\.identifier)
                .filter { desiredByID[$0] == nil }
            if !stale.isEmpty {
                await center.removePendingRequests(withIdentifiers: stale)
            }
            let pendingByID = Dictionary(
                uniqueKeysWithValues: owned.map { ($0.identifier, $0) }
            )
            for request in requests where !Self.matches(
                pendingByID[request.identifier],
                request
            ) {
                try? await center.add(request)
            }
            let reconciled = await center.pendingRequests()
            guard prefix == ownedPrefix else {
                return
            }
            scheduledCount = reconciled.filter { pending in
                guard let desired = desiredByID[pending.identifier] else {
                    return false
                }
                return Self.matches(pending, desired)
            }.count
            NotificationCenter.default.post(
                name: .localNotificationScheduleDidChange,
                object: nil
            )
        }
    }

    private func enqueueRemoval(prefixes: [String]) {
        let center = notificationCenter
        let previous = pendingOperation
        pendingOperation = Task {
            await previous?.value
            let pending = await center.pendingRequests()
            let owned = pending
                .map(\.identifier)
                .filter { identifier in
                    prefixes.contains { identifier.hasPrefix($0) }
                }
            await center.removePendingRequests(withIdentifiers: owned)
            if prefixes.contains(ownedPrefix) {
                scheduledCount = 0
                NotificationCenter.default.post(
                    name: .localNotificationScheduleDidChange,
                    object: nil
                )
            }
        }
    }

    private func notificationRequest(
        _ schedule: StoredSchedule
    ) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = "물 주기 알림"
        content.body = "오늘 물 주기 일정이 있어요."
        content.sound = .default
        content.userInfo = [
            "route": "plant-care",
            "plantID": schedule.plantID
        ]
        let date = schedule.date.split(separator: "-").compactMap { Int($0) }
        let time = schedule.time.split(separator: ":").compactMap { Int($0) }
        let trigger = UNCalendarNotificationTrigger(
            dateMatching: DateComponents(
                calendar: .current,
                timeZone: .current,
                year: date[0],
                month: date[1],
                day: date[2],
                hour: time[0],
                minute: time[1]
            ),
            repeats: false
        )
        return UNNotificationRequest(
            identifier: "\(ownedPrefix)\(schedule.deduplicationKey)",
            content: content,
            trigger: trigger
        )
    }

    private static func matches(
        _ pending: UNNotificationRequest?,
        _ desired: UNNotificationRequest
    ) -> Bool {
        guard let pending,
              let pendingTrigger = pending.trigger as? UNCalendarNotificationTrigger,
              let desiredTrigger = desired.trigger as? UNCalendarNotificationTrigger
        else {
            return false
        }
        return pending.content.title == desired.content.title
            && pending.content.body == desired.content.body
            && NSDictionary(dictionary: pending.content.userInfo).isEqual(
                to: desired.content.userInfo
            )
            && pendingTrigger.dateComponents == desiredTrigger.dateComponents
    }
}

extension Notification.Name {
    static let localNotificationScheduleDidChange = Notification.Name(
        "planterior.localNotificationScheduleDidChange"
    )
}
