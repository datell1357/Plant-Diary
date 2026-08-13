import PlanteriorDesignSystem
import SwiftUI

struct AppRouteDestination: View {
    let route: AppRoute

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: imageName)
                .font(.system(size: 44))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text(title)
                .font(PlanteriorTypography.screenTitle)
            Text(message)
                .multilineTextAlignment(.center)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PlanteriorPalette.canvas.color)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
        .navigationTitle(title)
    }

    private var title: String {
        switch route {
        case let .tabDetail(tab): "\(tab.title) 상세"
        case .plant: "식물 상세"
        case .unavailable: "항목을 찾을 수 없어요"
        }
    }

    private var message: String {
        switch route {
        case .tabDetail: "이 탭의 탐색 위치는 다른 탭과 독립적으로 유지됩니다."
        case .plant: "선택한 식물의 관리 정보를 확인하세요."
        case .unavailable: "삭제되었거나 사용할 수 없는 항목입니다."
        }
    }

    private var imageName: String {
        switch route {
        case .tabDetail: "square.stack.3d.up"
        case .plant: "leaf"
        case .unavailable: "questionmark.folder"
        }
    }

    private var identifier: String {
        switch route {
        case let .tabDetail(tab): "\(tab.rawValue).detail"
        case .plant: "plant.detail"
        case .unavailable: "route.unavailable"
        }
    }
}
