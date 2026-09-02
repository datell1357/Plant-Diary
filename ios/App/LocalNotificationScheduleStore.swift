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
        let previousPrefix = Self.ownedPrefix(accountID: self.accountID)
        self.accountID = mountedAccountID
        key = "notifications.\(mountedAccountID).scheduled"
        restore()
        enqueueRemoval(prefixes: [previousPrefix])
        enqueueReconciliation()
    }

    func reconcile(_ request: NotificationScheduleRequest) throws {
        let planningRequest = LocalNotificationScheduleSupport.localPlanningRequest(request)
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
        enqueueRemoval(prefixes: [Self.ownedPrefix(accountID: accountID)])
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
        schedules = LocalNotificationScheduleSupport.restore(
            defaults: defaults,
            key: key,
            decode: { try? JSONDecoder().decode([StoredSchedule].self, from: $0) }
        )
    }

    private func persist() {
        LocalNotificationScheduleSupport.persist(schedules, defaults: defaults, key: key) {
            try? JSONEncoder().encode($0)
        }
    }

    private var deliverySchedules: [StoredSchedule] {
        LocalNotificationScheduleSupport.deliverySchedules(
            schedules,
            authorization: authorization,
            quietHours: quietHours(),
            time: \.time,
            date: \.date
        )
    }

    private func enqueueReconciliation() {
        let prefix = Self.ownedPrefix(accountID: accountID)
        let requests = deliverySchedules.map(notificationRequest)
        let center = notificationCenter
        let previous = pendingOperation
        pendingOperation = Task {
            await previous?.value
            let pending = await center.pendingRequests()
            let desiredByID = Dictionary(uniqueKeysWithValues: requests.map { ($0.identifier, $0) })
            let owned = pending.filter { $0.identifier.hasPrefix(prefix) }
            let stale = owned.map(\.identifier).filter { desiredByID[$0] == nil }
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
                do { try await center.add(request) } catch {
                    await center.removePendingRequests(withIdentifiers: [request.identifier])
                }
            }
            let reconciled = await center.pendingRequests()
            guard prefix == Self.ownedPrefix(accountID: accountID) else {
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
            if prefixes.contains(Self.ownedPrefix(accountID: accountID)) {
                scheduledCount = 0
                NotificationCenter.default.post(
                    name: .localNotificationScheduleDidChange,
                    object: nil
                )
            }
        }
    }

    private func notificationRequest(_ schedule: StoredSchedule) -> UNNotificationRequest {
        LocalNotificationScheduleSupport.notificationRequest(
            plantID: schedule.plantID,
            date: schedule.date,
            time: schedule.time,
            identifier: "\(Self.ownedPrefix(accountID: accountID))\(schedule.deduplicationKey)"
        )
    }

    private static func matches(
        _ pending: UNNotificationRequest?,
        _ desired: UNNotificationRequest
    ) -> Bool {
        LocalNotificationScheduleSupport.matches(pending, desired)
    }
}
