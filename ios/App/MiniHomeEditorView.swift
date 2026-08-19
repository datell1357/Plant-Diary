import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct MiniHomeEditorView: View {
    @ObservedObject var store: MiniHomeStore
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @Environment(\.dismiss) var dismiss
    @State var errorMessage: String?
    @State var showsUnsavedPrompt = false
    @State var showsConflictPrompt = false
    @State var showsPlantPicker = false
    @FocusState var isNameFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                TextField("미니홈 이름", text: roomName)
                    .textFieldStyle(.roundedBorder)
                    .frame(minHeight: PlanteriorControl.minimumTarget)
                    .submitLabel(.done)
                    .focused($isNameFocused)
                    .onSubmit {
                        isNameFocused = false
                    }
                    .accessibilityIdentifier("minihome.room-name")
                if let draft = store.draft {
                    MiniHomeEditorCanvas(
                        room: draft,
                        store: store,
                        errorMessage: $errorMessage
                    )
                }
                PlanteriorPrimaryButton("식물 추가") {
                    showsPlantPicker = true
                }
                .accessibilityIdentifier("minihome.add-plant")
                if let errorMessage {
                    Text(errorMessage)
                        .foregroundStyle(
                            PlanteriorPalette.textSecondary.color
                        )
                        .accessibilityIdentifier("minihome.save-error")
                }
                Text(stateLabel)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("minihome.state")
                stateMessage
                PlanteriorPrimaryButton("저장", action: save)
                    .accessibilityIdentifier("minihome.save")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("미니홈 꾸미기")
        .interactiveDismissDisabled(store.hasUnsavedChanges)
        .navigationBarBackButtonHidden(store.hasUnsavedChanges)
        .accessibilityIdentifier("minihome.editor")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button {
                    if store.hasUnsavedChanges {
                        showsUnsavedPrompt = true
                    } else {
                        dismiss()
                    }
                } label: {
                    Image(systemName: "xmark")
                }
                .accessibilityLabel("편집 닫기")
                .accessibilityIdentifier("minihome.close")
            }
        }
        .confirmationDialog(
            "저장하지 않은 변경사항이 있어요.",
            isPresented: $showsUnsavedPrompt
        ) {
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
        .confirmationDialog(
            "다른 기기에서 먼저 저장했어요.",
            isPresented: $showsConflictPrompt
        ) {
            Button("내 변경 다시 저장") {
                resolveConflict(.save)
            }
            .accessibilityIdentifier("minihome.conflict.save")
            Button("서버 버전 사용", role: .destructive) {
                resolveConflict(.discard)
            }
            .accessibilityIdentifier("minihome.conflict.discard")
            Button("나중에 결정", role: .cancel) {
                resolveConflict(.cancel)
            }
            .accessibilityIdentifier("minihome.conflict.cancel")
        }
        .sheet(isPresented: $showsPlantPicker) {
            PlantMiniaturePicker(
                options: availablePlantOptions,
                select: { option in
                    addPlant(option.id)
                    showsPlantPicker = false
                },
                requestRegistration: {
                    errorMessage =
                        "도감 탭에서 식물을 먼저 등록해 주세요."
                    showsPlantPicker = false
                }
            )
        }
    }
}
