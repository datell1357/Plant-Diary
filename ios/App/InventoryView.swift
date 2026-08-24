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
    @State var seasonalOnly = false
    @State var sortDescending: Bool
    @State var visibleItemLimit: Int
    @State var message: String?
    @State var selectedItem: ShopItem?
    let now: Instant?

    init() {
        let now = Self.runtimeInstant()
        self.now = now
        _mode = State(initialValue: Self.initialMode)
        _sortDescending = State(initialValue: Self.initialSortDescending)
        _visibleItemLimit = State(initialValue: Self.initialVisibleItemLimit)
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
            VStack(alignment: .leading, spacing: 0) {
                storageHeader
                if mode == .shop {
                    shopCredit
                }
                categoryFilters
                if mode == .warehouse {
                    warehouse
                } else {
                    ShopView(
                        entries: shopPage.entries,
                        acquire: acquire,
                        showDetail: { selectedItem = $0 }
                    )
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(
            .top,
            PlanteriorControl.minimumTarget + PlanteriorSpacing.extraSmall
        )
        .ignoresSafeArea(edges: .top)
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
