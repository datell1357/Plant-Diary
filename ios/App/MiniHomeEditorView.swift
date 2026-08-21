import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

/// Figma `myroom-editor` (`35:4`). Layer order is the frame's own skeleton:
/// `editor-header` / `room-canvas-container` / `category-tab-bar` /
/// `items-selector-panel` / `action-footer`. The header and the two lower
/// strips are fixed; only the room region scrolls, so the save action is never
/// pushed offscreen.
struct MiniHomeEditorView: View {
    @ObservedObject var store: MiniHomeStore
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @ObservedObject var inventory: InventoryRepository
    @Environment(\.dismiss) var dismiss
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @Environment(\.sizeCategory) private var sizeCategory
    @State var errorMessage: String?
    @State var showsUnsavedPrompt = false
    @State var showsConflictPrompt = false
    @State var showsPlantPicker = false
    @State var category = MiniRoomCategory.plant
    @State var selectedEntryID: String?
    @FocusState var isNameFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            MiniRoomEditorHeader(close: requestClose, save: save)
            if sizeCategory.isAccessibilityCategory {
                ScrollView {
                    VStack(spacing: 0) {
                        roomContent
                        editorControls
                    }
                }
                .scrollBounceBehavior(.basedOnSize)
                .accessibilityIdentifier("minihome.editor")
            } else {
                roomScrollRegion
                editorControls
            }
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationBarHidden(true)
        .interactiveDismissDisabled(store.hasUnsavedChanges)
        .accessibilityElement(children: .contain)
        .confirmationDialog(
            "저장하지 않은 변경사항이 있어요.",
            isPresented: $showsUnsavedPrompt
        ) {
            unsavedDialogActions
        }
        .confirmationDialog(
            "다른 기기에서 먼저 저장했어요.",
            isPresented: $showsConflictPrompt
        ) {
            conflictDialogActions
        }
        .sheet(isPresented: $showsPlantPicker) {
            plantPicker
        }
    }

    private var roomScrollRegion: some View {
        ScrollView { roomContent }
            .scrollBounceBehavior(.basedOnSize)
            .accessibilityIdentifier("minihome.editor")
    }

    private var roomContent: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
            roomNameField
            if let draft = store.draft {
                MiniHomeEditorCanvas(
                    room: draft,
                    placementAsset: asset(for:),
                    placementLabel: label(for:),
                    move: moveByDrag,
                    moveBy: moveBy
                )
            }
            MiniHomeEditorStatusStrip(
                stateLabel: stateLabel,
                errorMessage: errorMessage,
                conflictState: store.state,
                addPlant: { showsPlantPicker = true },
                resolveConflict: { showsConflictPrompt = true }
            )
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .padding(.top, PlanteriorSpacing.large)
        .padding(.bottom, PlanteriorSpacing.section)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var editorControls: some View {
        MiniRoomEditorTabBar(
            selection: $category,
            reduceMotion: reduceMotion
        )
        MiniRoomEditorTray(
            entries: trayEntries,
            selectedEntryID: selectedEntryID,
            emptyMessage: trayEmptyMessage,
            select: place
        )
        MiniRoomEditorFooter(
            canUndo: store.canUndoDraft,
            canReset: store.hasUnsavedChanges,
            undo: undo,
            reset: reset
        )
    }

    private var roomNameField: some View {
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

    var reduceMotion: Bool {
        systemReduceMotion
            || ProcessInfo.processInfo.environment["QA_REDUCE_MOTION"] == "1"
    }
}
