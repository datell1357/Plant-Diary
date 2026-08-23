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
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_MINIHOME_FIGMA_FIXTURE"
            ] == "1" {
                return [
                    "몬스테라", "스투키", "산세베리아", "아레카야자", "고무나무"
                ].enumerated().compactMap { index, name in
                    guard let plantID = try? PersonalPlantID.parse(
                        "figma-room-plant-\(index)"
                    ) else {
                        return nil
                    }
                    return PlantMiniatureOption(id: plantID, name: name)
                }
            }
        #endif
        return availablePlantIDs.enumerated().map { index, plantID in
            PlantMiniatureOption(
                id: plantID,
                name: collection.plants.indices.contains(index)
                    ? collection.plants[index].displayName
                    : "등록 식물 \(index + 1)"
            )
        }
    }

    /// Figma tray tap: select the entry and place it in the room. Plants and
    /// owned items share one placement path so undo/reset behave identically.
    func place(_ entry: MiniRoomTrayEntry) {
        selectedEntryID = entry.id
        if let plantID = entry.plantID {
            addPlant(plantID)
        } else if let itemID = entry.itemID {
            addPlacement(plantID: nil, itemID: itemID)
        }
    }

    func addPlant(_ plantID: PersonalPlantID) {
        addPlacement(plantID: plantID, itemID: nil)
    }

    private func addPlacement(plantID: PersonalPlantID?, itemID: ItemID?) {
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
                itemID: itemID,
                normalizedX: 0.5,
                normalizedY: 0.55,
                zIndex: store.draft?.placements.count ?? 0
            )
            store.addDraftPlacement(placement)
        } catch {
            errorMessage = "식물을 배치하지 못했어요."
        }
    }

    func undo() {
        isNameFocused = false
        errorMessage = nil
        selectedEntryID = nil
        store.undoDraft()
    }

    func reset() {
        isNameFocused = false
        errorMessage = nil
        selectedEntryID = nil
        store.resetDraft()
    }

    func moveByDrag(
        _ placement: MiniHomePlacement,
        to position: MiniHomePosition?
    ) {
        guard let position else {
            errorMessage = "식물 위치를 옮기지 못했어요."
            return
        }
        applyMove(placement, to: position)
    }

    func moveBy(
        _ placement: MiniHomePlacement,
        horizontalDelta: Double,
        verticalDelta: Double
    ) {
        let nextX = min(max(placement.normalizedX + horizontalDelta, 0), 1)
        let nextY = min(max(placement.normalizedY + verticalDelta, 0), 1)
        guard let position = try? MiniHomePosition(
            normalizedX: nextX,
            normalizedY: nextY
        ) else {
            errorMessage = "식물 위치를 옮기지 못했어요."
            return
        }
        applyMove(placement, to: position)
    }

    private func applyMove(
        _ placement: MiniHomePlacement,
        to position: MiniHomePosition
    ) {
        do {
            try store.moveDraftPlacement(id: placement.id, to: position)
        } catch {
            errorMessage = "식물 위치를 옮기지 못했어요."
        }
    }

    func save() {
        isNameFocused = false
        errorMessage = nil
        do {
            try store.save()
            if case .conflicted = store.state {
                showsConflictPrompt = true
            } else if case .failed = store.state {
                showsRoomSettings = true
            }
        } catch {
            errorMessage = "저장하지 못했어요. 초안은 그대로 남아 있어요."
            showsRoomSettings = true
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
