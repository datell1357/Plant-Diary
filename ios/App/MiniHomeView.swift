import Foundation
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct MiniHomeView: View {
    @EnvironmentObject private var store: MiniHomeStore
    @StateObject private var inventory: InventoryRepository
    @State private var showsEditor = false
    @State private var sharePresentation: MiniHomeSharePresentation?
    @Environment(\.sizeCategory) private var sizeCategory

    init() {
        let now = Self.runtimeInstant()
        _inventory = StateObject(
            wrappedValue: InventoryRepository(
                now: now,
                allowsLocalAcquisition: InventoryView.allowsLocalAcquisition,
                authoritativeService: InventoryView.authoritativeService
            )
        )
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
                storeStatus
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
        .accessibilityHidden(showsEditor)
        .task(id: store.accountID) {
            inventory.mount(accountID: store.accountID)
            inventory.seedQAIfNeeded()
            _ = await inventory.refreshAuthoritative()
            inventory.synchronizeAppliedItems(with: store.committed)
        }
        .onChange(of: store.committed) { _, room in
            inventory.synchronizeAppliedItems(with: room)
        }
        .fullScreenCover(isPresented: $showsEditor) {
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

    @ViewBuilder
    private var storeStatus: some View {
        switch store.state {
        case .mounting, .refreshing:
            ProgressView("저장본 불러오는 중")
                .accessibilityIdentifier("minihome.loading")
        case .loadFailed:
            VStack(alignment: .leading, spacing: 8) {
                Text("저장본을 새로 불러오지 못했어요.")
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("minihome.load-error")
                PlanteriorSecondaryButton("다시 불러오기") {
                    Task { await store.refresh() }
                }
                .accessibilityIdentifier("minihome.refresh")
            }
        case .idle, .saving, .saved, .failed, .conflicted:
            EmptyView()
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

    static func runtimeInstant() -> Instant? {
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

    static func defaultDraft(updatedAt: Instant?) throws -> MiniHome? {
        guard let updatedAt else { return nil }
        return try MiniHome(
            id: MiniHomeID.parse("local-mini-home"),
            name: "나의 초록 방",
            placements: initialPlacements(
                environment: ProcessInfo.processInfo.environment
            ),
            revision: .zero,
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
