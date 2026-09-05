import Combine
import Foundation
import PlanteriorDomain

enum MiniHomeStoreState: Equatable {
    case idle
    case mounting
    case refreshing
    case saving
    case saved
    case failed
    case loadFailed
    case conflicted(latestRevision: UInt64)
}

enum MiniHomeConflictResolution {
    case save
    case discard
    case cancel
}

@MainActor
final class MiniHomeStore: ObservableObject {
    @Published var committed: MiniHome?
    @Published var draft: MiniHome?
    @Published var state = MiniHomeStoreState.idle
    @Published var conflictSnapshot: MiniHomeVerifiedSnapshot?
    @Published var localCandidate: MiniHomeLocalCandidate?
    @Published var accountID: String?

    let service: any MiniHomeAuthoritativeService
    let cache: MiniHomeVerifiedCache
    let makeOperationID: () throws -> OperationID
    var generation = 0
    var draftHistory: [MiniHome] = []
    var mountBaseline: MiniHome?
    var pendingSave: PendingMiniHomeSave?

    init(
        service: any MiniHomeAuthoritativeService,
        cache: MiniHomeVerifiedCache,
        makeOperationID: @escaping () throws -> OperationID = {
            try OperationID.parse(UUID().uuidString)
        }
    ) {
        self.service = service
        self.cache = cache
        self.makeOperationID = makeOperationID
    }

    var hasUnsavedChanges: Bool {
        draft != committed
    }

    var canUndoDraft: Bool {
        !draftHistory.isEmpty
    }

    func save() async {
        await save(expectedRevision: conflictSnapshot?.home.revision)
    }

    func resolveConflict(_ resolution: MiniHomeConflictResolution) async {
        guard case .conflicted = state else { return }
        switch resolution {
        case .save:
            await save(expectedRevision: conflictSnapshot?.home.revision, forceNew: true)
        case .discard:
            applyConflictSnapshot()
        case .cancel:
            break
        }
    }

    func discardDraft() {
        draft = committed
        draftHistory = []
        conflictSnapshot = nil
        pendingSave = nil
        state = .idle
    }

    private func save(
        expectedRevision override: Revision?,
        forceNew: Bool = false
    ) async {
        guard state != .saving else { return }
        guard let accountID, let draft else {
            state = .failed
            return
        }
        // Preserve retries and explicit conflict resolution, but skip unchanged saves.
        if draft == committed, pendingSave == nil, conflictSnapshot == nil, !forceNew {
            return
        }
        let expectedRevision = override ?? committed?.revision ?? draft.revision
        let fingerprint = MiniHomeCanonicalEncoding.request(
            accountID: accountID,
            expectedRevision: expectedRevision.rawValue,
            home: draft
        )
        let operationID: OperationID
        do {
            operationID = try saveOperationID(
                fingerprint: fingerprint,
                forceNew: forceNew
            )
        } catch {
            state = .failed
            return
        }
        pendingSave = PendingMiniHomeSave(
            fingerprint: fingerprint,
            operationID: operationID
        )
        let requestGeneration = generation
        state = .saving
        do {
            let result = try await service.save(MiniHomeAuthoritativeSaveRequest(
                accountID: accountID,
                draft: draft,
                expectedRevision: expectedRevision,
                operationID: operationID
            ))
            guard accepts(accountID: accountID, generation: requestGeneration) else {
                return
            }
            apply(result)
        } catch {
            guard accepts(accountID: accountID, generation: requestGeneration) else {
                return
            }
            if error as? MiniHomeAuthoritativeError != .transport {
                pendingSave = nil
            }
            state = .failed
        }
    }

    private func saveOperationID(
        fingerprint: String,
        forceNew: Bool
    ) throws -> OperationID {
        if forceNew {
            return try makeOperationID()
        }
        guard let pendingSave else { return try makeOperationID() }
        if pendingSave.fingerprint == fingerprint {
            return pendingSave.operationID
        }
        return try makeOperationID()
    }
}

struct PendingMiniHomeSave {
    let fingerprint: String
    let operationID: OperationID
}
