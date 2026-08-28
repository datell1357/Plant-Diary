import PlanteriorData
import PlanteriorDomain

@MainActor
extension MiniHomeStore {
    func undoDraft() {
        guard let previous = draftHistory.popLast() else { return }
        draft = previous
        state = .idle
    }

    func resetDraft() {
        draft = committed ?? mountBaseline ?? draft
        draftHistory = []
        state = .idle
    }

    func renameDraft(_ name: String) {
        guard let draft else { return }
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
        guard let draft else { return }
        recordDraftHistory()
        self.draft = replacingDraftPlacements(draft.placements + [placement])
        state = .idle
    }

    func replaceDraftPlacements(_ placements: [MiniHomePlacement]) {
        guard draft != nil else { return }
        recordDraftHistory()
        draft = replacingDraftPlacements(placements)
        state = .idle
    }

    func moveDraftPlacement(
        id: PlacementID,
        to position: MiniHomePosition
    ) throws {
        guard let draft else { return }
        recordDraftHistory()
        let placements = try draft.placements.map { placement in
            guard placement.id == id else { return placement }
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

    private func recordDraftHistory() {
        guard let draft else { return }
        draftHistory.append(draft)
    }

    private func replacingDraftPlacements(
        _ placements: [MiniHomePlacement]
    ) -> MiniHome? {
        guard let draft else { return nil }
        return MiniHome(
            id: draft.id,
            name: draft.name,
            placements: placements,
            revision: draft.revision,
            updatedAt: draft.updatedAt
        )
    }
}
