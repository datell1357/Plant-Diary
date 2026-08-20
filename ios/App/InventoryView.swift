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
                allowsLocalAuthoritativeService:
                MilestoneProgressView.allowsLocalAuthoritativeService
            )
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
                storageHeader
                modeSelector
                categoryFilters
                if mode == .warehouse {
                    warehouse
                } else {
                    ShopView(
                        entries: shopPage.entries,
                        hasMore: shopPage.nextCursor != nil,
                        loadMore: { visibleItemLimit += 2 },
                        acquire: acquire,
                        showDetail: { selectedItem = $0 }
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, PlanteriorSpacing.large)
            .padding(.vertical, PlanteriorSpacing.small)
        }
        .background(PlanteriorPalette.canvas.color)
        .environment(\.sizeCategory, effectiveSizeCategory)
        .toolbar(.hidden, for: .navigationBar)
        .accessibilityIdentifier("storage.screen")
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
        .navigationDestination(isPresented: detailPresented) {
            if let selectedItem {
                InventoryItemDetailView(
                    item: selectedItem,
                    eligibility: eligibility(for: selectedItem),
                    isOwned: isOwned(selectedItem),
                    isApplied: isApplied(selectedItem),
                    message: message,
                    acquire: { acquire(selectedItem) },
                    togglePlacement: { togglePlacement(selectedItem) }
                )
                .environment(\.sizeCategory, effectiveSizeCategory)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if let message {
                Text(message)
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .padding(PlanteriorSpacing.medium)
                    .frame(maxWidth: .infinity)
                    .background(PlanteriorPalette.subtle.color)
                    .accessibilityIdentifier("storage.message")
            }
        }
    }

    private var detailPresented: Binding<Bool> {
        Binding(
            get: { selectedItem != nil },
            set: {
                if !$0 {
                    selectedItem = nil
                }
            }
        )
    }
}

enum InventoryMode {
    case warehouse
    case shop
}
