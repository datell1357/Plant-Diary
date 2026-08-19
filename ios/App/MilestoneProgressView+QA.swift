import SwiftUI

extension MilestoneProgressView {
    @ToolbarContentBuilder
    var qaMenu: some ToolbarContent {
        #if DEBUG
            if Self.allowsLocalAuthoritativeService, showsQAMenu {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu("QA 진행") {
                        qaEventButtons
                        Button(
                            "오프라인 이벤트 대기",
                            action: queueOffline
                        )
                        .accessibilityIdentifier("milestones.qa.queue")
                        Button("재연결") {
                            repository.reconnect()
                            lastOutcome = "서버 상태와 재조정됨"
                        }
                        .accessibilityIdentifier(
                            "milestones.qa.reconnect"
                        )
                        Button("QA 메뉴 숨기기") {
                            showsQAMenu = false
                        }
                        .accessibilityIdentifier("milestones.qa.hide")
                    }
                    .accessibilityIdentifier("milestones.qa.menu")
                }
            }
        #else
            ToolbarItem(placement: .automatic) {
                EmptyView()
            }
        #endif
    }

    @ViewBuilder
    private var qaEventButtons: some View {
        Button("등록 승인 이벤트") {
            submit("todo16-registration-1", kind: .registration)
        }
        .accessibilityIdentifier("milestones.qa.registration")
        Button("중복 이벤트 재전송") {
            submit("todo16-registration-1", kind: .registration)
        }
        .accessibilityIdentifier("milestones.qa.duplicate")
        Button("물주기 승인 이벤트") {
            submit("todo16-watering-1", kind: .watering)
        }
        .accessibilityIdentifier("milestones.qa.watering")
        Button("미니홈 승인 이벤트") {
            submit("todo16-minihome-1", kind: .miniHomeSave)
        }
        .accessibilityIdentifier("milestones.qa.minihome")
    }
}
