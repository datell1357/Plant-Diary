#if DEBUG
    import Foundation
    import PlanteriorDomain

    @MainActor
    final class InMemoryMiniHomeAuthoritativeService: MiniHomeAuthoritativeService {
        private struct Receipt {
            let fingerprint: String
            let snapshot: MiniHomeVerifiedSnapshot
        }

        private var snapshots: [String: MiniHomeVerifiedSnapshot]
        private var receipts: [OperationID: Receipt] = [:]
        private let failsSave: Bool
        private var forcesConflict: Bool
        private let updatedAtEpochMillis: UInt64

        init(
            snapshots: [String: MiniHomeVerifiedSnapshot] = [:],
            failsSave: Bool = false,
            forcesConflict: Bool = false,
            updatedAtEpochMillis: UInt64 = 1_786_413_600_000
        ) {
            self.snapshots = snapshots
            self.failsSave = failsSave
            self.forcesConflict = forcesConflict
            self.updatedAtEpochMillis = updatedAtEpochMillis
        }

        func load(accountID: String) async throws -> MiniHomeAuthoritativeLoadResult {
            await Task.yield()
            guard let snapshot = snapshots[accountID] else {
                return .empty(accountID: accountID)
            }
            return .snapshot(snapshot)
        }

        func save(
            _ request: MiniHomeAuthoritativeSaveRequest
        ) async throws -> MiniHomeAuthoritativeSaveResult {
            await Task.yield()
            if failsSave {
                throw MiniHomeAuthoritativeError.transport
            }
            let fingerprint = MiniHomeCanonicalEncoding.request(
                accountID: request.accountID,
                expectedRevision: request.expectedRevision.rawValue,
                home: request.draft
            )
            if let receipt = receipts[request.operationID] {
                guard receipt.fingerprint == fingerprint else {
                    throw MiniHomeAuthoritativeError.failedPrecondition
                }
                return .duplicate(receipt.snapshot)
            }
            let current = snapshots[request.accountID]
            guard current?.home.revision ?? .zero == request.expectedRevision else {
                return .conflict(current)
            }
            if forcesConflict, let current {
                forcesConflict = false
                let competing = try committedSnapshot(
                    request: request,
                    name: "최근 저장된 방",
                    placements: current.home.placements
                )
                snapshots[request.accountID] = competing
                return .conflict(competing)
            }
            let committed = try committedSnapshot(
                request: request,
                name: request.draft.name,
                placements: request.draft.placements
            )
            snapshots[request.accountID] = committed
            receipts[request.operationID] = Receipt(
                fingerprint: fingerprint,
                snapshot: committed
            )
            return .committed(committed)
        }

        private func committedSnapshot(
            request: MiniHomeAuthoritativeSaveRequest,
            name: String,
            placements: [MiniHomePlacement]
        ) throws -> MiniHomeVerifiedSnapshot {
            let home = try MiniHome(
                id: request.draft.id,
                name: name,
                placements: placements,
                revision: request.expectedRevision.next(),
                updatedAt: MiniHomeResponseDecoder.instant(updatedAtEpochMillis)
            )
            return .verified(
                accountID: request.accountID,
                home: home,
                updatedAtEpochMillis: updatedAtEpochMillis
            )
        }
    }
#endif
