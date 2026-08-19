import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct InventoryView: View {
    @EnvironmentObject var auth: AuthRuntime
    @Environment(\.sizeCategory) var sizeCategory
    @StateObject var repository: InventoryRepository
    @StateObject var collection = LocalPlantCollectionStore()
    @StateObject var progression: MilestoneRepository
    @State var mode: InventoryMode
    @State var category: ItemCategory?
    @State var sortDescending = false
    @State var visibleItemLimit = 2
    @State var message: String?
    @State var selectedItem: ShopItem?
    let now: Instant?

    init() {
        let now = Self.runtimeInstant()
        let allowsProgression =
            MilestoneProgressView.allowsLocalAuthoritativeService
        self.now = now
        _mode = State(initialValue: Self.initialMode)
        _repository = StateObject(
            wrappedValue: InventoryRepository(
                now: now,
                allowsLocalAcquisition: Self.allowsLocalAcquisition,
                failFirstAcquisition: Self.failsFirstAcquisition
            )
        )
        _progression = StateObject(
            wrappedValue: MilestoneRepository(
                now: now,
                allowsLocalAuthoritativeService: allowsProgression
            )
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if mode == .warehouse {
                    warehouse
                } else {
                    ShopView(
                        entries: shopPage.entries,
                        hasMore: shopPage.nextCursor != nil,
                        loadMore: {
                            visibleItemLimit += 2
                        },
                        acquire: acquire,
                        showDetail: { selectedItem = $0 }
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .background(PlanteriorPalette.canvas.color)
        .environment(\.sizeCategory, effectiveSizeCategory)
        .navigationTitle("창고")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("storage.screen")
        .safeAreaInset(edge: .top) {
            VStack(spacing: 8) {
                if !repository.catalog.isEmpty {
                    Text("보유 \(repository.ownedItems.count)개")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(
                            PlanteriorPalette.textSecondary.color
                        )
                        .frame(
                            maxWidth: .infinity,
                            alignment: .leading
                        )
                        .accessibilityIdentifier("storage.ready")
                }
                modeSelector
                categoryFilters
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 8)
            .background(PlanteriorPalette.canvas.color)
        }
        .toolbar {
            if mode == .shop {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(
                        sortDescending ? "이름 오름차순" : "이름 내림차순"
                    ) {
                        sortDescending.toggle()
                        visibleItemLimit = 2
                    }
                    .accessibilityIdentifier("shop.sort")
                }
            }
        }
        .task(id: accountScopeID) {
            repository.mount(accountID: accountScopeID)
            collection.mount(accountID: accountScopeID)
            collection.loadQAFixtureIfNeeded()
            progression.mount(accountID: progressionAccountID)
            progression.seedQAIfNeeded()
            HomeCommittedMiniHomeRepository(accountID: accountScopeID)
                .seedQAIfNeeded()
            repository.seedQAIfNeeded()
        }
        .sheet(item: $selectedItem) {
            let item = $0
            InventoryItemDetailView(
                item: item,
                isOwned: repository.ownedItems.contains { owned in
                    owned.itemID == item.id
                }
            )
            .environment(\.sizeCategory, effectiveSizeCategory)
        }
        .safeAreaInset(edge: .bottom) {
            if let message {
                Text(message)
                    .foregroundStyle(
                        PlanteriorPalette.textPrimary.color
                    )
                    .padding(12)
                    .frame(maxWidth: .infinity)
                    .background(PlanteriorPalette.subtle.color)
                    .accessibilityIdentifier("storage.message")
            }
        }
    }
}

enum InventoryMode {
    case warehouse
    case shop
}
