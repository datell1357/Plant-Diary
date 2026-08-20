import Foundation
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct MiniHomeView: View {
    @StateObject private var store: MiniHomeStore
    @StateObject private var inventory: InventoryRepository
    @State private var showsEditor = false
    @State private var sharePresentation: MiniHomeSharePresentation?
    @Environment(\.sizeCategory) private var sizeCategory
    private let initialDraft: MiniHome?
    private let accountID: String?

    init(accountID: String?) {
        let now = Self.runtimeInstant()
        HomeCommittedMiniHomeRepository(accountID: accountID)
            .seedQAIfNeeded()
        let conflict = MiniHomeConflictTrigger(
            enabled: Self.shouldForceConflict()
        )
        let repository = LocalMiniHomeRepository(
            accountID: accountID,
            now: now,
            shouldFailSave: { Self.shouldFailSave() },
            shouldForceConflict: { conflict.consume() }
        )
        _store = StateObject(
            wrappedValue: MiniHomeStore(repository: repository)
        )
        _inventory = StateObject(
            wrappedValue: InventoryRepository(
                now: now,
                allowsLocalAcquisition: InventoryView.allowsLocalAcquisition
            )
        )
        self.accountID = accountID
        initialDraft = Self.defaultDraft(updatedAt: now)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(store.committed?.name ?? "새 미니홈")
                    .font(PlanteriorTypography.screenTitle)
                    .accessibilityIdentifier("minihome.committed.name")
                if let room = store.committed ?? store.draft {
                    MiniHomeCanvasView(room: room)
                }
                PlanteriorPrimaryButton("미니홈 꾸미기") {
                    showsEditor = true
                }
                .accessibilityIdentifier("minihome.edit")
                if let committed = store.committed {
                    PlanteriorPrimaryButton("미니홈 공유") {
                        sharePresentation = MiniHomeSharePresentation(
                            room: committed
                        )
                    }
                    .accessibilityIdentifier("minihome.share")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .background(PlanteriorPalette.canvas.color)
        .environment(\.sizeCategory, effectiveSizeCategory)
        .navigationTitle("나의 미니홈")
        .accessibilityIdentifier("minihome.screen")
        .task {
            store.mount(defaultDraft: initialDraft)
            inventory.mount(accountID: accountID)
            inventory.seedQAIfNeeded()
        }
        .sheet(isPresented: $showsEditor) {
            MiniHomeEditorView(store: store, inventory: inventory)
                .environment(\.sizeCategory, effectiveSizeCategory)
        }
        .sheet(item: $sharePresentation) { presentation in
            NavigationStack {
                MiniHomeShareView(room: presentation.room)
            }
            .environment(\.sizeCategory, effectiveSizeCategory)
        }
    }

    private var effectiveSizeCategory: ContentSizeCategory {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_MINIHOME_SIZE_CATEGORY"
            ] == "AX5" {
                return .accessibilityExtraExtraExtraLarge
            }
        #endif
        return sizeCategory
    }

    private static func runtimeInstant() -> Instant? {
        #if DEBUG
            if let value = ProcessInfo.processInfo.environment[
                "QA_MINIHOME_NOW"
            ] {
                return try? Instant.parse(value)
            }
        #endif
        let formatter = ISO8601DateFormatter()
        return try? Instant.parse(formatter.string(from: Date()))
    }

    private static func shouldFailSave() -> Bool {
        #if DEBUG
            return ProcessInfo.processInfo.environment[
                "QA_MINIHOME_SAVE_FAILURE"
            ] == "1"
        #else
            return false
        #endif
    }

    private static func shouldForceConflict() -> Bool {
        #if DEBUG
            return ProcessInfo.processInfo.environment[
                "QA_MINIHOME_CONFLICT_ONCE"
            ] == "1"
        #else
            return false
        #endif
    }

    private static func defaultDraft(updatedAt: Instant?) -> MiniHome? {
        guard let updatedAt,
              let id = try? MiniHomeID.parse("local-mini-home"),
              let revision = try? Revision.parse(0)
        else {
            return nil
        }
        return MiniHome(
            id: id,
            name: "나의 초록 방",
            placements: [],
            revision: revision,
            updatedAt: updatedAt
        )
    }
}

private struct MiniHomeSharePresentation: Identifiable {
    let room: MiniHome

    var id: String {
        "\(room.id.rawValue)-\(room.revision.rawValue)"
    }
}

private final class MiniHomeConflictTrigger {
    private var enabled: Bool

    init(enabled: Bool) {
        self.enabled = enabled
    }

    func consume() -> Bool {
        guard enabled else {
            return false
        }
        enabled = false
        return true
    }
}
