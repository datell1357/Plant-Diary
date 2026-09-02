@testable import Planterior
import UserNotifications

@MainActor
final class LocalNotificationCenterFake: LocalNotificationCenterScheduling {
    private(set) var requests: [UNNotificationRequest]
    private(set) var addedIdentifiers: [String] = []
    private(set) var removedIdentifiers: [String] = []
    private let failingIdentifiers: Set<String>

    init(
        requests: [UNNotificationRequest] = [],
        failingIdentifiers: Set<String> = []
    ) {
        self.requests = requests
        self.failingIdentifiers = failingIdentifiers
    }

    func pendingRequests() async -> [UNNotificationRequest] {
        requests
    }

    func add(_ request: UNNotificationRequest) async throws {
        addedIdentifiers.append(request.identifier)
        guard !failingIdentifiers.contains(request.identifier) else {
            throw LocalNotificationCenterFakeError.rejected
        }
        requests.removeAll { $0.identifier == request.identifier }
        requests.append(request)
    }

    func removePendingRequests(withIdentifiers identifiers: [String]) async {
        removedIdentifiers.append(contentsOf: identifiers)
        requests.removeAll { identifiers.contains($0.identifier) }
    }

    func resetOperations() {
        addedIdentifiers = []
        removedIdentifiers = []
    }

    func seed(_ request: UNNotificationRequest) {
        requests.append(request)
    }
}

private enum LocalNotificationCenterFakeError: Error {
    case rejected
}
