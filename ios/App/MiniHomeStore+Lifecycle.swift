import PlanteriorDomain

@MainActor
extension MiniHomeStore {
    func mount(accountID: String?, defaultDraft: MiniHome?) async {
        generation &+= 1
        let requestGeneration = generation
        resetVisibleState(accountID: accountID)
        guard let accountID else { return }

        let cached = cache.load(accountID: accountID)
        committed = cached?.home
        draft = cached?.home ?? defaultDraft
        mountBaseline = draft
        localCandidate = cache.localCandidate(accountID: accountID)
        state = cached == nil ? .mounting : .refreshing
        await load(accountID: accountID, generation: requestGeneration)
    }

    func refresh() async {
        guard let accountID else { return }
        generation &+= 1
        let requestGeneration = generation
        state = .refreshing
        await load(accountID: accountID, generation: requestGeneration)
    }

    private func load(accountID: String, generation: Int) async {
        do {
            let result = try await service.load(accountID: accountID)
            guard accepts(accountID: accountID, generation: generation) else { return }
            switch result {
            case let .empty(owner):
                guard owner == accountID else { return }
                cache.removeSnapshot(accountID: accountID)
                committed = nil
                draft = mountBaseline
            case let .snapshot(snapshot):
                try cache.store(snapshot)
                let preservesDraft = hasUnsavedChanges
                committed = snapshot.home
                if !preservesDraft {
                    draft = snapshot.home
                }
                mountBaseline = snapshot.home
            }
            state = .idle
        } catch {
            guard accepts(accountID: accountID, generation: generation) else { return }
            state = .loadFailed
        }
    }

    private func resetVisibleState(accountID: String?) {
        self.accountID = accountID
        committed = nil
        draft = nil
        conflictSnapshot = nil
        localCandidate = nil
        mountBaseline = nil
        draftHistory = []
        pendingSave = nil
        state = .idle
    }

    func accepts(accountID: String, generation: Int) -> Bool {
        !Task.isCancelled
            && self.accountID == accountID
            && self.generation == generation
    }
}
