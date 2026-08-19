import XCTest

@MainActor
final class MiniHomeAccessibilityUITests: XCTestCase, MiniHomeUITestSupport {
    func testEditorControlsRemainReachableAtAX5() {
        let app = miniHomeApp()
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] =
            "todo14-ax5-\(UUID())"
        app.launchEnvironment["QA_MINIHOME_SIZE_CATEGORY"] = "AX5"
        app.launch()
        openEditor(in: app)

        app.swipeUp()
        XCTAssertTrue(
            app.buttons["minihome.save"].waitForExistence(timeout: 5)
        )
        XCTAssertGreaterThanOrEqual(
            app.textFields["minihome.room-name"].frame.height,
            44
        )
        XCTAssertTrue(app.buttons["minihome.save"].isHittable)
        XCTAssertTrue(app.buttons["minihome.close"].isHittable)
        attachScreenshot(named: "task-14-room-ax5")
    }
}
