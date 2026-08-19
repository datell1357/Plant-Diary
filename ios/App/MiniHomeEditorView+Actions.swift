import PlanteriorData
import PlanteriorDomain

extension MiniHomeEditorView {
    func addPlant() {
        isNameFocused = false
        guard let plantID = collection.weatherPlantIDs.first
            ?? (try? PersonalPlantID.parse("qa-plant")),
            let placementID = try? MiniHomeGeometry.nextPlacementID(
                existing: store.draft?.placements.map(\.id) ?? []
            )
        else {
            errorMessage = "배치할 식물을 찾지 못했어요."
            return
        }
        do {
            let placement = try MiniHomePlacement(
                id: placementID,
                plantID: plantID,
                itemID: nil,
                normalizedX: 0.5,
                normalizedY: 0.55,
                zIndex: store.draft?.placements.count ?? 0
            )
            store.addDraftPlacement(placement)
        } catch {
            errorMessage = "식물을 배치하지 못했어요."
        }
    }

    func save() {
        isNameFocused = false
        errorMessage = nil
        do {
            try store.save()
            if case .conflicted = store.state {
                showsConflictPrompt = true
            }
        } catch {
            errorMessage = "저장하지 못했어요. 초안은 그대로 남아 있어요."
        }
    }

    func saveAndDismiss() {
        save()
        if store.state == .saved {
            dismiss()
        }
    }

    func resolveConflict(
        _ resolution: MiniHomeConflictResolution
    ) {
        do {
            try store.resolveConflict(resolution)
        } catch {
            errorMessage = "충돌을 해결하지 못했어요."
        }
    }
}
