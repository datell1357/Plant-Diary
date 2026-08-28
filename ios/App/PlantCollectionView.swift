import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantCollectionView: View {
    let openLegacyDetail: () -> Void
    let openCamera: () -> Void
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @State var search = ""
    @State var showsSearch = false
    @FocusState var searchFocused: Bool
    @Environment(\.sizeCategory) var sizeCategory
    let careCalendar = PlantCareCalendar()
    var body: some View {
        VStack(spacing: CollectionReferenceMetrics.shellSpacing) {
            header
            if showsSearch, !isTrueEmptyCollection {
                searchField
            }
            ScrollView(showsIndicators: false) {
                VStack(spacing: PlanteriorSpacing.large) {
                    stateBanner
                    if isTrueEmptyCollection {
                        trueEmptyState
                    } else if filteredPlants.isEmpty {
                        searchEmptyState
                    } else {
                        summaryBanner
                        if sizeCategory.isAccessibilityCategory {
                            HStack {
                                Spacer(minLength: 0)
                                addPlantButton
                            }
                        }
                        plantRows
                    }
                }
                .padding(.bottom, PlanteriorSpacing.large)
            }
            .accessibilityIdentifier("collection.screen")
            .accessibilityValue(collection.qaFixtureMountReceipt)
        }
        .overlay(alignment: .bottomTrailing) {
            if !isTrueEmptyCollection, !sizeCategory.isAccessibilityCategory {
                addPlantButton
                    .padding(.trailing, PlanteriorSpacing.extraSmall)
                    .padding(
                        .bottom,
                        PlanteriorLayout.tabBarHeight
                            + PlanteriorSpacing.extraLarge
                            - PlanteriorSpacing.extraSmall
                    )
            }
        }
        .padding(.horizontal, PlanteriorSpacing.large)
        .padding(.top, -PlanteriorSpacing.small)
        .background(PlanteriorPalette.canvas.color)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            collection.loadQAFixtureIfNeeded()
            collection.setSnapshotStateFromQA()
            collection.restoreScrollAnchor()
        }
    }
}
