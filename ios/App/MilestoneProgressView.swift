import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct MilestoneProgressView: View {
    @EnvironmentObject var auth: AuthRuntime
    @Environment(\.sizeCategory) var sizeCategory
    @StateObject var repository: MilestoneRepository
    @State var lastOutcome = "서버 진행 상태를 기다리는 중"
    @State var showsQAMenu: Bool

    init() {
        let allowsProgression =
            Self.allowsLocalAuthoritativeService
        _repository = StateObject(
            wrappedValue: MilestoneRepository(
                now: Self.runtimeNow,
                allowsLocalAuthoritativeService: allowsProgression
            )
        )
        _showsQAMenu = State(initialValue: Self.showsQAControls)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                summary
                milestoneRows
            }
            .padding(20)
        }
        .background(PlanteriorPalette.canvas.color)
        .environment(\.sizeCategory, effectiveSizeCategory)
        .navigationTitle("꾸미기 마일스톤")
        .navigationBarTitleDisplayMode(.inline)
        .planteriorInlineNavigationChrome()
        .accessibilityIdentifier("milestones.screen")
        .toolbar { qaMenu }
        .task(id: accountID) {
            repository.mount(accountID: accountID)
            repository.seedQAIfNeeded()
        }
    }
}
