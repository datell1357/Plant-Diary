import XCTest

@MainActor
final class ProgressionAccessibilityUITests: ProgressionUITestCase {
    func testEarnedMilestoneRemainsReachableAtAX5() {
        let app = progressionApp(
            options: ProgressionLaunchOptions(
                scenario: "earned",
                ax5: true,
                showControls: false
            )
        )
        app.launch()
        openProgression(in: app)
        attachProgressionScreenshot(named: "task-16-progress-ax5")

        app.swipeUp()
        let row = app.otherElements["milestone.row.registration-1"]
        let claim = app.buttons["milestone.claim.registration-1"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        XCTAssertTrue(claim.waitForExistence(timeout: 5))
        XCTAssertTrue(claim.isHittable)
        XCTAssertGreaterThanOrEqual(claim.frame.height, 44)
        attachProgressionScreenshot(
            named: "task-16-progress-ax5-actions"
        )
    }
}
