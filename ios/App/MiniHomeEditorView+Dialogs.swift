import SwiftUI

/// Unsaved-draft protection, conflict resolution, and the registered-plant
/// picker. These stay on `MiniHomeEditorView` so their bindings remain live.
extension MiniHomeEditorView {
    @ViewBuilder
    var unsavedDialogActions: some View {
        Button("저장", action: saveAndDismiss)
            .accessibilityIdentifier("minihome.unsaved.save")
        Button("변경사항 버리기", role: .destructive) {
            store.discardDraft()
            dismiss()
        }
        .accessibilityIdentifier("minihome.unsaved.discard")
        Button("계속 편집", role: .cancel) {}
            .accessibilityIdentifier("minihome.unsaved.cancel")
    }

    @ViewBuilder
    var conflictDialogActions: some View {
        Button("내 변경 다시 저장") {
            resolveConflict(.save)
        }
        .accessibilityIdentifier("minihome.conflict.save")
        Button("최근 저장본 사용", role: .destructive) {
            resolveConflict(.discard)
        }
        .accessibilityIdentifier("minihome.conflict.discard")
        Button("나중에 결정", role: .cancel) {
            resolveConflict(.cancel)
        }
        .accessibilityIdentifier("minihome.conflict.cancel")
    }

    var plantPicker: some View {
        PlantMiniaturePicker(
            options: availablePlantOptions,
            select: { option in
                addPlant(option.id)
                showsPlantPicker = false
            },
            requestRegistration: {
                errorMessage = "도감 탭에서 식물을 먼저 등록해 주세요."
                showsPlantPicker = false
            }
        )
    }
}
