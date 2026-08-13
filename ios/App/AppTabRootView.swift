import PlanteriorDesignSystem
import SwiftUI

struct AppTabRootView: View {
    let tab: AppTab
    let openDetail: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: tab.systemImage + ".fill")
                .font(.system(size: 52))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text(tab.title)
                .font(PlanteriorTypography.screenTitle)
            PlanteriorPrimaryButton("상세 보기", action: openDetail)
                .frame(maxWidth: 240)
                .accessibilityLabel("\(tab.title) 상세 보기")
                .accessibilityIdentifier("\(tab.rawValue).open-detail")
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle(tab.title)
    }
}
