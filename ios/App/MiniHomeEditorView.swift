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
    @State var showsRoomSettings = false
    @State var opensPlantPickerAfterRoomSettings = false
    @State var category = MiniRoomCategory.plant
    @State var selectedEntryID: String?
    @FocusState var isNameFocused: Bool

    var body: some View {
        GeometryReader { viewport in
            VStack(spacing: 0) {
                MiniRoomEditorHeader(
                    close: requestClose,
                    showRoomSettings: { showsRoomSettings = true },
                    save: save
                )
                roomRegion
                editorControls
            }
            // At the accessibility sizes the wrapped strips paint their
            // complete Korean captions, so their intrinsic width exceeds the
            // 402pt frame. Clamping the root to the viewport keeps every strip
            // inside the window instead of centering a wider stack and pushing
            // the edge controls offscreen.
            .frame(width: viewport.size.width)
            .padding(
                .top,
                min(
                    PlanteriorSpacing.none,
                    MiniRoomReferenceMetrics.statusBarHeight
                        - viewport.safeAreaInsets.top
                )
            )
        }
        .background(PlanteriorPalette.canvas.color.ignoresSafeArea())
        .navigationBarHidden(true)
        .interactiveDismissDisabled(store.hasUnsavedChanges)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("minihome.editor")
        .confirmationDialog(
            "저장하지 않은 변경사항이 있어요.",
            isPresented: $showsUnsavedPrompt
        ) {
            unsavedDialogActions
        }
        .confirmationDialog(
            "다른 편집에서 먼저 저장했어요.",
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
    /// 52pt header and category strip. The region never scrolls, so it stays a
    /// plain container: an inert scroll view would only add an assistive
    /// container that reorders the room behind the lower control strips.
    ///
    /// The region carries no identifier of its own. Naming it collapsed it onto
    /// the canvas below, overwriting `minihome.editor.canvas` and erasing the
    /// room's assistive container while its placements stayed reachable; the
    /// editor viewport name now lives on the editor root instead.
    @ViewBuilder
    private var roomRegion: some View {
        if sizeCategory.isAccessibilityCategory {
            // The wrapped strips below claim their intrinsic height first, so
            // the room takes the remainder and never less than the readable
            // minimum. A greedy region here pinned the exact 330pt room and
            // compressed the strips until their rows overprinted each other.
            GeometryReader { geometry in
                roomContent(
                    height: max(
                        MiniRoomReferenceMetrics
                            .accessibilityCanvasMinimumHeight,
                        geometry.size.height
                    ),
                    availableWidth: geometry.size.width
                )
                .frame(
                    maxWidth: .infinity,
                    maxHeight: .infinity,
                    alignment: .top
                )
            }
            .frame(
                minHeight: MiniRoomReferenceMetrics
                    .accessibilityCanvasMinimumHeight
            )
            .layoutPriority(-1)
        } else {
            GeometryReader { geometry in
                roomContent(
                    height: MiniRoomReferenceMetrics.canvasSize.height,
                    availableWidth: geometry.size.width
                )
                .padding(.top, canvasTopInset)
                .frame(
                    maxWidth: .infinity,
                    minHeight: geometry.size.height,
                    alignment: .top
                )
            }
        }
    }

    /// The reference inset centers the exact 358x330 room between the fixed
    /// header and category strip. The accessibility layout has no spare
    /// vertical budget for it, so that branch starts the room directly under
    /// the header instead.
    private var canvasTopInset: CGFloat {
        Self.figmaCanvasTopInset
    }

    @ViewBuilder
    private func roomContent(
        height: CGFloat,
        availableWidth: CGFloat
    ) -> some View {
        if let draft = store.draft {
            MiniHomeEditorCanvas(
                room: draft,
                placementLabel: label(for:),
                move: moveByDrag,
                moveBy: moveBy,
                height: height
            )
            .frame(maxWidth: Self.figmaCanvasWidth)
            .padding(
                .horizontal,
                MiniRoomReferenceMetrics.canvasGutter(
                    availableWidth: availableWidth
                )
            )
        }
    }

    /// One source-ordered control container: category strip, tray, footer.
    /// Grouping them keeps assistive traversal in the same order a sighted
    /// customer works through the editor after the room itself.
    private var editorControls: some View {
        VStack(spacing: PlanteriorSpacing.none) {
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
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("minihome.editor.controls")
    }

    private static let figmaCanvasWidth: CGFloat = 358
    private static let figmaCanvasTopInset: CGFloat = 100

    var reduceMotion: Bool {
        systemReduceMotion
            || ProcessInfo.processInfo.environment["QA_REDUCE_MOTION"] == "1"
    }
}
