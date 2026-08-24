import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantCollectionView: View {
    let openLegacyDetail: () -> Void
    let openCamera: () -> Void
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @State private var search = ""
    @State private var showsSearch = false
    @FocusState private var searchFocused: Bool
    @Environment(\.sizeCategory) var sizeCategory
    let careCalendar = PlantCareCalendar()
    var body: some View {
        VStack(spacing: 18) {
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
        }
        .overlay(alignment: .bottomTrailing) {
            if !isTrueEmptyCollection, !sizeCategory.isAccessibilityCategory {
                addPlantButton
                    .padding(.trailing, PlanteriorSpacing.extraSmall)
                    .padding(
                        .bottom,
                        PlanteriorLayout.tabBarHeight
                            + PlanteriorLayout.floatingActionSize
                            + PlanteriorSpacing.extraLarge
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

    private var addPlantButton: some View {
        PlanteriorFloatingActionButton(
            accessibilityLabel: "식물 추가",
            action: openCamera
        )
        .planteriorShadow(PlanteriorShadow.floatingAction)
        .accessibilityIdentifier("collection.add")
    }

    private var header: some View {
        HStack {
            Text("나의 도감")
                .font(PlanteriorTypography.pageTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("collection.title")
            Spacer()
            if !isTrueEmptyCollection {
                Button {
                    showsSearch = true
                    searchFocused = true
                } label: {
                    Image(systemName: "magnifyingglass")
                        .frame(width: 40, height: 40)
                        .background(PlanteriorPalette.surface.color)
                        .clipShape(Circle())
                        .overlay {
                            Circle().stroke(
                                PlanteriorPalette.border.color,
                                lineWidth: PlanteriorControl.hairline
                            )
                        }
                }
                .frame(
                    minWidth: PlanteriorControl.minimumTarget,
                    minHeight: PlanteriorControl.minimumTarget
                )
                .contentShape(Rectangle())
                .buttonStyle(.plain)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityLabel("내 식물 검색")
                .accessibilityIdentifier("collection.search.action")
            }
        }
        .padding(.horizontal, PlanteriorSpacing.small)
        .frame(minHeight: PlanteriorControl.minimumTarget)
    }

    private var searchField: some View {
        HStack(spacing: PlanteriorSpacing.small) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(PlanteriorPalette.textTertiary.color)
                .accessibilityHidden(true)
            TextField("내 식물 검색", text: $search)
                .focused($searchFocused)
                .submitLabel(.done)
                .accessibilityIdentifier("collection.search")
            if !search.isEmpty {
                Button {
                    search = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .frame(
                            width: PlanteriorControl.minimumTarget,
                            height: PlanteriorControl.minimumTarget
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("검색어 지우기")
            }
        }
        .padding(.leading, PlanteriorSpacing.large)
        .frame(minHeight: 48)
        .background(PlanteriorPalette.surface.color)
        .clipShape(Capsule())
        .overlay {
            Capsule().stroke(
                PlanteriorPalette.border.color,
                lineWidth: PlanteriorControl.hairline
            )
        }
    }

    private var summaryBanner: some View {
        Button(action: openLegacyDetail) {
            HStack(spacing: PlanteriorSpacing.medium) {
                Image(.collectionPlantIllustration)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 48, height: 48)
                    .accessibilityLabel("새싹 화분")
                    .accessibilityIdentifier("collection.summary.illustration")
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text("등록된 식물 총 \(collectionCount)개 🌱")
                        .font(PlanteriorTypography.cardTitle)
                        .foregroundStyle(PlanteriorPalette.accent.color)
                        .accessibilityIdentifier("collection.summary.title")
                    Text(summarySubtitle)
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .accessibilityIdentifier("collection.summary.subtitle")
                }
                Spacer(minLength: 0)
            }
            .padding(PlanteriorSpacing.large)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PlanteriorPalette.subtle.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("collection.open-detail")
    }

    var filteredPlants: [(offset: Int, element: PlantRegistrationDraft)] {
        guard !isTrueEmptyFixture else { return [] }
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return collection.plants.enumerated()
            .filter { $0.element.displayName != "비공개 식물" }
            .filter {
                let identity = collection.presentationIdentity(at: $0.offset)
                    ?? "draft-\($0.element.displayName)"
                let presentedName = PlantCarePresentation.collectionName(
                    for: identity,
                    fallback: $0.element.displayName
                )
                return query.isEmpty
                    || $0.element.displayName.localizedCaseInsensitiveContains(query)
                    || presentedName.localizedCaseInsensitiveContains(query)
            }
    }

    private var collectionCount: Int {
        collection.plants.filter { $0.displayName != "비공개 식물" }.count
    }

    private var summarySubtitle: String {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_COLLECTION_FIGMA_FIXTURE"] == "1" {
                return "초보 식집사 단계에서 씩씩하게 자라는 중!"
            }
        #endif
        guard let today else { return "물 주기 일정을 확인해 보세요" }
        let summary = collection.careSummary(today: today)
        return "오늘 돌봄 \(summary.dueToday)개 · 설정 필요 \(summary.unconfigured)개"
    }

    private var isTrueEmptyCollection: Bool {
        isTrueEmptyFixture || collectionCount == 0
    }

    private var isTrueEmptyFixture: Bool {
        #if DEBUG
            ProcessInfo.processInfo.environment["QA_COLLECTION_EMPTY"] == "1"
        #else
            false
        #endif
    }

    var today: CalendarDate? {
        #if DEBUG
            let value = ProcessInfo.processInfo.environment["QA_WATERING_TODAY"]
            if let value, let date = try? CalendarDate.parse(value) {
                return date
            }
        #endif
        return try? careCalendar.calendarDate(from: Date())
    }
}
