import Foundation
@testable import Planterior
import PlanteriorDomain
import XCTest

@MainActor
final class MiniHomeStoreServiceFake: MiniHomeAuthoritativeService {
    private(set) var snapshots: [String: MiniHomeVerifiedSnapshot]
    private(set) var requests: [MiniHomeAuthoritativeSaveRequest] = []
    private(set) var loadAccounts: [String] = []
    var nextSaveResult: MiniHomeAuthoritativeSaveResult?
    var saveFailures: [MiniHomeAuthoritativeError] = []
    var suspendedLoadAccount: String?
    var suspendsSave = false
    private var suspendedLoadContinuation: CheckedContinuation<Void, Never>?
    private var suspendedLoadResult: MiniHomeAuthoritativeLoadResult?
    private var suspendedSaveContinuation: CheckedContinuation<Void, Never>?
    private var suspendedSaveResult: MiniHomeAuthoritativeSaveResult?
    private var suspendedLoadEntered: XCTestExpectation?
    private var suspendedSaveEntered: XCTestExpectation?

    init(snapshots: [String: MiniHomeVerifiedSnapshot] = [:]) {
        self.snapshots = snapshots
    }

    func load(accountID: String) async throws -> MiniHomeAuthoritativeLoadResult {
        loadAccounts.append(accountID)
        if suspendedLoadAccount == accountID {
            suspendedLoadEntered?.fulfill()
            suspendedLoadEntered = nil
            await withCheckedContinuation { continuation in
                suspendedLoadContinuation = continuation
            }
            if let suspendedLoadResult {
                return suspendedLoadResult
            }
        }
        guard let snapshot = snapshots[accountID] else {
            return .empty(accountID: accountID)
        }
        return .snapshot(snapshot)
    }

    func save(
        _ request: MiniHomeAuthoritativeSaveRequest
    ) async throws -> MiniHomeAuthoritativeSaveResult {
        requests.append(request)
        if suspendsSave {
            suspendedSaveEntered?.fulfill()
            suspendedSaveEntered = nil
            await withCheckedContinuation { continuation in
                suspendedSaveContinuation = continuation
            }
            if let suspendedSaveResult {
                return suspendedSaveResult
            }
        }
        if !saveFailures.isEmpty {
            throw saveFailures.removeFirst()
        }
        if let nextSaveResult {
            self.nextSaveResult = nil
            return nextSaveResult
        }
        let current = snapshots[request.accountID]
        guard current?.home.revision ?? .zero == request.expectedRevision else {
            return .conflict(current)
        }
        let committed = try Self.snapshot(
            accountID: request.accountID,
            home: request.draft,
            revision: request.expectedRevision.next()
        )
        snapshots[request.accountID] = committed
        return .committed(committed)
    }

    func expectSuspendedLoad() -> XCTestExpectation {
        let expectation = XCTestExpectation(description: "Suspended load entered")
        suspendedLoadEntered = expectation
        return expectation
    }

    func resumeSuspendedLoad(with result: MiniHomeAuthoritativeLoadResult) {
        suspendedLoadResult = result
        suspendedLoadContinuation?.resume()
        suspendedLoadContinuation = nil
    }

    func expectSuspendedSave() -> XCTestExpectation {
        let expectation = XCTestExpectation(description: "Suspended save entered")
        suspendedSaveEntered = expectation
        return expectation
    }

    func resumeSuspendedSave(with result: MiniHomeAuthoritativeSaveResult) {
        suspendedSaveResult = result
        suspendedSaveContinuation?.resume()
        suspendedSaveContinuation = nil
    }

    private static func snapshot(
        accountID: String,
        home: MiniHome,
        revision: Revision
    ) throws -> MiniHomeVerifiedSnapshot {
        let committed = try MiniHome(
            id: home.id,
            name: home.name,
            placements: home.placements,
            revision: revision,
            updatedAt: Instant.parse("2026-08-11T02:00:00.000Z")
        )
        return try MiniHomeStoreFixture.verifiedSnapshot(
            accountID: accountID,
            home: committed,
            updatedAtEpochMillis: 1_786_413_600_000
        )
    }
}
