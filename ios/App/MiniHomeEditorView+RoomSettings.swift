import PlanteriorDesignSystem
import SwiftUI

/// The editor's secondary surface. The reference frame keeps the room name and
/// the save/conflict status out of the fixed editor chrome, so they live in a
/// medium-detent sheet reached from the title instead.
extension MiniHomeEditorView {
    var roomSettings: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
                roomNameField
                MiniHomeEditorStatusStrip(
                    stateLabel: stateLabel,
                    errorMessage: errorMessage,
                    conflictState: store.state,
                    addPlant: requestPlantPickerFromRoomSettings,
                    resolveConflict: { showsConflictPrompt = true }
                )
                Spacer()
            }
            .padding(PlanteriorSpacing.large)
            .background(PlanteriorPalette.canvas.color)
            .navigationTitle("방 설정")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("완료") { showsRoomSettings = false }
                }
            }
        }
        .presentationDetents([.medium])
        .accessibilityIdentifier("minihome.editor.room-settings")
    }

    var roomNameField: some View {
        TextField("미니홈 이름", text: roomName)
            .textFieldStyle(.plain)
            .font(PlanteriorTypography.body)
            .foregroundStyle(PlanteriorPalette.textPrimary.color)
            .padding(.horizontal, PlanteriorSpacing.medium)
            .frame(minHeight: PlanteriorControl.minimumTarget)
            .background(PlanteriorPalette.surface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                    .stroke(
                        PlanteriorPalette.border.color,
                        lineWidth: PlanteriorControl.hairline
                    )
            }
            .submitLabel(.done)
            .focused($isNameFocused)
            .onSubmit { isNameFocused = false }
            .accessibilityIdentifier("minihome.room-name")
    }

    func requestPlantPickerFromRoomSettings() {
        opensPlantPickerAfterRoomSettings = true
        showsRoomSettings = false
    }

    func presentDeferredPlantPicker() {
        guard opensPlantPickerAfterRoomSettings else {
            return
        }
        opensPlantPickerAfterRoomSettings = false
        showsPlantPicker = true
    }
}
