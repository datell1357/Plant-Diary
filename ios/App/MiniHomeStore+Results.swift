@MainActor
extension MiniHomeStore {
    func apply(_ result: MiniHomeAuthoritativeSaveResult) {
        switch result {
        case let .committed(snapshot), let .duplicate(snapshot):
            do {
                try cache.store(snapshot)
            } catch {
                state = .failed
                return
            }
            committed = snapshot.home
            draft = snapshot.home
            mountBaseline = snapshot.home
            draftHistory = []
            conflictSnapshot = nil
            pendingSave = nil
            state = .saved
        case let .conflict(snapshot):
            conflictSnapshot = snapshot
            pendingSave = nil
            state = .conflicted(
                latestRevision: snapshot?.home.revision.rawValue ?? 0
            )
        }
    }

    func applyConflictSnapshot() {
        guard let snapshot = conflictSnapshot else {
            committed = nil
            draft = mountBaseline
            state = .idle
            return
        }
        do {
            try cache.store(snapshot)
        } catch {
            state = .failed
            return
        }
        committed = snapshot.home
        draft = snapshot.home
        mountBaseline = snapshot.home
        draftHistory = []
        conflictSnapshot = nil
        pendingSave = nil
        state = .idle
    }
}
