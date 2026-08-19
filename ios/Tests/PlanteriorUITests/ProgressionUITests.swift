import XCTest

@MainActor
final class ProgressionUITests: ProgressionUITestCase {
    func testApprovedDuplicateClaimAndOfflineReconcile() {
        let app = progressionApp()
        app.launch()
        openProgression(in: app)
        verifyInitialAndRegistration(in: app)
        claimRegistration(in: app)
        queueAndReconnect(in: app)
        performQA("milestones.qa.minihome", in: app)
        waitForLabel(
            "서버 경험치 350",
            identifier: "milestones.xp.server",
            in: app
        )
        attachEvidence(in: app)
        performQA("milestones.qa.hide", in: app)
        app.swipeDown()
        app.swipeDown()
        attachProgressionScreenshot(named: "task-16-progress")
    }
}
