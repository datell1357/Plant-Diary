import Foundation
import UserNotifications

@MainActor
protocol LocalNotificationCenterScheduling: AnyObject {
    func pendingRequests() async -> [UNNotificationRequest]
    func add(_ request: UNNotificationRequest) async throws
    func removePendingRequests(withIdentifiers identifiers: [String]) async
}

final class SystemLocalNotificationCenter: LocalNotificationCenterScheduling {
    private let center = UNUserNotificationCenter.current()

    func pendingRequests() async -> [UNNotificationRequest] {
        await center.pendingNotificationRequests()
    }

    func add(_ request: UNNotificationRequest) async throws {
        try await center.add(request)
    }

    func removePendingRequests(withIdentifiers identifiers: [String]) async {
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
    }
}

extension LocalNotificationScheduleStore {
    static func ownedPrefix(accountID: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(
            CharacterSet(charactersIn: "-_")
        )
        let account = accountID.addingPercentEncoding(
            withAllowedCharacters: allowed
        ) ?? accountID
        return "planterior.watering.\(account)."
    }
}
