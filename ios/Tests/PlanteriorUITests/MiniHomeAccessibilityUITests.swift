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

        for identifier in [
            "minihome.save",
            "minihome.close",
            "minihome.editor.category.plant",
            "minihome.editor.tray.0"
        ] {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.waitForExistence(timeout: 5))
            XCTAssertTrue(control.isHittable, "\(identifier) must be reachable at AX5")
        }
        let title = app.staticTexts["minihome.editor.title"]
        XCTAssertTrue(title.isHittable)
        attachScreenshot(named: "task-14-room-ax5")

        let roomSettings = app.descendants(matching: .any)
            .matching(identifier: "minihome.editor.room-settings")
            .firstMatch
        waitForMiniHomeElement(roomSettings) {
            title.tap()
        }
        let roomName = app.textFields["minihome.room-name"]
        XCTAssertTrue(roomName.waitForExistence(timeout: 5))
        XCTAssertEqual(roomName.frame.height, 44, accuracy: 1)
        XCTAssertTrue(roomName.isHittable)
    }
}
