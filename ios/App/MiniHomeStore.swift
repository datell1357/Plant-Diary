import Combine
import PlanteriorData
import PlanteriorDomain

enum MiniHomeStoreState: Equatable {
    case idle
    case saved
    case failed
    case conflicted(serverRevision: UInt64)
}

enum MiniHomeConflictResolution {
    case save
    case discard
    case cancel
}

@MainActor
final class MiniHomeStore: ObservableObject {
    @Published private(set) var committed: MiniHome?
    @Published private(set) var draft: MiniHome?
    @Published private(set) var state = MiniHomeStoreState.idle

    private let repository: LocalMiniHomeRepository
    /// Draft-only history for the Figma `action-footer` undo action. Cleared on
    /// mount, save, discard, and reset so it can never resurrect a committed room.
    private var draftHistory: [MiniHome] = []
    /// The room the editor started from. It is the committed room once one
    /// exists, and the mount default before the first save, so Reset has a real
    /// target on a brand-new account. Refreshed only at mount and on commit.
    private var mountBaseline: MiniHome?

    init(repository: LocalMiniHomeRepository) {
        self.repository = repository
    }

    var hasUnsavedChanges: Bool {
        draft != committed
    }

    func mount(defaultDraft: MiniHome? = nil) {
        committed = repository.load()
        draft = committed ?? defaultDraft
        mountBaseline = draft
        draftHistory = []
        state = .idle
    }

    var canUndoDraft: Bool {
        !draftHistory.isEmpty
    }

    /// Restores the draft to the state before the most recent draft edit. The
    /// committed room is never read or written here.
    func undoDraft() {
        guard let previous = draftHistory.popLast() else {
            return
        }
        draft = previous
        state = .idle
    }

    /// Returns the draft to the last committed room, or to the room the editor
    /// was mounted with when nothing is committed yet, without saving anything.
    func resetDraft() {
        draft = committed ?? mountBaseline ?? draft
        draftHistory = []
        state = .idle
    }

    private func recordDraftHistory() {
        guard let draft else {
            return
        }
        draftHistory.append(draft)
    }

    func renameDraft(_ name: String) {
        guard let draft else {
            return
        }
        recordDraftHistory()
        self.draft = MiniHome(
            id: draft.id,
            name: name,
            placements: draft.placements,
            revision: draft.revision,
            updatedAt: draft.updatedAt
        )
        state = .idle
    }

    func addDraftPlacement(_ placement: MiniHomePlacement) {
        guard let draft else {
            return
        }
        recordDraftHistory()
        self.draft = replacingDraftPlacements(
            draft.placements + [placement]
        )
        state = .idle
    }

    func moveDraftPlacement(
        id: PlacementID,
        to position: MiniHomePosition
    ) throws {
        guard let draft else {
            return
        }
        recordDraftHistory()
        let placements = try draft.placements.map { placement in
            guard placement.id == id else {
                return placement
            }
            return try MiniHomePlacement(
                id: placement.id,
                plantID: placement.plantID,
                itemID: placement.itemID,
                normalizedX: position.normalizedX,
                normalizedY: position.normalizedY,
                zIndex: placement.zIndex
            )
        }
        self.draft = replacingDraftPlacements(placements)
        state = .idle
    }

    func save() throws {
        guard let draft else {
            state = .failed
            return
        }
        let expectedRevision = committed?.revision ?? draft.revision
        switch try repository.save(
            draft: draft,
            expectedRevision: expectedRevision
        ) {
        case let .committed(saved):
            committed = saved
            self.draft = saved
            mountBaseline = saved
            draftHistory = []
            state = .saved
        case let .conflict(authoritative):
            committed = authoritative
            state = .conflicted(
                serverRevision: authoritative.revision.rawValue
            )
        case .failed:
            state = .failed
        }
    }

    func discardDraft() {
        draft = committed
        draftHistory = []
        state = .idle
    }

    func resolveConflict(
        _ resolution: MiniHomeConflictResolution
    ) throws {
        guard case .conflicted = state else {
            return
        }
        switch resolution {
        case .save:
            try save()
        case .discard:
            draft = committed
            draftHistory = []
            state = .idle
        case .cancel:
            break
        }
    }

    private func replacingDraftPlacements(
        _ placements: [MiniHomePlacement]
    ) -> MiniHome? {
        guard let draft else {
            return nil
        }
        return MiniHome(
            id: draft.id,
            name: draft.name,
            placements: placements,
            revision: draft.revision,
            updatedAt: draft.updatedAt
        )
    }
}
