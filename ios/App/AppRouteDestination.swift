import PlanteriorDesignSystem
import SwiftUI

struct AppRouteDestination: View {
    let route: AppRoute
    @EnvironmentObject var auth: AuthRuntime
    @ObservedObject private var collection = LocalPlantCollectionStore.shared

    var body: some View {
        switch route {
        case let .plant(target):
            if let index = collection.index(forRouteTarget: target.rawValue) {
                PlantCareDetailView(index: index)
            } else {
                placeholder(for: .unavailable)
            }
        case .miniHome:
            MiniHomeView()
        case .tabDetail(.collection):
            CollectionCareSummaryView()
        case .tabDetail(.settings):
            MilestoneProgressView()
        case .identificationDraft:
            IdentificationFlowView()
        case .manualRegistration:
            PlantRegistrationView()
        default:
            placeholder(for: route)
        }
    }

    private func placeholder(for route: AppRoute) -> some View {
        VStack(spacing: 12) {
            Image(systemName: imageName(for: route))
                .font(.system(size: 44))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text(message(for: route))
                .multilineTextAlignment(.center)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PlanteriorPalette.canvas.color)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier(for: route))
        .navigationTitle(title(for: route))
    }

    private func title(for route: AppRoute) -> String {
        switch route {
        case let .tabDetail(tab): "\(tab.title) 상세"
        case .plant: "식물 상세"
        case .miniHome: "나의 미니홈"
        case .identificationDraft: "사진 식별 준비"
        case .manualRegistration: "식물 직접 등록"
        case .unavailable: "항목을 찾을 수 없어요"
        }
    }

    private func message(for route: AppRoute) -> String {
        switch route {
        case .tabDetail: "이 탭의 탐색 위치는 다른 탭과 독립적으로 유지됩니다."
        case .plant: "선택한 식물의 관리 정보를 확인하세요."
        case .miniHome: "저장한 식물 공간을 확인하세요."
        case .identificationDraft: "동의한 사진으로 식별을 시작할 준비가 되었습니다."
        case .manualRegistration: "이름과 돌봄 정보를 직접 입력하세요."
        case .unavailable: "삭제되었거나 사용할 수 없는 항목입니다."
        }
    }

    private func imageName(for route: AppRoute) -> String {
        switch route {
        case .tabDetail: "square.stack.3d.up"
        case .plant: "leaf"
        case .miniHome: "house"
        case .identificationDraft: "camera.macro"
        case .manualRegistration: "square.and.pencil"
        case .unavailable: "questionmark.folder"
        }
    }

    private func identifier(for route: AppRoute) -> String {
        switch route {
        case let .tabDetail(tab): "\(tab.rawValue).detail"
        case .plant: "plant.detail"
        case .miniHome: "minihome.screen"
        case .identificationDraft: "identification.draft"
        case .manualRegistration: "registration.manual"
        case .unavailable: "route.unavailable"
        }
    }
}
