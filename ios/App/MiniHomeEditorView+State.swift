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
        case .mounting: "저장본 불러오는 중"
        case .refreshing: "저장본 새로고침 중"
        case .saving: "저장 중"
        case .saved: "저장 완료"
        case .failed: "저장 실패"
        case .loadFailed: "저장본 불러오기 실패"
        case let .conflicted(latestRevision):
            "충돌 · 저장본 \(latestRevision)판"
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
                    asset: MiniRoomPlantPresentation.trayAsset(for: option.id)
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

    func label(for placement: MiniHomePlacement) -> String {
        MiniRoomPlacementPresentation.accessibilityLabel(for: placement)
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
