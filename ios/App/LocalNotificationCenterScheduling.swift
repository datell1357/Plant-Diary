import CryptoKit
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
        let digest = SHA256.hash(data: Data(accountID.utf8))
        let scope = digest.map { String(format: "%02x", $0) }.joined()
        return "planterior.local.watering.\(scope)."
    }
}
