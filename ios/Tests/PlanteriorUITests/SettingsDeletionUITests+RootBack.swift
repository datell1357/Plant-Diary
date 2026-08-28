import XCTest

@MainActor
extension SettingsDeletionUITests {
    func testSettingsRootBackReturnsToPreviousTabInOneTap() {
        let app = figmaSettingsApp()
        app.launch()
        let collection = app.buttons["tab.collection"]
        XCTAssertTrue(collection.waitForExistence(timeout: 10))
        collection.tap()
        XCTAssertTrue(
            app.scrollViews["collection.screen"].waitForExistence(timeout: 5)
        )
        app.buttons["tab.settings"].tap()
        let back = app.buttons["settings.back"]
        XCTAssertTrue(back.waitForExistence(timeout: 5))
        attachScreenshot(named: "final-blocker-settings-402")

        let returned = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: app.scrollViews["collection.screen"]
        )
        back.tap()
        XCTAssertEqual(XCTWaiter.wait(for: [returned], timeout: 5), .completed)
        XCTAssertTrue(collection.isSelected)
    }
}
