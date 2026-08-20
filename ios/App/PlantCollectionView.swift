import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantCollectionView: View {
    let openLegacyDetail: () -> Void
    let openCamera: () -> Void
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @State private var search = ""
    @FocusState private var searchFocused: Bool
    let careCalendar = PlantCareCalendar()

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: PlanteriorSpacing.medium) {
                header
                searchField
                ScrollView {
                    VStack(spacing: PlanteriorSpacing.small) {
                        stateBanner
                        if isTrueEmptyCollection {
                            trueEmptyState
                        } else if filteredPlants.isEmpty {
                            searchEmptyState
                        } else {
                            summaryBanner
                            plantRows
                        }
                    }
                    .padding(.bottom, 76)
                }
                .accessibilityIdentifier("collection.screen")
            }
            .padding(.horizontal, PlanteriorSpacing.large)
            .padding(.top, PlanteriorSpacing.small)

            if !isTrueEmptyCollection {
                Button(action: openCamera) {
                    Image(systemName: "plus")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                        .frame(width: 56, height: 56)
                        .background(PlanteriorPalette.accent.color)
                        .clipShape(Circle())
                }
                .accessibilityLabel("식물 추가")
                .accessibilityIdentifier("collection.add")
                .padding(.trailing, PlanteriorSpacing.large)
                .padding(.bottom, PlanteriorSpacing.large)
            }
        }
        .background(PlanteriorPalette.canvas.color)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            collection.loadQAFixtureIfNeeded()
            collection.setSnapshotStateFromQA()
            collection.restoreScrollAnchor()
        }
    }

    private var header: some View {
        HStack {
            Text("나의 도감")
                .font(PlanteriorTypography.pageTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("collection.title")
            Spacer()
            Button {
                searchFocused = true
            } label: {
                Image(systemName: "magnifyingglass")
                    .frame(
                        width: PlanteriorControl.minimumTarget,
                        height: PlanteriorControl.minimumTarget
                    )
                    .background(PlanteriorPalette.surface.color)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(PlanteriorPalette.textPrimary.color)
            .accessibilityLabel("내 식물 검색")
            .accessibilityIdentifier("collection.search.action")
        }
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
                    .frame(width: 40, height: 40)
                    .accessibilityLabel("새싹 화분")
                    .accessibilityIdentifier("collection.summary.illustration")
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text("등록된 식물 총 \(collectionCount)개 🌱")
                        .font(PlanteriorTypography.cardTitle)
                        .foregroundStyle(PlanteriorPalette.textPrimary.color)
                        .accessibilityIdentifier("collection.summary.title")
                    Text("오늘의 돌봄 상태를 한눈에 확인해 보세요")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
                Spacer(minLength: 0)
            }
            .padding(PlanteriorSpacing.medium)
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
                query.isEmpty
                    || $0.element.displayName.localizedCaseInsensitiveContains(query)
            }
            .sorted {
                $0.element.displayName.localizedStandardCompare($1.element.displayName)
                    == .orderedAscending
            }
    }

    private var collectionCount: Int {
        collection.plants.filter { $0.displayName != "비공개 식물" }.count
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
