import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct InventoryView: View {
    @EnvironmentObject var auth: AuthRuntime
    @EnvironmentObject var miniHomeStore: MiniHomeStore
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
                failFirstAcquisition: Self.failsFirstAcquisition,
                authoritativeService: Self.authoritativeService
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
        GeometryReader { viewport in
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
                            rowSpacing: InventoryReferenceMetrics.shopGridRowSpacing(
                                scrollBodyHeight: viewport.size.height
                            ),
                            acquire: acquire,
                            showDetail: { selectedItem = $0 }
                        )
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(
                .top,
                InventoryReferenceMetrics.contentTopCorrection(
                    measuredSafeAreaTop: viewport.safeAreaInsets.top
                )
            )
            .accessibilityIdentifier("storage.screen")
        }
        .background(PlanteriorPalette.canvas.color.ignoresSafeArea())
        .environment(\.sizeCategory, effectiveSizeCategory)
        .toolbar(.hidden, for: .navigationBar)
        .task(id: accountScopeID) {
            repository.mount(accountID: accountScopeID)
            collection.mount(accountID: accountScopeID)
            collection.loadQAFixtureIfNeeded()
            progression.mount(accountID: progressionAccountID)
            progression.seedQAIfNeeded()
            repository.seedQAIfNeeded()
            _ = await repository.refreshAuthoritative()
            repository.synchronizeAppliedItems(with: miniHomeStore.committed)
        }
        .onChange(of: miniHomeStore.committed) { _, room in
            repository.synchronizeAppliedItems(with: room)
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

struct InventoryHeaderActionPresentation: Equatable {
    let systemImage: String
    let identifier: String
    let accessibilityLabel: String
}

enum InventoryMode {
    case warehouse
    case shop

    var headerAction: InventoryHeaderActionPresentation {
        switch self {
        case .warehouse:
            InventoryHeaderActionPresentation(
                systemImage: "archivebox",
                identifier: "storage.mode.shop",
                accessibilityLabel: "아이템 상점 열기"
            )
        case .shop:
            InventoryHeaderActionPresentation(
                systemImage: "shippingbox",
                identifier: "storage.mode.warehouse",
                accessibilityLabel: "나의 창고 열기"
            )
        }
    }
}
