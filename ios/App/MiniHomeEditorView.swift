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
    @State var errorMessage: String?
    @State var showsUnsavedPrompt = false
    @State var showsConflictPrompt = false
    @State var showsPlantPicker = false
    @State var showsRoomSettings = false
    @State var opensPlantPickerAfterRoomSettings = false
    @State var category = MiniRoomCategory.plant
    @State var selectedEntryID: String?
    @FocusState var isNameFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            MiniRoomEditorHeader(
                close: requestClose,
                showRoomSettings: { showsRoomSettings = true },
                save: save
            )
            .padding(.top, Self.figmaStatusBarHeight)
            roomScrollRegion
            editorControls
        }
        .background(PlanteriorPalette.canvas.color)
        .ignoresSafeArea(edges: .top)
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
        .sheet(
            isPresented: $showsRoomSettings,
            onDismiss: presentDeferredPlantPicker
        ) {
            roomSettings
        }
        .sheet(isPresented: $showsPlantPicker) {
            plantPicker
        }
    }

    /// The Figma board centers the 358x330 room in the space between the fixed
    /// 52pt header and category strip. Keeping a non-scrolling `ScrollView`
    /// preserves the editor's established accessibility container while making
    /// the initial capture position deterministic.
    private var roomScrollRegion: some View {
        GeometryReader { geometry in
            ScrollView {
                roomContent
                    .padding(.top, Self.figmaCanvasTopInset)
                    .frame(
                        maxWidth: .infinity,
                        minHeight: geometry.size.height,
                        alignment: .top
                    )
            }
            .scrollDisabled(true)
            .scrollBounceBehavior(.basedOnSize)
            .accessibilityIdentifier("minihome.editor")
        }
    }

    @ViewBuilder
    private var roomContent: some View {
        if let draft = store.draft {
            MiniHomeEditorCanvas(
                room: draft,
                placementAsset: asset(for:),
                placementLabel: label(for:),
                move: moveByDrag,
                moveBy: moveBy
            )
            .frame(maxWidth: Self.figmaCanvasWidth)
            .padding(.horizontal, Self.figmaCanvasGutter)
        }
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

    private var roomSettings: some View {
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

    private func requestPlantPickerFromRoomSettings() {
        opensPlantPickerAfterRoomSettings = true
        showsRoomSettings = false
    }

    private func presentDeferredPlantPicker() {
        guard opensPlantPickerAfterRoomSettings else {
            return
        }
        opensPlantPickerAfterRoomSettings = false
        showsPlantPicker = true
    }

    private static let figmaStatusBarHeight: CGFloat = 48
    private static let figmaCanvasWidth: CGFloat = 358
    private static let figmaCanvasGutter: CGFloat = 22
    private static let figmaCanvasTopInset: CGFloat = 100

    var reduceMotion: Bool {
        systemReduceMotion
            || ProcessInfo.processInfo.environment["QA_REDUCE_MOTION"] == "1"
    }
}
