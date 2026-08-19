import Foundation
import PlanteriorData
import PlanteriorDomain

extension MiniHomeEditorView {
    var availablePlantIDs: [PersonalPlantID] {
        let plantIDs = collection.weatherPlantIDs
        #if DEBUG
            if plantIDs.isEmpty, let fallback = qaFallbackPlantID {
                return [fallback]
            }
        #endif
        return plantIDs
    }

    private var qaFallbackPlantID: PersonalPlantID? {
        guard ProcessInfo.processInfo.environment[
            "QA_COLLECTION_FIXTURE"
        ] == "1" else {
            return nil
        }
        return try? PersonalPlantID.parse("qa-plant")
    }

    var availablePlantOptions: [PlantMiniatureOption] {
        availablePlantIDs.enumerated().map { index, plantID in
            PlantMiniatureOption(
                id: plantID,
                name: collection.plants.indices.contains(index)
                    ? collection.plants[index].displayName
                    : "등록 식물 \(index + 1)"
            )
        }
    }

    func addPlant(_ plantID: PersonalPlantID) {
        isNameFocused = false
        guard let placementID = try? MiniHomeGeometry.nextPlacementID(
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
