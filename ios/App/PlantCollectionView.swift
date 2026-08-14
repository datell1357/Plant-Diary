import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

struct PlantCollectionView: View {
    let openLegacyDetail: () -> Void
    let openCamera: () -> Void
    @ObservedObject private var collection = LocalPlantCollectionStore.shared
    @State private var search = ""

    var body: some View {
        VStack(spacing: 16) {
            stateBanner
            TextField("내 식물 검색", text: $search)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("collection.search")
            if filteredPlants.isEmpty {
                ContentUnavailableView(
                    collection.plants.isEmpty
                        ? "등록한 식물이 없어요"
                        : "검색 결과가 없어요",
                    systemImage: "leaf",
                    description: Text(
                        collection.plants.isEmpty
                            ? "카메라 또는 직접 등록으로 시작해 보세요."
                            : "다른 검색어를 입력해 주세요."
                    )
                )
                .accessibilityIdentifier("collection.empty")
                if collection.plants.isEmpty {
                    Button("카메라로 등록", action: openCamera)
                        .accessibilityIdentifier("collection.empty.camera")
                    NavigationLink("직접 등록") {
                        PlantRegistrationView()
                    }
                    .accessibilityIdentifier("collection.empty.manual")
                }
            } else {
                List(filteredPlants, id: \.offset) { item in
                    NavigationLink {
                        PlantCareDetailView(index: item.offset)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.element.displayName)
                                .font(PlanteriorTypography.body)
                            Text("돌봄 상세 보기")
                                .foregroundStyle(
                                    PlanteriorPalette.textSecondary.color
                                )
                        }
                    }
                    .accessibilityIdentifier("collection.row.\(item.offset)")
                    .onAppear {
                        collection.rememberScrollAnchor(item.offset)
                    }
                }
                .listStyle(.plain)
            }
        }
        .padding(16)
        .navigationTitle("도감")
        .task {
            collection.loadQAFixtureIfNeeded()
            collection.setSnapshotStateFromQA()
            collection.restoreScrollAnchor()
        }
        .toolbar {
            Button("도감 상세", action: openLegacyDetail)
                .accessibilityIdentifier("collection.open-detail")
        }
    }

    private var filteredPlants: [(offset: Int, element: PlantRegistrationDraft)] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return collection.plants.enumerated()
            .filter { $0.element.displayName != "비공개 식물" }
            .filter {
                query.isEmpty
                    || $0.element.displayName.localizedCaseInsensitiveContains(query)
            }
            .sorted {
                $0.element.displayName.localizedStandardCompare(
                    $1.element.displayName
                ) == .orderedAscending
            }
    }

    @ViewBuilder
    private var stateBanner: some View {
        switch collection.snapshotState {
        case .loading:
            ProgressView("도감을 불러오는 중")
                .accessibilityIdentifier("collection.loading")
        case .error:
            ContentUnavailableView(
                "도감을 불러오지 못했어요",
                systemImage: "exclamationmark.triangle"
            )
            .accessibilityIdentifier("collection.error")
        case .partial:
            Text("일부 식물 정보만 표시 중이에요.")
                .accessibilityIdentifier("collection.partial")
        case .stale:
            Text("저장된 정보를 표시하고 있어요.")
                .accessibilityIdentifier("collection.stale")
        case .content:
            EmptyView()
        }
    }
}
