import PlanteriorDomain
import SwiftUI

extension MiniHomeEditorView {
    var roomName: Binding<String> {
        Binding(
            get: { store.draft?.name ?? "" },
            set: { store.renameDraft($0) }
        )
    }

    var stateLabel: String {
        switch store.state {
        case .idle: "편집 중"
        case .saved: "저장 완료"
        case .failed: "저장 실패"
        case let .conflicted(serverRevision):
            "충돌 · 서버 \(serverRevision)판"
        }
    }

    /// Figma `items-selector-panel` content. Plants come from the registered
    /// collection; every other category lists owned inventory of that kind.
    var trayEntries: [MiniRoomTrayEntry] {
        guard let itemCategory = category.itemCategory else {
            return availablePlantOptions.map { option in
                MiniRoomTrayEntry(
                    target: .plant(option.id),
                    name: option.name,
                    asset: MiniRoomPlantPresentation.asset(
                        for: option.id,
                        named: option.name
                    )
                )
            }
        }
        let owned = Set(inventory.ownedItems.map(\.itemID))
        return inventory.catalog
            .filter { owned.contains($0.id) && $0.category == itemCategory }
            .map { item in
                MiniRoomTrayEntry(
                    target: .item(item.id),
                    name: item.name,
                    asset: StorageItemPresentation.asset(for: item)
                )
            }
    }

    var trayEmptyMessage: String {
        category.placesRegisteredPlant
            ? "배치할 수 있는 등록 식물이 없어요."
            : "창고에 보유한 \(category.title) 아이템이 없어요."
    }

    func asset(for placement: MiniHomePlacement) -> FigmaAsset {
        if let plantID = placement.plantID {
            let name = availablePlantOptions
                .first { $0.id == plantID }?.name
            return MiniRoomPlantPresentation.asset(
                for: plantID,
                named: name ?? ""
            )
        }
        guard let itemID = placement.itemID,
              let item = inventory.catalog.first(where: { $0.id == itemID })
        else {
            return .roomPlant01
        }
        return StorageItemPresentation.asset(for: item)
    }

    func label(for placement: MiniHomePlacement) -> String {
        guard let itemID = placement.itemID else {
            return "배치된 식물"
        }
        let item = inventory.catalog.first { $0.id == itemID }
        return item.map { "배치된 \($0.name)" } ?? "배치된 소품"
    }

    func requestClose() {
        isNameFocused = false
        if store.hasUnsavedChanges {
            showsUnsavedPrompt = true
        } else {
            dismiss()
        }
    }
}
