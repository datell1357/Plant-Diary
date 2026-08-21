import Foundation
import PlanteriorDomain

enum MiniHomeSaveOutcome {
    case committed(MiniHome)
    case conflict(MiniHome)
    case failed
}

struct LocalMiniHomeRepository {
    private let defaults: UserDefaults
    private let key: String
    private let now: Instant?
    private let shouldFailSave: () -> Bool
    private let shouldForceConflict: () -> Bool

    init(
        accountID: String?,
        defaults: UserDefaults = .standard,
        now: Instant?,
        shouldFailSave: @escaping () -> Bool = { false },
        shouldForceConflict: @escaping () -> Bool = { false }
    ) {
        self.defaults = defaults
        key = "home.\(accountID ?? "signed-out").committed-mini-home"
        self.now = now
        self.shouldFailSave = shouldFailSave
        self.shouldForceConflict = shouldForceConflict
    }

    func load() -> MiniHome? {
        guard let data = defaults.data(forKey: key) else {
            return nil
        }
        return try? JSONDecoder().decode(MiniHome.self, from: data)
    }

    func rename(_ name: String) throws -> MiniHomeSaveOutcome {
        let current = load()
        guard let initial = current ?? newRoom(named: name) else {
            return .failed
        }
        let draft = MiniHome(
            id: initial.id,
            name: name,
            placements: initial.placements,
            revision: initial.revision,
            updatedAt: initial.updatedAt
        )
        return try save(draft: draft, expectedRevision: initial.revision)
    }

    func save(
        draft: MiniHome,
        expectedRevision: Revision
    ) throws -> MiniHomeSaveOutcome {
        guard !shouldFailSave() else {
            return .failed
        }
        let current = load()
        if let current, current.revision != expectedRevision {
            return .conflict(current)
        }
        if current == nil, expectedRevision.rawValue != 0 {
            return .failed
        }
        guard let now else {
            return .failed
        }
        if shouldForceConflict(), let current {
            let authoritative = try MiniHome(
                id: current.id,
                name: "다른 기기의 방",
                placements: current.placements,
                revision: current.revision.next(),
                updatedAt: now
            )
            try persist(authoritative)
            return .conflict(authoritative)
        }
        let committed = try MiniHome(
            id: draft.id,
            name: draft.name,
            placements: draft.placements,
            revision: expectedRevision.next(),
            updatedAt: now
        )
        try persist(committed)
        return .committed(committed)
    }

    private func newRoom(named name: String) -> MiniHome? {
        guard let now,
              let id = try? MiniHomeID.parse("local-mini-home")
        else {
            return nil
        }
        return MiniHome(
            id: id,
            name: name,
            placements: [],
            revision: .zero,
            updatedAt: now
        )
    }

    private func persist(_ room: MiniHome) throws {
        let data = try JSONEncoder().encode(room)
        defaults.set(data, forKey: key)
        NotificationCenter.default.post(
            name: .miniHomeCommittedDidChange,
            object: nil
        )
    }
}

extension Notification.Name {
    static let miniHomeCommittedDidChange = Notification.Name(
        "miniHomeCommittedDidChange"
    )
}
