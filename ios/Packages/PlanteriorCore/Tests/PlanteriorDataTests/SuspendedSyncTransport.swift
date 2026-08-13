import Foundation
@testable import PlanteriorData
import PlanteriorDomain

actor SuspendedTransport: SyncTransport {
    private var sendContinuation: CheckedContinuation<SyncTransportResult, Never>?
    private var startContinuation: CheckedContinuation<Void, Never>?
    private var started = false

    func send(
        _ mutation: SyncMutation,
        accountID: AccountID
    ) async -> SyncTransportResult {
        started = true
        startContinuation?.resume()
        startContinuation = nil
        return await withCheckedContinuation { sendContinuation = $0 }
    }

    func waitUntilSendStarts() async {
        guard !started else {
            return
        }
        await withCheckedContinuation { startContinuation = $0 }
    }

    func resume(with result: SyncTransportResult) {
        sendContinuation?.resume(returning: result)
        sendContinuation = nil
    }

    nonisolated func events(
        accountID: AccountID,
        domain: SyncDomain
    ) -> AsyncStream<SyncRemoteEvent> {
        AsyncStream { $0.finish() }
    }
}
