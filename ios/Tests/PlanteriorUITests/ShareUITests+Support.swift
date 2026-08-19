import XCTest

extension ShareUITests {
    func shareApp(ax5: Bool = false) -> XCUIApplication {
        let app = miniHomeApp()
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] = UUID().uuidString
        app.launchEnvironment["QA_SHARE_FIXTURE"] = "1"
        app.launchEnvironment["QA_SHARE_ONLINE"] = "1"
        app.launchEnvironment["QA_SHARE_NOW"] = "2026-08-11T02:00:00Z"
        app.launchEnvironment["QA_SHARE_SHEET_RESULT"] = "cancelled"
        if ax5 {
            app.launchEnvironment["QA_MINIHOME_SIZE_CATEGORY"] = "AX5"
        }
        return app
    }

    func openShare(in app: XCUIApplication) {
        XCTAssertTrue(
            app.scrollViews["minihome.screen"]
                .waitForExistence(timeout: 10)
        )
        let share = app.buttons["minihome.share"]
        XCTAssertTrue(share.waitForExistence(timeout: 5))
        share.tap()
        XCTAssertTrue(
            app.otherElements["minihome.share.screen"]
                .waitForExistence(timeout: 10)
        )
        XCTAssertTrue(
            app.images["minihome.share.preview"]
                .waitForExistence(timeout: 10)
        )
    }

    func tap(_ identifier: String, in app: XCUIApplication) {
        let button = app.buttons[identifier]
        if !button.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(button.waitForExistence(timeout: 5))
        XCTAssertTrue(button.isHittable)
        button.tap()
    }

    func waitForShareState(
        _ label: String,
        in app: XCUIApplication
    ) {
        let state = app.staticTexts["minihome.share.state"]
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", label),
            object: state
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [changed], timeout: 5),
            .completed
        )
    }
}
