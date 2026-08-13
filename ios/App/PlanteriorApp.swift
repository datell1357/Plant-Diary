import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

@main
struct PlanteriorApp: App {
    var body: some Scene {
        WindowGroup {
            AppShellView()
        }
    }
}

struct AppShellView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "leaf.fill")
                .font(.system(size: 52))
                .foregroundStyle(Color(red: 61 / 255, green: 102 / 255, blue: 66 / 255))
                .accessibilityHidden(true)
            Text("초보 식집사")
                .font(.title.bold())
            Text("나의 식물 생활을 시작해 보세요")
                .foregroundStyle(.secondary)
            Text(PlanteriorDomainModule.name)
                .font(.caption2)
                .hidden()
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(red: 252 / 255, green: 251 / 255, blue: 247 / 255))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("app.shell")
    }
}
