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

    init(repository: LocalMiniHomeRepository) {
        self.repository = repository
    }

    var hasUnsavedChanges: Bool {
        draft != committed
    }

    func mount(defaultDraft: MiniHome? = nil) {
        committed = repository.load()
        draft = committed ?? defaultDraft
        state = .idle
    }

    func renameDraft(_ name: String) {
        guard let draft else {
            return
        }
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
